# Using the library

This guide walks through a complete use: define a saga, implement its adapters, choose a saga log, run the saga, wait
for it, roll back, and recover after a crash. The examples are taken from a program that was run against the
libraries; the output shown is the real output.

## Dependencies

Add the execution module and one saga-log provider (see [README](../README.md#add-it-to-your-project) for the
repository and version):

```xml
<dependency>
    <groupId>no.cantara.saga</groupId>
    <artifactId>saga-execution</artifactId>
    <version>${distributed-saga.version}</version>
</dependency>
<!-- one of: the memory log is in sagalog-api (a dependency of saga-execution) -->
<dependency>
    <groupId>no.cantara.saga</groupId>
    <artifactId>sagalog-file</artifactId>         <!-- or sagalog-postgres -->
    <version>${distributed-saga.version}</version>
</dependency>
```

In a modular application:

```java
module my.app {
    requires no.cantara.saga.execution;   // also gives saga-api, sagalog-api and future-selector
    uses no.cantara.sagalog.SagaLogInitializer;
}
```

## 1. Define the saga

```java
Saga saga = Saga.start("Order")
        .linkTo("reserve-stock", "charge-card")
        .id("reserve-stock").adapter("Stock").linkTo("ship")
        .id("charge-card").adapter("Payment").linkTo("ship")
        .id("ship").adapter("Shipping").linkToEnd()
        .end();
```

`reserve-stock` and `charge-card` run in parallel; `ship` runs when both are done. `end()` validates the graph and
throws `SagaException` on a missing link target, a cycle, or a node not reachable from the start. Build a saga once
and reuse it: it is a definition, not an execution. See [saga-api](modules/saga-api.md).

## 2. Implement the adapters

The simplest adapter is `Adapter<O>`, built from an output type, a name, an action and a compensating action:

```java
AdapterLoader adapters = new AdapterLoader()
        .register(new Adapter<>(String.class, "Stock",
                (input, dependeesOutput) -> "reservation-42",
                (input, reservation) -> System.out.println("release " + reservation)))
        .register(new Adapter<>(String.class, "Payment",
                (input, dependeesOutput) -> "payment-7",
                (input, payment) -> System.out.println("refund " + payment)))
        .register(new Adapter<>(String.class, "Shipping",
                (input, dependeesOutput) -> "shipment-1"));
```

- `input` is the saga input; `dependeesOutput` maps each incoming `SagaNode` to its output.
- The action's return value is the node's output. It is serialized into the log and given to the compensating action.
- `Adapter` serializes with `toString()` and deserializes with the output type's `String` constructor. For any other
  type, implement `SagaAdapter<O>` and return your own `ActionOutputSerializer<O>`.
- Actions and compensating actions must be idempotent: they can run more than once.
- An adapter instance is shared by every node and every saga that names it, on many threads: keep it stateless.

The start and end adapters (`SagaStart`, `SagaEnd`) are registered by `AdapterLoader` itself.

## 3. Create a thread pool

```java
SelectableThreadPoolExectutor pool = new SelectableThreadPoolExectutor(
        10, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
```

Size it for the number of branches of all sagas that run at the same time: a walk waiting at a join node holds its
thread, and with an unbounded queue the pool does not grow past its core size. See
[Architecture](architecture.md#threading).

## 4. Choose and open a saga log

```java
SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
        .filter(p -> p.type().getName().equals("no.cantara.sagalog.memory.MemorySagaLogInitializer"))
        .findFirst().orElseThrow().get();
SagaLogPool logPool = initializer.initialize(Map.of("cluster.instance-id", "01"));

SagaLogId logId = logPool.registerInstanceLocalIdFor("log-1");
SagaLog sagaLog = logPool.tryTakeOwnership(new SagaLogOwner("main"), logId);
```

| Provider | Use it for | Page |
|---|---|---|
| `no.cantara.sagalog.memory.MemorySagaLogInitializer` | tests and applications that need no recovery | [sagalog-api](modules/sagalog-api.md#the-memory-saga-log) |
| `no.cantara.sagalog.file.FileSagaLogInitializer` | one process on one machine | [sagalog-file](modules/sagalog-file.md) |
| `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` | several instances, shared storage | [sagalog-postgres](modules/sagalog-postgres.md) |

A log has one writer at a time. Concurrent sagas need one log each; a common pattern is to register several
instance-local log ids at start-up and take one per saga with `tryAcquire(owner)`, then `release(logId)` when the
saga is done (see [sagalog-api](modules/sagalog-api.md#pools-and-ownership)).

## 5. Run the saga and wait

```java
String executionId = UUID.randomUUID().toString();
SagaHandoffControl control = new SagaExecution(sagaLog, pool, saga, adapters)
        .executeSaga(executionId, "{\"order\":1}", false, result -> sagaLog.truncate().join());

control.getHandoffFuture().join();                                 // the saga is in the log
SagaHandoffResult result = control.getCompletionFuture().join();   // the saga is finished
logPool.releaseOwnership(logId);
```

Output:

```
success: true
log after onComplete: 0
```

- `executionId` identifies this execution in the log. Use a value that is unique across restarts.
- The handoff future completes when the `Start` entry is written. A server can reply "accepted" at that point.
- `onComplete` is called once, when the saga is finished. The completion future completes only after it has
  returned, so a caller waiting on the future sees what `onComplete` did. The usual job of `onComplete` is to clean
  up the log: `truncate()` removes all its entries, so the next saga starts on a clean log.
- If `onComplete` throws after a successful saga, the completion future fails with `SagaOnCompleteException`.

## 6. Roll back with AbortSagaException

An action throws `AbortSagaException` to roll back the whole saga:

```java
new Adapter<>(String.class, "Payment",
        (input, dependeesOutput) -> { throw new AbortSagaException("card declined"); },
        (input, payment) -> System.out.println("refund " + payment))
```

With that adapter, the saga above prints:

```
release reservation-42
failed with no.cantara.saga.execution.adapter.AbortSagaException: card declined
Start:S Start:charge-card Start:reserve-stock End:reserve-stock Abort:charge-card Comp:reserve-stock End:E
```

`reserve-stock` ended, so it is compensated. The aborted node itself is not compensated: it is the action that
decided to fail. The completion future fails with a `CompletionException` whose cause is the `AbortSagaException`, and
`onComplete` receives a result with `isFailure()` true.

Any other exception from an action is *not* rolled back. See [Known limitations](limitations.md#exceptions-other-than-abortsagaexception).

## 7. Recover unfinished sagas

After a restart, read the logs this instance owns, find executions without `End` on `E`, and run them again with
`recovery = true`:

```java
static void recover(SagaLog sagaLog, SelectableThreadPoolExectutor pool,
                    Map<String, Saga> sagasByName, AdapterLoader adapters) {
    Map<String, List<SagaLogEntry>> entriesByExecutionId = sagaLog.readIncompleteSagas()
            .collect(Collectors.groupingBy(SagaLogEntry::getExecutionId));
    for (List<SagaLogEntry> entries : entriesByExecutionId.values()) {
        boolean finished = entries.stream().anyMatch(e ->
                e.getEntryType() == SagaLogEntryType.End && Saga.ID_END.equals(e.getNodeId()));
        if (finished) {
            continue;
        }
        SagaLogEntry start = entries.stream()
                .filter(e -> e.getEntryType() == SagaLogEntryType.Start && Saga.ID_START.equals(e.getNodeId()))
                .findFirst().orElseThrow();
        Saga saga = sagasByName.get(start.getSagaName());
        Object input = start.getJsonData(); // deserialize as your start adapter serialized it
        new SagaExecution(sagaLog, pool, saga, adapters)
                .executeSaga(start.getExecutionId(), input, true, r -> { })
                .getCompletionFuture().join();
    }
}
```

For a log that held `Start:S Start:reserve-stock End:reserve-stock`, recovery writes:

```
Start:S Start:reserve-stock End:reserve-stock Start:charge-card End:charge-card Start:ship End:ship End:E
```

`reserve-stock` is not run again; its logged output is passed on. Things to know:

- `readIncompleteSagas()` returns every entry in the log, finished executions included. Filter on `End` of `E`.
- The library stores the saga input as the start adapter serialized it (`toString()`), and does not read it back.
  Your code turns `getJsonData()` back into the input object.
- Keep a map from saga name to `Saga` definition, so the log's saga name finds the definition.
- Logs of other instances (`SagaLogPool.clusterWideLogIds()`) can be recovered too: `connect` or take ownership of
  each, recover, then `remove` the log from the pool so its owner can use it again.
- Recovery continues forward only. See [Known limitations](limitations.md#recovery-after-a-crash-during-rollback).

## 8. Observe execution

The longer `executeSaga` overload takes a `preAction` and a `postAction` callback, called before and after each node
with a `SagaExecutionTraversalContext` (the node, its adapter, the ancestors, the outputs so far, and after the action
the serialized output). Use them for logging, metrics or tracing.

`SagaHandoffControl.getTraversalResult().waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(timeout, unit)`
waits for every walk of a successful saga to end, and checks that every node was visited; it is meant for tests.

## 9. Shut down

Shut down the thread pool you created, and call `SagaLogPool.shutdown()` to close the pool's logs.
