# saga-execution

`no.cantara.saga:saga-execution` · Java module `no.cantara.saga.execution` · depends on `saga-api`, `sagalog-api`,
`future-selector` (all `requires transitive`)

Runs sagas: walks the graph on a thread pool, calls the adapters, writes every step to a saga log, rolls back on
`AbortSagaException`, and continues an interrupted execution in recovery mode. For the flow, see
[Concepts](../concepts.md#execution) and [Architecture](../architecture.md#life-of-one-saga-execution).

## Types

Package `no.cantara.saga.execution`:

| Type | Purpose |
|---|---|
| `SagaExecution` | Binds a saga, a saga log, a thread pool and an adapter loader; `executeSaga(...)` starts one execution. |
| `SagaHandoffControl` | Returned by `executeSaga`: the handoff future, the completion future, and the execution's inputs. |
| `SagaHandoffResult` | The result: `getExecutionId()`, `isSuccess()`, `isFailure()`, `getFailureCause()`. |
| `SagaOnCompleteException` | The completion future's failure when the saga succeeded but `onComplete` threw. |
| `SagaExecutionTraversalContext` | Given to the `preAction` and `postAction` callbacks. |
| `SagaTraversalResult` | The traversal's state; `waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(...)`, for tests. |

Package `no.cantara.saga.execution.adapter`:

| Type | Purpose |
|---|---|
| `SagaAdapter<OUTPUT>` | The adapter interface: `name()`, `executeAction`, `executeCompensatingAction`, `serializer()`. |
| `Adapter<O>` | A ready adapter built from lambdas; serializes with `toString()` and a `String` constructor. |
| `ActionOutputSerializer<V>` | Turns an output into text and back: `serializationClazz()`, `serialize`, `deserialize`. |
| `ActionOutputSerializerToStringAndStringConstructor<V>` | The serializer `Adapter` uses. For `String` it returns the text as is. |
| `AdapterLoader` | Adapters by name. Registers the start and end adapters itself. |
| `AbortSagaException` | Thrown by an action to roll the saga back. |

## SagaExecution

```java
SagaExecution execution = new SagaExecution(sagaLog, pool, saga, adapterLoader);

SagaHandoffControl control = execution.executeSaga(executionId, sagaInput, recovery, onComplete);
SagaHandoffControl control = execution.executeSaga(executionId, sagaInput, recovery, onComplete, preAction, postAction);
```

| Parameter | Meaning |
|---|---|
| `executionId` | The id of this execution, stored on every log entry. Unique; reused only for recovery. |
| `sagaInput` | Passed to every action. Serialized with the start adapter (`toString()`) into the first log entry. |
| `recovery` | `false` for a new execution. `true` to continue an execution from the entries already in the log. |
| `onComplete` | Called once when the execution is finished; may be `null`. |
| `preAction`, `postAction` | Called before and after each node. |

An instance can run any number of executions, one after another or at the same time, as long as each has its own saga
log, or the log tolerates concurrent writers.

### The two futures

- **Handoff future**: completes when the `Start` entry on `S` has been written (in recovery, at once). After it, the
  saga survives a crash of the process.
- **Completion future**: completes after `End` on `E` has been written and `onComplete` has returned.

| Outcome | `onComplete` receives | Completion future |
|---|---|---|
| Success | a success result | completes with the result |
| Success, but `onComplete` throws | a success result | fails with `SagaOnCompleteException` (cause: the callback's exception) |
| `AbortSagaException`, rolled back | a failure result with the abort | fails with the `AbortSagaException` |
| Rolled back, and `onComplete` throws | a failure result | fails with the `AbortSagaException`; the callback's exception is suppressed on it |
| Another exception from an action or the log | a failure result | fails with that exception; nothing is rolled back |

`onComplete` runs on the pool thread that finished the saga, never on the thread that called `executeSaga` (unless
the pool's rejection policy runs tasks on the caller).

### Recovery mode

With `recovery = true`, the execution reads the log's entries for `executionId` first:

- `S`: nothing is written; the handoff future completes at once.
- A node with an `End` entry: the action is not run; the logged output is deserialized and passed on.
- A node with only a `Start` entry: the action runs again (no second `Start` is written).
- A node without entries: runs as normal.

See [Using the library](../using.md#7-recover-unfinished-sagas) for how to find what to recover.

## Adapters

`Adapter<O>` covers most cases:

```java
new Adapter<>(JsonNode.class, "WriteToDb",
        (sagaInput, dependeesOutput) -> db.write(sagaInput),          // the action
        (sagaInput, output) -> db.delete(output));                    // the compensating action
```

Its constructors: name only (action returns null, no compensation), with an action, and with an action and a
compensating action. The output type needs a public `String` constructor for `deserialize`, unless it is `String`.

Implement `SagaAdapter<O>` directly for another serialization:

```java
class WriteToDb implements SagaAdapter<JsonNode> {
    public String name() { return "WriteToDb"; }
    public JsonNode executeAction(SagaNode node, Object input, Map<SagaNode, Object> dependeesOutput) { ... }
    public void executeCompensatingAction(SagaNode node, Object input, JsonNode output) { ... }
    public ActionOutputSerializer<JsonNode> serializer() { return JSON_SERIALIZER; }
}
```

Rules for adapters:

- One instance serves every node that names it, concurrently: no mutable state.
- The adapter name is stored in serialized sagas; renaming breaks them.
- Actions and compensating actions can run more than once; make them idempotent.
- A `null` output is written as no data, and read back as `null`.
- Throw `AbortSagaException` from an action to roll back. The aborting node is not compensated.
- `AdapterLoader.load` fails for an unknown adapter name only when the node is reached. Register every adapter
  before you start sagas.

## Behaviour to know

See [Known limitations](../limitations.md) for exceptions other than abort, thread starvation, a compensating action
that fails, recovery after a crash during rollback, and timeouts.
