# Architecture

## Modules

The repository is one Maven reactor (`pom.xml` at the root). Every library module has the same version.

```
future-selector        saga-api                    sagalog-api
(selectable futures)   (Saga, SagaNode, builder)   (SagaLog SPI, pool, memory log)
        │               │            │              │              │
        └───────┬───────┘            │              │              │
                │      ┌─────────────┘              │              │
                ▼      ▼                            ▼              ▼
          saga-execution ◄────────────────── sagalog-api   sagalog-file   sagalog-postgres
          (runs sagas)                                    (file log)     (PostgreSQL log)

          saga-serialization ◄── saga-api   (sagas to and from JSON)

          saga-samples/polyglot-persistence  (uses all but sagalog-postgres)
```

| Module | Depends on (inside the project) | Third-party runtime dependencies |
|---|---|---|
| `future-selector` | none | none |
| `saga-api` | none | none |
| `sagalog-api` | none | none |
| `saga-execution` | `saga-api`, `sagalog-api`, `future-selector` | none |
| `saga-serialization` | `saga-api` | `org.json:json` |
| `sagalog-file` | `sagalog-api` | none (it has its own copy of Tape's `QueueFile`, see [NOTICE](../NOTICE)) |
| `sagalog-postgres` | `sagalog-api` | HikariCP, PostgreSQL JDBC driver, SLF4J API |
| `saga-samples/polyglot-persistence` | all modules except `sagalog-postgres` | Undertow, org.json |

`saga-parent` is a pom-only module that the library modules use as their parent. It adds nothing to the root pom.

An application typically depends on `saga-execution` and one saga-log provider. It adds `saga-serialization` when it
stores or exchanges saga definitions as JSON.

## Java modules (JPMS)

Every library module is a named Java module with a `module-info.java`.

| Java module | Exports | Requires | Service |
|---|---|---|---|
| `no.cantara.concurrent.futureselector` | `no.cantara.concurrent.futureselector` | `java.base` | |
| `no.cantara.saga.api` | `no.cantara.saga.api` | `java.base` | |
| `no.cantara.sagalog` | `no.cantara.sagalog` | `java.base` | provides `SagaLogInitializer` (memory log) |
| `no.cantara.saga.execution` | `no.cantara.saga.execution`, `...execution.adapter` | `transitive` saga-api, sagalog, futureselector | uses `SagaLogInitializer` |
| `no.cantara.saga.serialization` | `no.cantara.saga.serialization` | `transitive` saga-api; `org.json` | |
| `no.cantara.sagalog.file` | nothing | `no.cantara.sagalog` | provides `SagaLogInitializer` (file log) |
| `no.cantara.sagalog.postgres` | nothing | `no.cantara.sagalog`, `java.sql`, `com.zaxxer.hikari`, `org.postgresql.jdbc`, `org.slf4j` | provides `SagaLogInitializer` (PostgreSQL log) |

Notes:

- `requires transitive` means a module that requires `no.cantara.saga.execution` can use `Saga`, `SagaLog` and
  `SelectableFuture` without requiring their modules itself.
- The provider modules export nothing. An application reaches them only through `ServiceLoader` and the
  `SagaLogInitializer` interface (see below). Their packages are `opens`, for reflection.
- `org.postgresql.jdbc` is an automatic module: the PostgreSQL driver has no module descriptor, but it declares that
  name in its manifest. Every other dependency is a named module.
- The libraries also work on the class path.

## Saga-log providers and service loading

A saga-log implementation is a *provider*: a `SagaLogInitializer` registered as a Java service, both in
`module-info.java` (`provides`) and in `META-INF/services/no.cantara.sagalog.SagaLogInitializer`. An application
picks one by class name:

```java
SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
        .filter(p -> p.type().getName().equals("no.cantara.sagalog.file.FileSagaLogInitializer"))
        .findFirst().orElseThrow().get();
SagaLogPool pool = initializer.initialize(configuration);
```

| Provider class | Module | Configuration keys |
|---|---|---|
| `no.cantara.sagalog.memory.MemorySagaLogInitializer` | `sagalog-api` | `cluster.instance-id` |
| `no.cantara.sagalog.file.FileSagaLogInitializer` | `sagalog-file` | `filesagalog.folder`, `cluster.instance-id` |
| `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` | `sagalog-postgres` | `cluster.owner`, `cluster.name`, `cluster.instance-id`, `postgres.driver.*`, `connection-pool.max-size` |

`configurationOptionsAndDefaults()` on each initializer returns its keys with example values. In a modular
application, the module that calls `ServiceLoader.load` must declare `uses no.cantara.sagalog.SagaLogInitializer`.
When the libraries are put in a shaded ("fat") jar, the service files must be merged (for the Maven Shade plugin,
`ServicesResourceTransformer`), or only one provider is found.

## Threading

`SagaExecution` runs on a `SelectableThreadPoolExectutor` that the caller creates and owns (from
`future-selector`). The library starts no threads of its own.

- A saga execution is a set of *walks*. One walk starts at `S`. When a node has several outgoing nodes, the walk
  submits a new walk to the pool for each extra branch, and continues down the last one itself.
- A walk that reaches a node with several incoming nodes waits, on its pool thread, until the other incoming nodes
  are done. Only one walk continues past a join; the others end there.
- Every saga-log write is joined before the walk continues.
- Each action runs on the pool thread of the walk that reached it.

Because walks block pool threads while they wait, the pool must have enough threads for the branches of every saga
that runs at the same time. A `ThreadPoolExecutor` with an unbounded queue never grows beyond its core size, so set
the core size for the expected parallelism. See [Known limitations](limitations.md#thread-starvation).

`onComplete` runs on the pool thread that completes the saga. Blocking in it holds that thread.

## Life of one saga execution

```
caller                     SagaExecution / pool threads                      SagaLog
  │ executeSaga(id, input)        │                                               │
  │──────────────────────────────►│ walk at S: write Start(S, name, input) ──────►│
  │◄─── handoff future completes ─│                                               │
  │                               │ walks: for each node                          │
  │                               │   write Start(node) ─────────────────────────►│
  │                               │   adapter.executeAction(...)                  │
  │                               │   write End(node, output) ───────────────────►│
  │                               │ walk at E: write End(E) ─────────────────────►│
  │                               │ onComplete(result)                            │
  │◄─ completion future completes─│                                               │
```

On `AbortSagaException`, the walk writes `Abort` on its node and the backward walk writes `Comp` for each compensated
node and `End` on `E`, before `onComplete` and the completion future (see [Concepts](concepts.md#rollback)).

## Where the state lives

- The `Saga` definition and the adapters are immutable from the library's point of view and are shared.
- All state of a running execution is in memory, in the traversal, and in the saga log.
- Only the saga log survives a restart. Whatever recovery needs must be in the log: the saga name, the input, and
  each completed node's output.
