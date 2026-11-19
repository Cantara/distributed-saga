# Known limitations

These are behaviours of the current code that a user must design around. Each is described as it is today; none is
hidden by the rest of the documentation.

## Exceptions other than AbortSagaException

Only `AbortSagaException` rolls a saga back. If an action (or a saga-log write, or an unknown adapter name) throws
anything else:

- no `Abort` entry is written and nothing is compensated;
- the failure stops the saga: walks waiting at a join node return and free their pool threads, and no walk starts a
  new node (up to 2.1.0, only an abort did this, and a walk waiting at a join for a node behind the failed one
  stayed blocked for ever);
- an action that is already running on another branch runs to its end and writes its `End` entry; the saga reports
  completion only after that, once no walk of it can run an action or write to the log any more, so `onComplete` sees
  every entry the execution writes (up to 2.1.0, the completion future failed at once, while other branches still
  ran and wrote);
- `onComplete` is called with a failure result, and the completion future fails with that exception (the first one,
  if several steps fail; a later, different one is suppressed on it);
- the execution's log entries stay as they are, without `End` on `E`, so the execution looks unfinished and recovery
  will run it again;
- if another node threw `AbortSagaException` before the saga completed, no rollback runs either; the abort is attached
  to the saga's failure as a suppressed exception (`getSuppressed()`).

Catch the exceptions you expect inside your actions, and throw `AbortSagaException` when the saga must be rolled back.
A failed saga completes only when its running actions have returned: an action that never returns holds the saga's
completion too (see [No timeouts](#no-timeouts)).

## Thread starvation

A walk that reaches a join node waits on its pool thread. If the pool has fewer threads than the branches that wait
at the same time, the walks that would complete the join stay in the queue, and the saga stops. A
`ThreadPoolExecutor` with an unbounded queue never adds threads above its core size. Size the core of the pool for the
widest saga times the number of sagas you run at once.

## A compensating action that fails

If a compensating action throws (or the rollback's read of the log's entries fails), the rollback stops: the
remaining nodes are not compensated, no `End` is written on `E`, and the completion future fails with that exception,
not the abort (the abort is suppressed on it), once the other compensations that were running have ended. The node's
last entry is `CompStart`, so forward recovery runs it again, together with every other node whose effect may be
undone; the saga then ends forward, not rolled back. There is no mode that resumes the rollback instead.

## Recovery after a crash during rollback

Recovery continues forward only. After a crash during a rollback it runs again every node whose last entry is
`CompStart` or `Comp`, and the aborted node, so the saga ends with every effect in place; it does not finish the
rollback.

## No timeouts

There are no timeouts on actions, compensating actions, saga-log writes and reads, or the saga as a whole. An action
that never returns holds its thread and its saga; a saga-log provider whose write or read never returns (a network
partition without a socket timeout) does the same. Put timeouts inside your actions, and configure the provider's own
timeouts.

The PostgreSQL saga log's database calls are bounded only by the timeouts the application configures (socket timeout,
`statement_timeout`, `lock_timeout`, the pool's connection timeout); by default, as in the JDBC driver, there are
none. See [sagalog-postgres](modules/sagalog-postgres.md#timeouts-and-tls).

## Pool threads, and how many sagas run at once

Every walk of a saga holds a pool thread while it runs an action, waits at a join node, or waits for a saga-log write
or read: two log round trips for each node, one each for `S` and `E`, and two for each compensated node plus one read
in a rollback. `executeSaga` only submits the first walk and returns; it does not limit how many sagas run. Limiting
concurrent executions is the application's job (a semaphore around `executeSaga` released on completion, for
example), as is sizing the pool for them (see [Thread starvation](#thread-starvation)). With an unbounded queue, walks
that wait for a thread queue up without limit and the handoff comes later and later; with a bounded queue or no queue
and `AbortPolicy`, a saga the pool cannot take fails at once with `RejectedExecutionException`.

## The executor: rejection, shutdown, and caller-runs

- A walk the executor rejects (it is shut down, or full with `AbortPolicy`) fails the saga with the
  `RejectedExecutionException`: `onComplete` is called and the completion and handoff futures fail. `executeSaga`
  does not throw it.
- `shutdownNow()` interrupts the walks that run, which fails their sagas, and returns the walks still queued without
  running them. A saga that has a running walk completes. A saga all of whose walks were queued completes only when the
  tasks `shutdownNow()` returned are cancelled: cancel them (`((Future<?>) task).cancel(false)`), or wait on the
  saga's futures with a timeout.
- A rejection policy that runs the task on the submitting thread (`ThreadPoolExecutor.CallerRunsPolicy`) cannot run
  sagas: a walk run on the thread that submits it waits for walks that thread would only start afterwards. The engine
  rejects such a walk instead of running it, and the saga fails with `RejectedExecutionException`. Use a queue, or
  `AbortPolicy`.
- A rejection policy that discards tasks silently (`DiscardPolicy`, `DiscardOldestPolicy`) drops a walk without
  telling anyone, and its saga never completes. Do not use one. (A walk discarded because the executor is shut down
  is detected, and fails the saga with `RejectedExecutionException`.)

## Saga-log providers

- **File log**: one process per log file (it is locked), and logs written before 2.1.0 cannot be read. The file
  shrinks only when it becomes empty, and every read and recovery reads the whole file. See
  [sagalog-file](modules/sagalog-file.md#behaviour-to-know).
- **PostgreSQL log**: each open log holds a connection, so the connection pool must be larger than the number of logs
  open at once. See [sagalog-postgres](modules/sagalog-postgres.md#connections).
- **Ownership in the pool**: the deprecated `releaseOwnership(logId)` and `release(logId)` take no owner, so any caller
  can release any log. Use `releaseOwnership(owner, logId)`, which refuses to release another owner's log.

## Graphs

A node without a path to `E` is accepted, but the saga can finish while that branch still runs, that branch is not
compensated on rollback, and the rollback may not finish. Link every node to the end. See [saga-api](modules/saga-api.md#behaviour-to-know).
