---
title: "Sequence flows"
description: "Step by step, in the order the code runs them: a saga that succeeds, a fork and a join, an abort and its rollback, a failure, recovery after a crash, taking a saga log, a lost database connection, and recovering another instance's logs."
group: Reference
order: 22
---

# Sequence flows

Each diagram follows the code, in the order it runs. The rules behind them are in [Protocols](protocols.md).
`Walk` is a task on the application's thread pool; `Log` is the `SagaLog` the execution was given.

## A saga that succeeds

The saga `S -> a -> E`, started with `onComplete` and waited for.

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant X as SagaExecution
    participant W as Walk (pool thread)
    participant A as Adapter of a
    participant L as Log
    C->>X: executeSaga(id, input, false, onComplete)
    X->>W: submit the walk at S
    X-->>C: SagaHandoffControl
    W->>L: write Start(S, saga name, input), and wait
    W-->>C: handoff future completes
    W->>L: write Start(a), and wait
    W->>A: executeAction(a, input, outputs of S)
    A-->>W: output
    W->>L: write End(a, output), and wait
    W->>L: write End(E), and wait
    W->>C: onComplete(success)
    W-->>C: completion future completes
```

- `executeSaga` returns as soon as the first walk is submitted; everything after runs on the pool.
- The handoff future completes once `Start` on `S` is in the log: from there the saga survives a crash.
- The completion future completes only after `onComplete` has returned. The usual `onComplete` truncates the log
  once the saga is finished (it succeeded, or was rolled back); after any other failure it keeps the entries for
  recovery.
- `preAction` and `postAction`, when given, run before and after each node's visit.

## A fork and a join

The saga `S -> {a, b} -> j -> E`. No walk waits for another; the last node to finish starts the join.

```mermaid
sequenceDiagram
    autonumber
    participant W1 as Walk 1
    participant P as Pool
    participant W2 as Walk 2
    participant L as Log
    W1->>L: Start(S)
    Note over W1: S done: signal a and b,<br/>both now have every signal
    W1->>P: submit a new walk for a
    P->>W2: run
    par branch b on walk 1
        W1->>L: Start(b), action, End(b)
        Note over W1: signal j: 1 of 2 left
        Note over W1: nothing started: walk 1 ends
    and branch a on walk 2
        W2->>L: Start(a), action, End(a)
        Note over W2: signal j: 0 left, so j starts here
    end
    W2->>L: Start(j)
    Note over W2: j gets the outputs of a and b
    W2->>L: End(j)
    W2->>L: End(E)
```

- The walk that visits a node continues into the last node it started, and submits a new walk for each other one.
- Which branch finishes last is a matter of timing. Whichever it is, its walk runs the join; the other walk ends.
- A join holds no thread while it waits, so a pool smaller than the branches makes the saga slower, not stuck.

## An abort and its rollback

The saga `S -> a -> b -> E`, where `b` throws `AbortSagaException`.

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant W as Walk
    participant B as Adapter of b
    participant A as Adapter of a
    participant L as Log
    W->>L: Start(S), Start(a), End(a, out-a)
    W->>L: Start(b)
    W->>B: executeAction
    B--xW: AbortSagaException
    Note over W: first abort: stop the forward traversal,<br/>register the rollback for when no walk is left
    W->>L: write Abort(b)
    Note over W: the walk ends: no walk left,<br/>so the rollback starts on this thread
    W->>L: read the execution's entries, by node
    Note over W: backward from E: b's last entry is Abort, skip it
    W->>L: write CompStart(a)
    W->>A: executeCompensatingAction(a, input, out-a)
    W->>L: write Comp(a)
    Note over W: reached S
    W->>L: write End(E)
    W->>C: onComplete(failure: AbortSagaException)
    W-->>C: completion future fails with the AbortSagaException
```

- The rollback starts only when every forward walk has ended, so it reads all of their entries.
- A node is compensated with the output of its last `End`, or with `null` if it started but did not end.
- A node whose adapter has no compensating action is skipped, and nothing is logged for it.

## A failure that is not an abort

The same saga, where `b` throws any other exception (a database error, say).

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller
    participant W as Walk
    participant L as Log
    W->>L: Start(S), Start(a), End(a)
    W->>L: Start(b)
    Note over W: the action throws: the walk fails,<br/>the traversal stops, the failure is recorded
    Note over W: the walk ends: no walk left
    W->>C: onComplete(failure)
    W-->>C: completion future fails with that exception
    Note over L: no End(E): the execution is unfinished,<br/>and recovery will run b again
```

No rollback runs. The log keeps the execution as unfinished, so recovery continues it forward, and `b`, whose last
entry is `Start`, runs again.

## Recovery after a crash

The process stopped after `End(a)` and `Start(b)`. After the restart, the application recovers the execution.

```mermaid
sequenceDiagram
    autonumber
    participant R as Application (recovery)
    participant X as SagaExecution
    participant W as Walk
    participant L as Log
    R->>L: readIncompleteSagas()
    L-->>R: Start(S), Start(a), End(a, out-a), Start(b)
    Note over R: no End(E): unfinished. Find the saga by its name,<br/>turn Start(S)'s data back into the input
    R->>X: executeSaga(id, input, true, onComplete)
    X->>L: read the execution's entries, by node
    X->>W: submit the walk at S
    Note over W: S: Start(S) is not written again,<br/>the handoff future completes
    Note over W: a: last entry End, so it is not run,<br/>and out-a is passed on
    Note over W: b: last entry Start, so it runs again,<br/>without a second Start
    W->>L: write End(b, out-b)
    W->>L: write End(E)
    W->>R: onComplete(success)
```

The log now reads `Start(S) Start(a) End(a) Start(b) End(b) End(E)`. Because `b` may have run before the crash too,
its action must be idempotent.

## Taking and releasing a saga log

One saga's use of a log from the pool, with the PostgreSQL provider opening the log the first time it is taken.

```mermaid
sequenceDiagram
    autonumber
    participant App as Application
    participant P as SagaLogPool
    participant S as LogSession
    participant DB as PostgreSQL
    App->>P: tryAcquire(owner, 10, SECONDS)
    Note over P: take the next log id from the available queue,<br/>then that log's permit
    P->>S: open the log (first time only)
    S->>DB: find or assign lock_key in Locks, commit
    S->>DB: pg_try_advisory_lock(lock_key)
    DB-->>S: true
    S->>DB: write a new owner_token in Locks
    S->>DB: create the log's table and index if missing, commit
    P-->>App: SagaLog, owned by owner
    Note over App: run the saga on the log, and onComplete<br/>truncates it once the saga is finished
    App->>P: releaseOwnership(owner, logId)
    Note over P: drop the ownership, release the permit,<br/>put the id at the back of the available queue
```

- The log stays open, with its connection and its advisory lock, after it is released. The next `tryAcquire` that
  gets it does not open it again.
- If another process holds the advisory lock, the open fails with `SagaLogBusyException`; the id goes to the back of
  the queue, so the next `tryAcquire` hands out another log.
- With the file provider, the open takes the operating-system lock on the log's `.lock` file and reads the file
  instead.

## A PostgreSQL log loses its connection

The database session of an open log ends (a failover, a network reset). Its advisory lock is free from that moment.

```mermaid
sequenceDiagram
    autonumber
    participant W as Walk
    participant S as LogSession
    participant DB as PostgreSQL
    W->>S: write(entry)
    S->>DB: INSERT
    DB--xS: connection lost (SQLState 08...)
    Note over S: evict the connection
    S->>DB: new connection
    loop up to 10 s
        S->>DB: pg_try_advisory_lock(lock_key)
    end
    S->>DB: read owner_token
    alt the token is still its own
        S->>DB: INSERT again, commit
        S-->>W: the entry
    else the token changed, or the row is gone
        Note over S: another process held the log: give the lock up,<br/>refuse this and every later operation
        S--xW: SagaLogBusyException
    end
```

The other operations on the log wait meanwhile: a log runs one operation at a time. If the lock cannot be had within
10 s, the operation fails with `SagaLogBusyException`, and the next one tries again.

## Recovering another instance's logs

Instance 02 recovers the sagas of instance 01, which is gone. Both use the same PostgreSQL schema and cluster name.

```mermaid
sequenceDiagram
    autonumber
    participant I2 as Instance 02
    participant P as SagaLogPool (02)
    participant DB as PostgreSQL
    I2->>P: clusterWideLogIds()
    P->>DB: list the namespace's tables
    P-->>I2: ids of 01 and 02
    loop each log of instance 01
        I2->>P: tryTakeOwnership(owner, id)
        P->>DB: open: advisory lock, new owner_token
        alt instance 01 still holds the log
            DB-->>P: lock not granted
            P--xI2: SagaLogBusyException
        else the lock is free
            P-->>I2: SagaLog
            Note over I2: recover each unfinished execution,<br/>as in "Recovery after a crash"
            I2->>P: remove(id)
            Note over P: closes the log: its lock is released
            I2->>P: releaseOwnership(owner, id)
        end
    end
```

A live instance keeps its logs locked, so a recovery trigger can run on every instance at any time: it can only take
the logs of instances that are gone.
