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
- `Shipping` is registered without a compensating action, so a rollback leaves its effect in place and writes no
  entries for it. A compensating action receives `null` as the output when the action started but never ended.
- An adapter instance is shared by every node and every saga that names it, on many threads: keep it stateless.

The start and end adapters (`SagaStart`, `SagaEnd`) are registered by `AdapterLoader` itself.

## 3. Create a thread pool

```java
SelectableThreadPoolExecutor pool = new SelectableThreadPoolExecutor(
        10, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
```

Size it for the number of branches of all sagas that run at the same time: a walk waiting at a join node, or for a
saga-log write, holds its thread, and with an unbounded queue the pool does not grow past its core size. Limit how
many sagas you run at once in your application; the library does not. Keep the default `AbortPolicy`, or another
policy that does not run tasks on the submitting thread (`CallerRunsPolicy` makes the sagas fail). See
[Architecture](architecture.md#threading).

## 4. Choose and open a saga log

```java
SagaLogInitializer initializer = ServiceLoader.load(SagaLogInitializer.class).stream()
        .filter(p -> p.type().getName().equals("no.cantara.sagalog.memory.MemorySagaLogInitializer"))
        .findFirst().orElseThrow().get();
SagaLogPool logPool = initializer.initialize(Map.of("cluster.instance-id", "01"));

SagaLogId logId = logPool.registerInstanceLocalIdFor("log-1");
SagaLogOwner owner = new SagaLogOwner("main");
SagaLog sagaLog = logPool.tryTakeOwnership(owner, logId);
```

| Provider | Use it for | Page |
|---|---|---|
| `no.cantara.sagalog.memory.MemorySagaLogInitializer` | tests and applications that need no recovery | [sagalog-api](modules/sagalog-api.md#the-memory-saga-log) |
| `no.cantara.sagalog.file.FileSagaLogInitializer` | one process on one machine | [sagalog-file](modules/sagalog-file.md) |
| `no.cantara.sagalog.postgres.PostgresSagaLogInitializer` | several instances, shared storage | [sagalog-postgres](modules/sagalog-postgres.md) |

A log has one writer at a time. Concurrent sagas need one log each; a common pattern is to register several
instance-local log ids at start-up and take one per saga with `tryAcquire(owner)`, then `releaseOwnership(owner, logId)` when the
saga is done (see [sagalog-api](modules/sagalog-api.md#pools-and-ownership)).

## 5. Run the saga and wait

```java
String executionId = UUID.randomUUID().toString();
SagaHandoffControl control = new SagaExecution(sagaLog, pool, saga, adapters)
        .executeSaga(executionId, "{\"order\":1}", false, result -> sagaLog.truncate().join());

control.getHandoffFuture().join();                                 // the saga is in the log
SagaHandoffResult result = control.getCompletionFuture().join();   // the saga is finished
logPool.releaseOwnership(owner, logId);
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
Start:S Start:charge-card Start:reserve-stock End:reserve-stock Abort:charge-card CompStart:reserve-stock Comp:reserve-stock End:E
```

`reserve-stock` ended and has a compensating action, so it is compensated: `CompStart` before the compensating action,
`Comp` after. The aborted node itself is not compensated: it is the action that decided to fail. `Shipping` has no
compensating action; if it had ended, it would get no entries in the rollback and its effect would stay. The completion future fails with a `CompletionException` whose cause is the `AbortSagaException`, and
`onComplete` receives a result with `isFailure()` true.

Any other exception from an action is *not* rolled back. See [Known limitations](limitations.md#exceptions-other-than-abortsagaexception).

## 7. Recover unfinished sagas

After a restart, read the logs this instance owns, find executions without `End` on `E`, and run them again with
`recovery = true`:

```java
static void recover(SagaLog sagaLog, SelectableThreadPoolExecutor pool,
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
- Recovery continues forward only; the last entry of each node decides. See [saga-execution](modules/saga-execution.md#recovery-mode).

## 8. Observe execution

The longer `executeSaga` overload takes a `preAction` and a `postAction` callback, called before and after each node
with a `SagaExecutionTraversalContext` (the node, its adapter, the ancestors, the outputs so far, and after the action
the serialized output). Use them for logging, metrics or tracing.

`SagaHandoffControl.getTraversalResult().waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(timeout, unit)`
waits for every walk of a successful saga to end, and checks that every node was visited; it is meant for tests.

## 9. Shut down

The library owns no threads and no shutdown order: the application does. Shut down in this order, so that no saga is
cut off between two steps and nothing calls the saga-log pool after it has closed:

1. **Stop new work.** Stop accepting requests that start sagas, and stop your recovery trigger, so that no new saga
   and no new `tryAcquire` or `tryTakeOwnership` starts.
2. **Wait for the running sagas,** on their completion futures (or until their `onComplete` has run), with a bound
   that you choose.
3. **Shut down the thread pool you created.** `shutdown()` lets walks that are already queued run. A running walk
   continues on its own thread: after a step with one next step, the walk goes on to it without the pool, so a
   linear saga that is mid-way finishes even after the pool is shut down. Only where the saga forks (a step with two
   or more next steps) does the walk hand every next step but the last to the pool. After shutdown that hand-over is
   rejected: the saga fails through its futures with `RejectedExecutionException`, and `onComplete` is called once
   with a failure. The entries written so far stay in the log unless `onComplete`
   truncates it; keep them when the result is a failure if recovery should finish the saga after a restart. If you use `shutdownNow()`, cancel the tasks it returns, so that a saga whose walks were all
   queued completes its futures:

   ```java
   for (Runnable task : pool.shutdownNow()) {
       if (task instanceof Future) {
           ((Future<?>) task).cancel(false);
       }
   }
   ```

4. **Call `SagaLogPool.shutdown()`** to close the pool's logs. After that the pool refuses `connect`, `tryAcquire` and
   `tryTakeOwnership` with `SagaPoolShutdownException`. A caller that still arrives late (a recovery timer, a request
   in flight) should treat that exception as "shutting down", not as an error. When the call is made inside a
   `CompletableFuture`, it arrives as the cause of a `CompletionException`.
5. Then close what the logs use: the `DataSource`, or the folder of the file log.

**Release what a saga holds in `onComplete`,** or on the completion future: its saga log (`releaseOwnership(owner,
logId)`) and anything you use to limit concurrent sagas, such as a semaphore permit. `onComplete` sees every outcome,
including a walk the thread pool rejected; a `catch` around `executeSaga` does not, because `executeSaga` reports a
rejection through the futures, not by throwing.

**Testing shutdown with a saga between two steps.** Give one step an adapter that blocks on a latch the test controls,
and let that step fork into two or more next steps (for example `S -> a -> (b, c) -> E`, with `a` held): start the
saga, wait until `a` has entered the adapter, start the shutdown, and release the latch before or after the thread
pool is shut down. Released before, the saga succeeds; released after, the hand-over at the fork is rejected and the
saga fails. With a linear `S -> a -> b -> E` nothing is handed to the pool after `a`, so the saga succeeds either way.
`SagaExecutionShutdownTest` in `saga-execution` uses a blocking adapter this way.
