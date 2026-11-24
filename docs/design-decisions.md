# Design decisions

Three choices define how the library behaves under failure and load:
- actions run at least once;
- steps block on the saga's own pool;
- the saga log is synchronous.

Each section below gives the decision, why it is right for durability, delivery semantics and resilience, what it
asks of applications, and what was rejected. The decisions were taken on 2026-11-24, after the engine was reworked
in 2.2.0 (joins no longer hold a thread; see the [CHANGELOG](../CHANGELOG.md)). They are judged on the code, not on
how easy or cheap an alternative would be.

## 1. Delivery semantics: at least once

**Decision.** Every action and compensating action runs at least once. The library does not give exactly-once
execution; an application gets exactly-once *effects* by making its actions idempotent.

### How the code gives it

Each effect is bracketed by two durable saga-log entries (`SagaExecution.Execution.visitForward` and
`visitBackward`). Each write is joined before the engine goes on:

| Effect | Written before | Written after | Recovery finds only the entry before |
|---|---|---|---|
| Action | `Start` | `End`, with the output | runs the action again |
| Compensating action | `CompStart` | `Comp` | runs the compensating action again |

Recovery looks at the last entry of each node. `End` passes on the logged output and does not run the action.
`Start` runs the action again, without writing a second `Start`.

Several things leave an effect done without its entry after it:
- the process crashes;
- the `End` write fails (the database is unreachable);
- an executor's `shutdownNow` interrupts the step.

In each case the saga fails or stops, and recovery runs the step again.

The callbacks follow the same rule:
- `postAction` is called again during recovery for a node that had ended, with its logged output.
- `onComplete` is called once per `executeSaga` call, so a recovered execution can call it a second time.

### Why not at most once

At most once would mean never running a step again that has `Start` but no `End`. The engine cannot know whether
such a step's effect happened, so recovery would have to give up on it, or roll the saga back. That is worse on every
count:

1. **It breaks the handoff.** The handoff future means the saga is durable: once `Start` on `S` is written, the saga
   survives a crash of the process. Applications answer their clients on it. Under at most once, a crash would turn
   accepted work into rollbacks.
2. **The uncertainty does not go away.** It moves to the compensating actions. They would run, with a `null` output,
   for steps that may or may not have run, so they would still have to be idempotent and handle "maybe".
3. **Steps without a compensating action would be left inconsistent.** Their effect may exist while the saga is
   marked failed, and nothing would repair it.

At least once keeps forward recovery: a saga that was handed off completes. Exactly-once effects then come from the
application, at the one place that can deduplicate: the step itself.

### What it asks of applications

- **Make every action idempotent, keyed on something stable across reruns.** A rerun gets the same saga input (logged
  in `Start` on `S`) and the same outputs of the nodes it depends on (logged in their `End` entries). Derive the key
  from those, never from a value the step makes itself, such as a new random id or the current time.
- **Make every compensating action idempotent, and let it accept a `null` output** for a step that started but did
  not end.
- **Let `postAction` and `onComplete` tolerate being called again.**
- **Treat a failure other than an abort as "outcome unknown".** `AbortSagaException` means the saga was rolled back.
  Any other failure may leave work done that recovery completes later. A client retry must be idempotent, with the
  same id.
- **Consider that a rerun is late.** Recovery may run an old saga's step after newer changes to the same data. A step
  that can be reordered this way needs a version or a lock.

## 2. Execution: steps block, on the saga's own pool

**Decision.** A step runs on a thread of the executor given to `SagaExecution`, and may block there for its I/O. This
is the only model: the library has no asynchronous steps.

### Why blocking is right

- **It is plain.** One step runs on one thread from `Start` to `End`, so the order of log writes, actions and
  callbacks can be read straight from the code.
- **It pushes back on load.** The pool belongs to the application, and its threads cap how many steps run at once.
  When a downstream system slows down, the sagas slow down with it, instead of piling up more work on that system.
  With a bounded queue (the application's choice), new sagas are also rejected at once, so the application sees the
  overload where it starts sagas.
- **Durability needs a wait anyway.** A step must not start before the entries ahead of it are durable, so some thread
  waits for each commit. Blocking makes that wait explicit and bounded by the pool.
- **It adds no failure windows.** A rejected walk is always a node whose `Start` was never written: nothing has run.
- **Threads are cheap at this scale.** With tens or hundreds of sagas at once and waits of milliseconds, a pool sized
  for them costs little.

The rule that matters is: never block a thread that other work needs to make progress. That covers an event loop, a
request thread, a reactive scheduler, or a pool thread waiting for work queued behind it. The saga's own pool is the
place where blocking belongs.

### What was wrong before 2.2.0, and why async was not the fix

Applications on 2.1.4 and earlier saw sagas stall with no timeout. The cause was a join: the walk of the first branch
to be done held its pool thread while it waited for the others. With fewer threads than branches waiting at joins,
the walks that would complete the joins stayed queued behind it, and the sagas, aborted ones included, never
completed.

Since 2.2.0 no walk waits for another. Each incoming node signals the join when it is done, and the last signal
starts it (`SagaTraversal`). That fixed the cause for every step. Blocking was never the problem.

### Asynchronous steps: built, tried and removed

An asynchronous adapter (`AsyncSagaAdapter`, returning a `CompletionStage`) was built during the 2.2.0 work and
removed before release. It let a step hold no pool thread while its stage ran: the walk ended after `Start`, and the
stage's completion submitted the rest of the step to the pool.

- **It gained nothing for the steps that needed it.** The one user, a transaction-log step, called a client whose
  asynchronous publish was `CompletableFuture.runAsync(blockingPublish, unboundedPool)`. The same thread waited, on
  another pool, outside the saga pool's thread cap. That is true of any blocking call wrapped in a future.
- **It took away back-pressure.** The waits moved off the bounded pool.
- **It added a way for a step to run twice.** A continuation rejected by a full pool queue left a step done without
  its `End`.
- **It gave the engine a second execution mode.** In that mode one node's visit spans threads, and every later change
  to the traversal would have had to account for it.

It would only pay off for steps whose I/O is truly non-blocking and waits long, in a process with very many sagas in
flight. No user has that case. The transaction-log step went back to the synchronous adapter.

## 3. The saga log is synchronous; there is no asynchronous saga log

**Decision.** The saga-log providers do their work on the calling thread, and return futures that are already
complete. The engine waits for each write before it goes on. A non-blocking saga log, meaning an engine that
continues on the write's future plus a provider on a non-blocking PostgreSQL driver, was designed and rejected.

### Why not

- **It would not raise throughput.** One log runs its operations one at a time on its one connection: the PostgreSQL
  log's `LogSession.run` is `synchronized`, and the file log serialises on its queue file. A log's throughput is set
  by the database's round trips, not by threads. More logs give more throughput, with either model.
- **It would remove back-pressure when it matters most.** In a database outage, sagas cannot make progress either
  way. A blocking log holds the pool's threads, which caps the sagas in progress (and with a bounded queue, rejects
  new ones). An asynchronous log would free the threads and keep admitting sagas, piling up pending writes with no
  bound unless one were built by hand.
- **It would put the most critical protocol in two implementations.** The PostgreSQL log's durability rests on
  several pieces of `LogSession`: the advisory lock, the ownership token in `Locks`, the reconnect that takes the lock
  again and checks the token, and the retry of a commit whose reply was lost. A second, asynchronous copy of that
  protocol would be a second place for it to go wrong, a durability risk in itself.
- **Durability gains nothing.** The next step must still wait for the commit. An asynchronous write only changes
  which thread is free meanwhile.

### What covers the outage case instead

A reconnect can hold a log's operations for up to the connection timeout each (see
[sagalog-postgres](modules/sagalog-postgres.md)). Bound it with configuration:
- `connection-pool.connection-timeout`;
- `postgres.driver.socket-timeout`;
- `postgres.statement-timeout`;
- `postgres.lock-timeout`.

The last three are `0` (no limit) by default. Size the saga pool for the sagas that run at once, and let it push back.

## When to revisit

- **Section 2:** if an application needs thousands of sagas in flight in one process, each waiting on truly
  non-blocking I/O, thread count would become the limit. Only such a measured case would justify asynchronous steps
  again, and they would have to keep the guarantees of this page.
- **Section 3:** if a saga-log backend appears whose write path is itself non-blocking end to end, and a measured
  load shows threads, not round trips, to be the limit. Neither holds today.
- **Section 1:** does not change. Exactly-once effects belong in the steps.
