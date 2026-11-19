# Known limitations

These are behaviours of the current code that a user must design around. Each is described as it is today; none is
hidden by the rest of the documentation.

## Exceptions other than AbortSagaException

Only `AbortSagaException` rolls a saga back. If an action (or a saga-log write, or an unknown adapter name) throws
anything else:

- no `Abort` entry is written and nothing is compensated;
- `onComplete` is called with a failure result, and the completion future fails with that exception;
- the execution's log entries stay as they are, without `End` on `E`, so the execution looks unfinished and recovery
  will run it again;
- walks of the same saga that wait at a join node for the failed node never finish and keep their pool threads,
  and `SagaTraversalResult.waitForCompletion…` never returns, even with a timeout. The cause is in
  [future-selector](modules/future-selector.md#behaviour-to-know): a future that fails is never selected.

Catch the exceptions you expect inside your actions, and throw `AbortSagaException` when the saga must be rolled back.

## Thread starvation

A walk that reaches a join node waits on its pool thread. If the pool has fewer threads than the branches that wait
at the same time, the walks that would complete the join stay in the queue, and the saga stops. A
`ThreadPoolExecutor` with an unbounded queue never adds threads above its core size. Size the core of the pool for the
widest saga times the number of sagas you run at once, and see the previous section: a failed saga can hold threads
for good.

## Rollback of a node that started but did not end

During rollback, a node with a `Start` but no `End` entry is meant to be run again and then compensated. That path
fails with a `NullPointerException` when it gathers the outputs of the node's dependencies, so the rollback stops
there. In a normal run this does not happen: when an action aborts, the other running actions finish first, and
every started node has ended. It happens when a log already holds a node that never ended.

## A compensating action that fails

If a compensating action throws, the rollback stops: the remaining nodes are not compensated, no `End` is written on
`E`, and the completion future fails with the compensating action's exception, not the abort.

## Recovery after a crash during rollback

Recovery continues forward only; it does not look at `Abort` or `Comp` entries. If a process stops during a rollback,
recovery runs the saga forward: it runs the aborted node's action again, and passes on the outputs of nodes that were
already compensated. Check an unfinished execution's entries for `Abort` before you recover it.

## No timeouts

There are no timeouts on actions, compensating actions, or the saga as a whole. An action that never returns holds its
thread and its saga. Put timeouts inside your actions.

## Saga-log providers

- **File log**: keeps entry data only when it starts with `{`, has no escaping for spaces, restarts entry ids at 0
  after a restart, and has no lock between processes. See [sagalog-file](modules/sagalog-file.md#behaviour-to-know).
- **PostgreSQL log**: never gives a log's connection back to the pool, and returns a closed log when a removed log is
  opened again. See [sagalog-postgres](modules/sagalog-postgres.md#behaviour-to-know).
- **Ownership in the pool**: `releaseOwnership(logId)` and `release(logId)` take no owner, so any caller can release
  any log; release only logs you hold.

## Graphs

A node without a path to `E` is accepted, but the saga can finish while that branch still runs, that branch is not
compensated on rollback, and the rollback may not finish. Link every node to the end. See [saga-api](modules/saga-api.md#behaviour-to-know).
