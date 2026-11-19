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
- walks of the same saga that wait at a join node for the failed node end with that failure and free their pool
  threads (before 2.0.1 they stayed blocked for ever, one pool thread per failed saga).

Catch the exceptions you expect inside your actions, and throw `AbortSagaException` when the saga must be rolled back.

## Thread starvation

A walk that reaches a join node waits on its pool thread. If the pool has fewer threads than the branches that wait
at the same time, the walks that would complete the join stay in the queue, and the saga stops. A
`ThreadPoolExecutor` with an unbounded queue never adds threads above its core size. Size the core of the pool for the
widest saga times the number of sagas you run at once.

## A compensating action that fails

If a compensating action throws, the rollback stops: the remaining nodes are not compensated, no `End` is written on
`E`, and the completion future fails with the compensating action's exception, not the abort. The node's last entry is
`CompStart`, so forward recovery runs it again, together with every other node whose effect may be undone; the saga
then ends forward, not rolled back. There is no mode that resumes the rollback instead.

## Recovery after a crash during rollback

Recovery continues forward only. After a crash during a rollback it runs again every node whose last entry is
`CompStart` or `Comp`, and the aborted node, so the saga ends with every effect in place; it does not finish the
rollback.

## No timeouts

There are no timeouts on actions, compensating actions, or the saga as a whole. An action that never returns holds its
thread and its saga. Put timeouts inside your actions.

## Saga-log providers

- **File log**: keeps entry data only when it starts with `{`, and has no escaping for spaces. See [sagalog-file](modules/sagalog-file.md#behaviour-to-know).
- **PostgreSQL log**: each open log holds a connection, so the connection pool must be larger than the number of logs
  open at once; `delete(logId)` does not drop the log's table. See [sagalog-postgres](modules/sagalog-postgres.md#connections).
- **Ownership in the pool**: `releaseOwnership(logId)` and `release(logId)` take no owner, so any caller can release
  any log; release only logs you hold.

## Graphs

A node without a path to `E` is accepted, but the saga can finish while that branch still runs, that branch is not
compensated on rollback, and the rollback may not finish. Link every node to the end. See [saga-api](modules/saga-api.md#behaviour-to-know).
