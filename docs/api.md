---
title: "API reference"
description: "Every public type an application uses: the saga builder, adapters, the execution and its futures, the thread pool, the saga log and its pool, the providers and the exceptions."
group: Reference
order: 20
---

# API reference

The public API is what the library modules export (see [Architecture](architecture.md#java-modules-jpms)). An
application usually depends on `saga-execution`, which brings `saga-api`, `sagalog-api` and `future-selector` with it,
and on one saga-log provider. This page lists every public type by task; the module pages give the behaviour in
detail.

| Package | Module | What it holds |
|---|---|---|
| `no.cantara.saga.api` | `saga-api` | The saga definition and its builder |
| `no.cantara.saga.execution` | `saga-execution` | The execution, its futures, its state |
| `no.cantara.saga.execution.adapter` | `saga-execution` | Adapters, the adapter loader, output serializers, `AbortSagaException` |
| `no.cantara.concurrent.futureselector` | `future-selector` | The thread pool the execution runs on, and its futures |
| `no.cantara.sagalog` | `sagalog-api` | The saga-log SPI, the pool, ownership, entries |
| `no.cantara.sagalog.postgres` | `sagalog-postgres` | The PostgreSQL provider and `PostgresSagaLogException` |
| `no.cantara.saga.serialization` | `saga-serialization` | Sagas to and from JSON |

`sagalog-file` exports nothing, and the memory log's package is not exported: an application reaches both through
`ServiceLoader` and `SagaLogInitializer`.

## Define a saga

`no.cantara.saga.api`. A saga is built once and is immutable; one `Saga` serves any number of executions.

```java
Saga saga = Saga.start("order")                 // the start node S, linked to:
        .linkTo("reserve-stock", "charge-card")
        .id("reserve-stock").adapter("stock").linkTo("ship")
        .id("charge-card").adapter("payment").linkTo("ship")
        .id("ship").adapter("shipping").linkToEnd()
        .end();                                 // validates the graph
```

| Type | Member | Meaning |
|---|---|---|
| `Saga` | `static start(String name)` | Starts a builder. The start node has id `S` (`ID_START`) and adapter `SagaStart` (`ADAPTER_START`). |
| `Saga.SagaBuilder.OutgoingBuilder` | `linkTo(String...)`, `linkToEnd()` | The links of the node being built. `linkToEnd()` links it to `E` (`ID_END`, adapter `SagaEnd`). |
| `Saga.SagaBuilder` | `id(String)` | Starts the next node. |
| `Saga.SagaBuilder.AdapterBuilder` | `adapter(String)` | The adapter name of that node. |
| `Saga.SagaBuilder` | `end()` | Builds and validates the saga: every link points to a node, no cycle, every node reachable from `S`. |
| `Saga` | `name`, `getStartNode()`, `getEndNode()`, `nodes()` | Reading a saga. |
| `Saga` | `depthFirstPreOrderFullTraversal(...)`, `reverseDepthFirstPreOrderFullTraversal(...)` | Visit every node, with the ids already visited on the path. |
| `SagaNode` | `id`, `adapter`, `incoming`, `outgoing` | One node; equal by id. |
| `SagaException` | | An invalid saga (unchecked). |

See [saga-api](modules/saga-api.md).

## Write adapters

`no.cantara.saga.execution.adapter`. An adapter is the code of a node. One instance serves every node that names it,
in every execution, on many threads: it must be stateless and thread-safe.

| Type | Member | Meaning |
|---|---|---|
| `SagaAdapter<OUTPUT>` | `name()` | The name nodes refer to. |
| | `executeAction(node, sagaInput, dependeesOutput)` | The action. Gets the saga input and the outputs of the nodes it depends on (`Map<SagaNode, Object>`), returns its output. Throws `AbortSagaException` to roll the saga back. |
| | `executeCompensatingAction(node, sagaInput, actionOutput)` | Undoes the action. `actionOutput` is `null` when the action started but did not end. |
| | `hasCompensatingAction()` | Whether rollback runs and logs a compensation for this adapter. |
| | `serializer()` | Turns the output into text for the log and back. |
| `Adapter<O>` | `Adapter(outputClass, name)` | An adapter whose action returns `null` and that has no compensating action. |
| | `Adapter(outputClass, name, action)` | With an action, a `BiFunction<Object, Map<SagaNode, Object>, O>`. |
| | `Adapter(outputClass, name, action, compensation)` | With a compensating action, a `BiConsumer<Object, O>`. |
| `AdapterLoader` | `register(SagaAdapter<?>)` | Registers an adapter by its name; returns the loader, so calls chain. |
| | `load(SagaNode)` | The adapter of a node. |
| `ActionOutputSerializer<V>` | `serialize(V)`, `deserialize(String)`, `serializationClazz()` | The output's text form in the log. |
| `ActionOutputSerializerToStringAndStringConstructor<V>` | | `toString()` one way, the class's `String` constructor the other. |
| `AbortSagaException` | | Thrown by an action: the saga is rolled back. Any other exception fails the saga without rollback. |

See [saga-execution](modules/saga-execution.md#adapters).

## Run a saga

`no.cantara.saga.execution`.

```java
SagaExecution execution = new SagaExecution(sagaLog, threadPool, saga, adapterLoader);
SagaHandoffControl control = execution.executeSaga(executionId, input, false, result -> sagaLog.truncate().join());
control.getHandoffFuture().join();      // the saga is durable: Start on S is written
control.getCompletionFuture().join();   // the saga is finished, and onComplete has returned
```

| Type | Member | Meaning |
|---|---|---|
| `SagaExecution` | `SagaExecution(sagaLog, executor, saga, adapterLoader)` | One saga, one log, one pool. The caller owns the pool and the log. |
| | `executeSaga(executionId, sagaInput, recovery, onComplete)` | Starts one execution and returns at once. `recovery = true` continues an execution from its entries in the log. `onComplete` may be `null`. |
| | `executeSaga(..., preAction, postAction)` | The same, with callbacks around each node's visit. |
| `SagaHandoffControl` | `getHandoffFuture()` | Completes when `Start` on `S` is in the log; fails at once with the first failure. |
| | `getCompletionFuture()` | Completes after `End` on `E` is written and `onComplete` has returned. Fails with the `AbortSagaException` after a rollback, or with the first failure. |
| | `getState()` | A `SagaExecutionState` snapshot, without waiting. |
| | `getExecutionId()`, `getSagaInput()`, `getSaga()`, `getSagaLog()`, `getAdapterLoader()`, `getTraversalResult()` | What the execution was started with. |
| `SagaHandoffResult` | `getExecutionId()`, `isSuccess()`, `isFailure()`, `getFailureCause()` | What `onComplete` gets. |
| `SagaExecutionState` | `getPhase()`, `getRunningNodeIds()`, `getWaitingNodeIds()`, `getVisitedNodeIds()`, `getWalks()`, `getFailure()`, `getExecutionId()` | Where an execution is. `Phase` is `FORWARD`, `ROLLBACK` or `COMPLETED`. |
| `SagaExecutionTraversalContext` | `getNode()`, `getAdapter()`, `getOutput()`, `getOutputByNode()`, `getAncestors()` | What `preAction` and `postAction` get. |
| `SagaTraversalResult` | `waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(timeout, unit)` | For tests: waits for the traversal and checks it reached every node; `TimeoutRuntimeException` after the timeout. |
| `SagaOnCompleteException` | `getExecutionId()` | `onComplete` threw on a successful saga; the completion future fails with this, the cause being what `onComplete` threw. |

`executeSaga` does not throw a rejection by the pool: it reaches the caller through the futures. An `Error` thrown by
the executor's `submit` for the first walk fails the saga, runs `onComplete`, and is then rethrown by `executeSaga`.
See [saga-execution](modules/saga-execution.md#sagaexecution).

## The thread pool

`no.cantara.concurrent.futureselector`. The execution runs on a `SelectableThreadPoolExecutor` the application
creates, sizes and shuts down.

| Type | Meaning |
|---|---|
| `SelectableThreadPoolExecutor` | A `ThreadPoolExecutor` whose `submit` returns `SelectableFuture`. The constructors are those of `ThreadPoolExecutor`. |
| `SelectableThreadPoolExectutor` | The old, misspelt name; deprecated, the same class. |
| `SelectableFuture<V>` | A `CompletableFuture` that is also a `RunnableFuture`. |
| `FutureSelector<F, C>` | Waits for the first of several futures to complete: `add(future, control)`, `select()`, `select(timeout, unit)` (`null` on timeout), `pending()`. |
| `Selection<F, C>` | The completed `future` and its `control` object. |
| `ExecutionRuntimeException`, `InterruptedRuntimeException`, `TimeoutRuntimeException` | Unchecked forms of the concurrency exceptions, with the cause kept. |
| `Utils.launder(Throwable)` | Turns a `Throwable` into an unchecked exception of the right type. |

The rejection policy must not run tasks on the submitting thread (no `CallerRunsPolicy`, no `Discard` policies). See
[future-selector](modules/future-selector.md) and [Known limitations](limitations.md#the-executor-rejection-shutdown-and-caller-runs).

## The saga log

`no.cantara.sagalog`. The engine writes to one `SagaLog`; the application gets it from a `SagaLogPool`.

| Type | Member | Meaning |
|---|---|---|
| `SagaLog` | `write(SagaLogEntryBuilder)` | Appends an entry. Every provider here returns a future that is already complete, with the stored entry or the failure. |
| | `truncate()`, `truncate(SagaLogEntryId)` | Removes every entry, or every entry up to and including the id. |
| | `readIncompleteSagas()` | Every entry in the log, in write order. |
| | `readEntries(executionId)` | The entries of one execution, in write order. |
| | `getSnapshotOfSagaLogEntriesByNodeId(executionId)` | One execution's entries grouped by node. |
| | `builder()`, `id()`, `close()` | A new entry builder; the log's id; closes the log. |
| | `toString`/`fromString`, `toBytes`/`fromBytes` | Entry ids as text and as bytes. |
| `SagaLogEntry` | `getId()`, `getExecutionId()`, `getEntryType()`, `getNodeId()`, `getSagaName()`, `getJsonData()` | One entry. `getJsonData()` holds any text. |
| `SagaLogEntryType` | `Start`, `End`, `Abort`, `CompStart`, `Comp`, `Ignore` | See [Protocols](protocols.md#the-saga-log-protocol). |
| `SagaLogEntryBuilder` | `startSaga`, `startAction`, `endAction`, `endSaga`, `abort`, `compStart`, `compDone`, `control` | Builds the entries the engine writes. |
| `SagaLogId` | `getClusterInstanceId()`, `getLogName()` | Which log. |
| `SagaLogPool` | `registerInstanceLocalIdFor`, `tryAcquire`, `tryTakeOwnership`, `releaseOwnership`, `connect`, `remove`, `delete`, `clusterWideLogIds`, `instanceLocalSagaLogOwnerships`, `shutdown` | Logs and their ownership. See [sagalog-api](modules/sagalog-api.md#pools-and-ownership). |
| `SagaLogOwner` | `SagaLogOwner(ownerId)` | Who owns a log in the pool. |
| `SagaLogOwnership` | `getOwner()`, `getLogId()`, `getAcquired()` | One ownership, for monitoring. |
| `SagaLogInitializer` | `initialize(Map<String, String>)`, `configurationOptionsAndDefaults()` | A provider: opens a pool from configuration. |
| `AbstractSagaLogPool` | | The pool logic shared by every provider; extend it to write one. |

Configuration keys of each provider: [Architecture](architecture.md#saga-log-providers-and-service-loading),
[sagalog-file](modules/sagalog-file.md#configuration), [sagalog-postgres](modules/sagalog-postgres.md#configuration).

## Sagas as JSON

`no.cantara.saga.serialization`: `SagaSerializer.toJson(saga)`, `SagaSerializer.writeJson(saga, writer)` and
`SagaDeserializer.fromJson(json)`. See [saga-serialization](modules/saga-serialization.md).

## Exceptions

| Exception | Thrown by | Means |
|---|---|---|
| `AbortSagaException` | An action | Roll the saga back. The completion future fails with it once the rollback is done. |
| any other exception from an action | An action | The saga fails without rollback; recovery runs it forward later. |
| `SagaOnCompleteException` | The completion future | `onComplete` threw after a successful saga. |
| `SagaException` | `Saga.SagaBuilder.end()`, `SagaDeserializer` | The saga definition is invalid. |
| `RejectedExecutionException` | The futures | The pool rejected a walk. |
| `SagaLogBusyException` | Opening, or using, a log | Another process holds the log, or took it after this one lost its connection. |
| `SagaLogAlreadyAquiredByOtherOwnerException` | The pool | Another owner holds the log in this pool. |
| `SagaPoolShutdownException` | The pool | The pool was shut down. |
| `PostgresSagaLogException` | The PostgreSQL log | A database failure; `getOperation()` and `getSqlState()` say which and why. |
| `UncheckedIOException` | The file log | A file failure; the message names the operation and the file. |
| `IllegalStateException` | A log, the pool | A log used after it was closed, a release without ownership, a file log that could not read its file again. |
| `TimeoutRuntimeException` | `SagaTraversalResult` | The wait for the traversal timed out. |
