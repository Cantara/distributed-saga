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
| `SagaHandoffControl` | Returned by `executeSaga`: the handoff future, the completion future, the execution's inputs, and `getState()`. |
| `SagaExecutionState` | A snapshot of an execution, from `SagaHandoffControl.getState()`: its phase, the nodes running, and the nodes waiting for others. |
| `SagaHandoffResult` | The result: `getExecutionId()`, `isSuccess()`, `isFailure()`, `getFailureCause()`. |
| `SagaOnCompleteException` | The completion future's failure when the saga succeeded but `onComplete` threw. |
| `SagaExecutionTraversalContext` | Given to the `preAction` and `postAction` callbacks. |
| `SagaTraversalResult` | The forward traversal's walks; `waitForCompletionAndCheckThatAllNodesWereWalkedAndVisited(...)`, for tests. After a rollback it reports the nodes the stopped forward walks never reached as not walked. It throws `TimeoutRuntimeException` when the walks have not ended within its timeout. |

Package `no.cantara.saga.execution.adapter`:

| Type | Purpose |
|---|---|
| `SagaAdapter<OUTPUT>` | The adapter interface: `name()`, `executeAction`, `executeCompensatingAction`, `hasCompensatingAction()`, `serializer()`. |
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
| `executionId` | The id of this execution, stored on every log entry. Unique; reused only for recovery. Not null. |
| `sagaInput` | Passed to every action. Serialized with the start adapter (`toString()`) into the first log entry. |
| `recovery` | `false` for a new execution. `true` to continue an execution from the entries already in the log. |
| `onComplete` | Called once when the execution is finished; may be `null`. |
| `preAction`, `postAction` | Called before and after each node, on the walk's thread. Not null. The ancestors deque in the context belongs to that node's walk: read it, do not change it. |

An instance can run any number of executions, one after another or at the same time, as long as each has its own saga
log, or the log tolerates concurrent writers.

### The two futures

- **Handoff future**: completes when the `Start` entry on `S` has been written (in recovery, at once). After it, the
  saga survives a crash of the process.
- **Completion future**: completes after `End` on `E` has been written and `onComplete` has returned.

Both are the futures the engine completes: cancelling or completing them does not stop the saga, and hides its outcome
from other callers waiting on them. Wait on them, or on a copy (`future.copy()`).

The engine completes them on a walk's thread: the handoff future right after `Start` is written, the completion future
after `onComplete`. A dependent attached with a non-async method (`thenRun`, `thenApply`, `whenComplete`) runs there,
inside the saga's flow: while it runs, that walk does not go on. So attach work that blocks, or that waits for the saga,
with the `*Async` methods and an executor of your own (`thenRunAsync(task, executor)`). The engine does not move them
to another thread itself: the handoff is the earliest signal that the saga is durable, and a task on the saga's pool
would queue it behind the sagas' walks.

| Outcome | `onComplete` receives | Completion future |
|---|---|---|
| Success | a success result | completes with the result |
| Success, but `onComplete` throws | a success result | fails with `SagaOnCompleteException` (cause: the callback's exception) |
| `AbortSagaException`, rolled back | a failure result with the abort | fails with the `AbortSagaException` |
| Rolled back, and `onComplete` throws | a failure result | fails with the `AbortSagaException`; the callback's exception is suppressed on it |
| Another exception from an action or the log | a failure result | fails with that exception, once the saga's running actions have ended; nothing is rolled back |
| A failure in the rollback (a compensating action, the read of the log's entries) | a failure result | fails with that exception; the abort is suppressed on it |
| The executor rejects a walk, or a walk dropped by `shutdownNow()` is cancelled | a failure result | fails with `RejectedExecutionException` or `CancellationException` |

`onComplete` runs on the pool thread that finished the saga. It runs on the thread that called `executeSaga` only when
the saga fails before any walk ran (its first walk was rejected), or on the thread that cancels a dropped walk.

### Where an execution is

A join holds no thread while it waits, so a saga that does not complete may not show in a thread dump.
`control.getState()` returns a snapshot of the execution, from any thread, without waiting:

| Field | Meaning |
|---|---|
| `getExecutionId()` | The execution's id |
| `getPhase()` | `FORWARD`, `ROLLBACK` (compensating after an abort), or `COMPLETED` (the completion future is done) |
| `getRunningNodeIds()` | The nodes being visited now: an action or a compensating action, a log write for the node, its `preAction` or `postAction` |
| `getWaitingNodeIds()` | Each node that some, but not all, of the nodes it depends on have signalled, with how many it still waits for |
| `getVisitedNodeIds()` | The nodes visited in the current phase (backward, in a rollback) |
| `getWalks()` | The walks that may still run: running, or queued on the pool |
| `getFailure()` | The first failure, once recorded |

A saga stuck in a step shows that step in `running`. A saga whose step is waiting for a pool thread shows its walk in
`walks` but nothing running. The values are read one after another, not atomically, so they can be a moment apart; just
after completion, the last node can still show as running.

### The pool

The pool belongs to the application; the engine starts no threads. Every walk holds a pool thread while it runs an
action or waits for a saga-log write or read, none of which has a timeout in the engine. A join holds no thread: the
walk of the last node it depends on starts it, so a small pool makes sagas slower, not stuck. The application sizes
the pool and limits how many sagas run at once. The rejection policy must not run tasks on the
submitting thread: under `CallerRunsPolicy` a walk the pool would run on the submitting thread is rejected, and the
saga fails with `RejectedExecutionException`. A rejected walk fails the saga through its futures; `executeSaga` does
not throw it. An `Error` thrown by the executor's `submit` (an `OutOfMemoryError`, say) fails the saga too, and runs
`onComplete`; when it comes from the first walk, `executeSaga` then rethrows it. After `shutdownNow()`, cancel the tasks it returns, so that sagas whose walks were all queued complete.
See [Known limitations](../limitations.md#the-executor-rejection-shutdown-and-caller-runs).

### Recovery mode

With `recovery = true`, the execution reads the log's entries for `executionId` first:

- `S`: nothing is written; the handoff future completes at once.
- The **last** entry of each node decides; an execution can hold several rounds of a node.
- Last entry `End`: the action is not run; the output of that `End` is deserialized and passed on.
- Last entry `Start`: the action runs again (no second `Start` is written).
- Last entry `Abort`, `CompStart` or `Comp`, or no entries: a `Start` is written and the action runs. A step that was
  (being) compensated runs again, because its effect may be undone.
- An `End` on `E` is not looked at: an execution that was rolled back (or finished) is run forward again when asked,
  and its new entries are added after the old ones. Applications use this to retry an aborted saga; whether to
  recover an execution is the caller's choice.

See [Using the library](../using.md#7-recover-unfinished-sagas) for how to find what to recover.

## Adapters

`Adapter<O>` covers most cases:

```java
new Adapter<>(JsonNode.class, "WriteToDb",
        (sagaInput, dependeesOutput) -> db.write(sagaInput),          // the action
        (sagaInput, output) -> db.delete(output));                    // the compensating action
```

Its constructors: name only (action returns null, no compensation), with an action (no compensation), and with an
action and a compensating action (`null` means none). `hasCompensatingAction()` is true when a compensating action
was given, or when a subclass overrides `executeCompensatingAction`, so an override is never skipped. The output type needs a public `String` constructor for `deserialize`, unless it is `String`.

Implement `SagaAdapter<O>` directly for another serialization:

```java
class WriteToDb implements SagaAdapter<JsonNode> {
    public String name() { return "WriteToDb"; }
    public JsonNode executeAction(SagaNode node, Object input, Map<SagaNode, Object> dependeesOutput) { ... }
    public void executeCompensatingAction(SagaNode node, Object input, JsonNode output) { ... }
    public boolean hasCompensatingAction() { return true; }
    public ActionOutputSerializer<JsonNode> serializer() { return JSON_SERIALIZER; }
}
```

Rules for adapters:

- One instance serves every node that names it, concurrently: no mutable state.
- The adapter name is stored in serialized sagas; renaming breaks them.
- Actions and compensating actions can run more than once; make them idempotent.
- A `null` output is written as no data, and read back as `null`.
- Throw `AbortSagaException` from an action to roll back. The aborting node is not compensated: undo its own partial
  effect before you throw.
- Throw a new exception each time: the engine attaches other failures of the saga to the saga's failure as
  suppressed exceptions, which a shared instance would collect across sagas.
- A compensating action can be called with a `null` output, for a node that started but did not end: it must handle
  an action that may never have run.
- `AdapterLoader.load` fails for an unknown adapter name only when the node is reached. Register every adapter
  before you start sagas.
- `Adapter`'s serializer fails with `IllegalStateException` ("cannot deserialize <class> from its String
  constructor") when the output type has no public `String` constructor, or the constructor throws (then that is the
  cause).

## Behaviour to know

See [Known limitations](../limitations.md) for exceptions other than abort, thread starvation, a compensating action
that fails, recovery after a crash during rollback, timeouts, how many sagas run at once, and the executor's
rejection policy and shutdown.
