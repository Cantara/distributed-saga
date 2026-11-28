---
title: "Protocols"
description: "The rules the engine and the saga logs follow: the entries each step writes, how recovery and rollback read them, the two futures, the traversal's signals, and how a log is owned within a process and across processes."
group: Reference
order: 21
---

# Protocols

The library has no network protocol: a saga runs inside one process, and processes share nothing but the saga logs.
What holds it together is a set of rules, each kept by one part of the code. This page states them. The order in
which they play out is drawn in [Sequence flows](sequence-flows.md).

| Protocol | Kept by | Between |
|---|---|---|
| [The saga-log protocol](#the-saga-log-protocol) | `SagaExecution` | The engine and the log: which entries a step writes, and what they mean on recovery and rollback |
| [The handoff protocol](#the-handoff-protocol) | `SagaExecution`, `SagaWalks` | The engine and its caller: when each future completes |
| [The traversal protocol](#the-traversal-protocol) | `SagaTraversal`, `SagaWalks` | The walks of one execution, on the pool's threads |
| [The ownership protocol](#the-ownership-protocol-within-a-process) | `AbstractSagaLogPool` | The callers of one pool, in one process |
| [Ownership across processes](#ownership-across-processes) | The PostgreSQL and file providers | Processes that share a database or a folder |
| [Storage formats](#storage-formats) | The providers | A log and what it reads back after a restart |

## The saga-log protocol

Every effect is bracketed by two durable entries, one written before it and one after. Each write is joined (the
engine waits for it) before the engine goes on.

| Step | Entry before | Entry after |
|---|---|---|
| The saga starts | | `Start` on `S`, with the saga name and the serialized input |
| An action | `Start` on the node | `End` on the node, with the serialized output |
| An action throws `AbortSagaException` | `Start` on the node | `Abort` on the node |
| A compensating action | `CompStart` on the node | `Comp` on the node |
| The saga ends, after success or after rollback | | `End` on `E` |

An execution is **finished** when it has `End` on `E`. `Ignore` entries (`SagaLogEntryBuilder.control()`) are not
about any node and are skipped when a node's state is read.

### Reading a node's state

The engine never trusts memory for a node's state on recovery or rollback: it reads the node's entries from the log
and takes the **last** one (an execution can hold several rounds of a node: run, compensated, run again).

Going forward with `recovery = true` (`SagaExecution.Execution.visitForward`):

| Last entry of the node | What the engine does |
|---|---|
| `End` | Does not run the action. Passes on the output of the last `End`. |
| `Start` | Runs the action again, without writing a second `Start`: it may or may not have run. |
| none, `Abort`, `CompStart`, `Comp` | Writes `Start` and runs the action: a new round. |

`Start` on `S` is not written again on recovery; the handoff future completes all the same.

Going backward after an abort (`visitBackward`), from the log read once when the rollback starts:

| Last entry of the node | What the engine does |
|---|---|
| none | Nothing: the node never started. |
| `Abort` | Nothing: it is the node that aborted. |
| `Comp` | Nothing: it is already compensated. |
| any, and the adapter has no compensating action | Nothing, and nothing is logged: its effect stays. |
| `End` | Writes `CompStart`, runs the compensating action with the output of the last `End`, writes `Comp`. |
| `Start` | Writes `CompStart`, runs the compensating action with `null`, writes `Comp`. |
| `CompStart` | Runs the compensating action again, with the output of the last `End` if there is one, and writes `Comp`. |

When the backward walk reaches `S`, it writes `End` on `E`: a rolled-back saga is finished too.

### What the protocol guarantees

- A crash at any point leaves the log saying which effects may have happened: an effect is skipped on recovery only
  if its node's last entry is `End`.
- So every action and compensating action runs **at least once**, and may run more than once. Applications make them
  idempotent. Why this is the right trade: [Design decisions](design-decisions.md#1-delivery-semantics-at-least-once).
- `CompStart` is written before a compensating action runs, so a crash during it leaves the node marked as being
  undone, never as done.

### Recovery is the application's loop

The library does not scan logs. After a restart the application reads a log (`readIncompleteSagas()`), groups the
entries by execution id, keeps the executions without `End` on `E`, finds each one's `Saga` by the saga name on
`Start` of `S`, turns that entry's data back into the input, and calls `executeSaga(executionId, input, true, ...)`.
Recovery always goes forward, also after a crash during a rollback. See
[Using the library](using.md#7-recover-unfinished-sagas).

## The handoff protocol

`executeSaga` returns a `SagaHandoffControl` at once. Its two futures are the contract with the caller:

| Future | Completes normally | Completes exceptionally |
|---|---|---|
| Handoff | When `Start` on `S` is in the log: the saga survives a crash from here. | At once, with the first failure of the execution. |
| Completion | After `End` on `E` is written and `onComplete` has returned. | After a rollback, with the `AbortSagaException`. Or with the first failure, once no walk of the execution is left. Or with `SagaOnCompleteException` when `onComplete` threw after success. |

- `onComplete` runs exactly once per `executeSaga` call, before the completion future completes, on the thread that
  ends the execution.
- A failure of `onComplete` after a failed saga is added to the saga's failure as suppressed.
- A failed saga completes only when no walk can still run an action or write to the log, so nothing happens to the
  log after the caller is told.
- When another node fails with a different exception at the time of an abort, no rollback runs; the saga fails with
  that exception, and the abort is attached to it as suppressed.

## The traversal protocol

One execution is one forward traversal of the graph and, after an abort, one backward traversal. A traversal runs as
*walks*: tasks on the application's pool.

1. **Counting.** Each node counts the nodes it depends on (forward: incoming; backward: outgoing). A walk that has
   visited a node *signals* each node that depends on it.
2. **Starting.** The signal that brings a node's count to zero starts it. If one visit starts several nodes, the walk
   continues into the last of them and submits a new walk for each of the others. So a join is started by the walk of
   the last node it depends on; no walk waits for another, and no thread is held while a join waits.
3. **Outputs.** A node gets the outputs of all the nodes it depends on. They are all done when it starts, so reading
   them never blocks.
4. **Stopping.** The first failure in a walk stops the traversal: no walk starts a new node, the nodes that depend on
   the failed one are never signalled, and walks that were submitted but have not started are ended without running.
5. **Accounting.** Every walk is counted from just before it is submitted until it has ended or is known never to run
   (rejected, cancelled, or ended unstarted). When the count reaches zero:
   - after a failure, the futures fail with the first failure;
   - after an abort, the rollback starts, on the thread that ended the last forward walk, and its first backward walk
     runs on that thread too.
6. **The pool's duties.** A walk the pool rejects fails the saga. A walk the pool would run on the submitting thread
   (caller-runs) is rejected. A walk cancelled after `shutdownNow()` fails the saga.

## The ownership protocol within a process

A saga log has one writer at a time. `AbstractSagaLogPool` keeps that rule for the callers of one pool.

- **Ownership** is a permit per log (a `Semaphore` of one) plus a record of who holds it (`SagaLogOwnership`). Taking
  a log takes the permit, connects the log if it is not open, and records the owner. While one owner holds a log,
  `tryTakeOwnership` by another returns `null`.
- **Availability.** A registered instance-local log is in the `tryAcquire` queue **exactly when nobody owns it, and at
  most once**. Every path keeps this: registering, releasing, taking by `tryAcquire`, taking by `tryTakeOwnership`, and
  a take whose connect fails (the log goes back to the end of the queue).
- **Fairness.** A released log goes to the back of the queue, so `tryAcquire` hands logs out round-robin, and a log
  that another process holds does not block the free ones behind it.
- **Releasing** checks the owner: `releaseOwnership(owner, logId)` frees the log only for the owner that holds it, and
  throws `SagaLogAlreadyAquiredByOtherOwnerException` for any other.
- **Shutdown** marks the pool shut down, then closes and removes every log; later takes fail with
  `SagaPoolShutdownException`.

## Ownership across processes

A provider whose storage can be shared locks a log when it opens it, so that one process at a time writes it.

### PostgreSQL: advisory lock and ownership token

The `Locks` table in the schema gives each log a lock key and an ownership token (`LockRegistry`).

1. **Open.** On a connection that the log keeps for as long as it is open, the provider finds or assigns the log's
   `lock_key`, takes `pg_try_advisory_lock(lock_key)`, and writes a new random `owner_token` into the log's `Locks`
   row. If the lock is held elsewhere, the open fails with `SagaLogBusyException`.
2. **Operate.** Each operation runs in a transaction on that connection, one at a time per log.
3. **Lost connection.** An advisory lock belongs to its session, so a lost connection frees the lock at once. The next
   operation evicts the connection, gets a new one, and takes the lock again, waiting up to 10 s. It then reads the
   token:
   - still its own: nobody held the log in between, and the operation runs again on the new session;
   - changed, or the row is gone: another process held the log. The operation fails with `SagaLogBusyException`, and
     so does every later operation on this log object. The application removes the log and connects it again, which
     reads the log afresh.
4. **Lost reply.** A `write` whose commit succeeded but whose reply was lost is retried with the same entry id;
   finding that entry stored, identical, counts as success.
5. **Close.** `pg_advisory_unlock_all()`, commit, and the connection goes back to the pool; if that fails, the
   connection is evicted, which ends the session and its locks.

Details, timeouts and their configuration: [sagalog-postgres](modules/sagalog-postgres.md#ownership-across-processes).

### File: an operating-system lock

Opening a file log takes an exclusive operating-system lock on `<log file>.lock` until `close()`. A second open, in
any process, fails with `SagaLogBusyException`. A process-wide registry refuses a second open within the process
before it touches the lock file, because closing any handle of a locked file releases the process's lock. If the lock
is lost all the same, the log refuses every later operation with `SagaLogBusyException`. See
[sagalog-file](modules/sagalog-file.md#behaviour-to-know).

### Memory

The memory log lives in one process; the pool's ownership is all there is.

## Storage formats

What a log stores, and reads back after a restart.

| | PostgreSQL | File | Memory |
|---|---|---|---|
| Where | One table per log, `SAGALOG_<cluster.name>_<instance-id>_<log-name>`, in the schema `<cluster.owner>` | One file per log, `<instance-id>.--.<log-name>.sagalog` | The heap |
| Entry id | UUIDv7, generated in Java, strictly increasing per log | A `long`, increasing for the life of the file | A counter |
| Entry type | `smallint`: `Start` 1, `End` 2, `Abort` 3, `Comp` 4, `Ignore` 5, `CompStart` 6 | The type's name, as a string | The enum |
| Record | Columns `entry_id`, `txid` (execution id), `entry_type`, `node_id`, `saga_name`, `data` | Format byte `2`, the id, then execution id, type, node id, saga name and data, each a length-prefixed UTF-8 string | The entry object |
| Read order | `ORDER BY entry_id`: write order | Queue order: write order | Write order |
| Survives a restart | Yes | Yes | No |

Every provider stores the entry data exactly as written; it is any text, not only JSON. Saga definitions have a JSON
format of their own, described in [saga-serialization](modules/saga-serialization.md#format).
