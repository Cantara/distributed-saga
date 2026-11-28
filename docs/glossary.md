---
title: "Glossary"
description: "The terms of this documentation, each with one meaning, and the name the code gives it."
group: Reference
order: 24
---

# Glossary

The terms the documentation uses, each with one meaning. Where the code has its own name for the same thing, the name
is given after the definition.

## Sagas and steps

### Saga

A named, directed acyclic graph of nodes that is run as one operation: from the [start node](#start-node) through
its steps to the [end node](#end-node). A saga is a definition, validated when it is built, and the same saga can be
run any number of times. See [Concepts](concepts.md#saga). Code: `Saga`, built with `Saga.start(name)`.

### Node

One point of a saga's graph: an id, unique in its saga, and the name of the [adapter](#adapter) that runs for it.
Code: `SagaNode`.

### Step

A node other than the start and end nodes: one action of the saga, with its compensating action.

### Start node

The node every saga starts at, with id `S`. Running it writes the saga's first entry, `Start` on `S`, with the saga's
name and its [input](#saga-input). Code: `Saga.ID_START`, adapter `SagaStart`.

### End node

The node every saga ends at, with id `E`. Running it writes `End` on `E`, which makes the saga
[finished](#finished). Code: `Saga.ID_END`, adapter `SagaEnd`.

### Link

An edge of the graph: a link from A to B means that B runs after A. Code: `linkTo(...)`, `linkToEnd()`.

### Fork

A node with several outgoing links. Its branches run in parallel, each on a thread of the pool.

### Join

A node with several incoming links. It runs once all of them are done, and receives the output of each. A waiting
join holds no thread: the walk of the last branch to be done continues into it. See
[the traversal protocol](protocols.md#the-traversal-protocol).

## Adapters

### Adapter

The code that runs for a node: an action, a compensating action, and a serializer for the action's output. Adapters
are registered by name, and one adapter serves every node that names it, on many threads, so it must be stateless
and thread-safe. See [saga-execution](modules/saga-execution.md#adapters). Code: `SagaAdapter`, `Adapter`.

### Action

What a step does. It receives the [saga input](#saga-input) and the outputs of the nodes it depends on, and returns
its own output. Code: `executeAction(node, sagaInput, dependeesOutput)`.

### Compensating action

What undoes a step's action, given the action's output; `null` when the action started but did not end. It must be
safe to run more than once. A step without one keeps its effect on [rollback](#rollback). Code:
`executeCompensatingAction(node, sagaInput, actionOutput)`, `hasCompensatingAction()`.

### Adapter loader

The registry of adapters by name, which an execution looks a node's adapter up in. Code: `AdapterLoader`.

### Saga input

Any object the caller passes when a saga starts. Every action receives it, and it is serialized into the saga's first
entry, so [recovery](#recovery) can pass it again.

### Output

What an action returns. It is serialized into the step's `End` entry and passed to the nodes that depend on the step,
and to the step's compensating action. Code: `dependeesOutput`, `actionOutput`.

### Serializer

Turns an action's output into text for the saga log, and the text back into the output. Code: `SagaAdapter.serializer()`.

### Idempotent

Safe to run more than once with the same effect as once. Every action and compensating action must be idempotent,
because they run [at least once](#at-least-once).

## Running a saga

### Execution

One run of a saga, under an id the caller chooses. Recovering a saga is a new run of the same execution. Code:
`SagaExecution.executeSaga(executionId, input, recovery, onComplete)`.

### Execution id

The id of one execution, chosen by the caller. Every entry of the execution carries it. Code: `executionId`.

### Walk

The work of one thread through the graph: a walk starts at `S`, submits a new walk to the pool for each extra branch
of a fork, and ends at a join it is not the last to reach, or at `E`. See
[Threading](architecture.md#threading).

### Thread pool

The executor that walks run on, which the application creates, sizes and shuts down. A saga's steps block on it,
never on the common pool. See [Using the library](using.md#3-create-a-thread-pool). Code:
`SelectableThreadPoolExecutor`.

### Handoff future

Completes once `Start` on `S` is in the saga log: from then on the saga survives a crash. Code:
`SagaHandoffControl.getHandoffFuture()`.

### Completion future

Completes once the saga is finished and `onComplete` has returned; exceptionally when the saga failed or was rolled
back. Code: `SagaHandoffControl.getCompletionFuture()`.

### onComplete

The callback the caller passes to `executeSaga`. It runs once, when the saga ends, before the completion future
completes, and is where a finished saga's log is truncated.

### Finished

A saga whose execution has `End` on `E` in the log, after success or after rollback. A saga that is not finished is
[recovered](#recovery).

### At least once

The delivery the library gives: every action runs at least once, and may run again on recovery. Exactly once is not
offered. See [Design decisions](design-decisions.md#1-delivery-semantics-at-least-once).

## Failure

### Abort

A step's action that throws `AbortSagaException` aborts the saga: `Abort` is written on the step, the forward walk
stops, and the saga is [rolled back](#rollback). Code: `AbortSagaException`.

### Rollback

The walk back from `E` after an abort: every step that started, and has a compensating action, is compensated
between `CompStart` and `Comp`, a step only after the steps that depend on it. The saga then writes `End` on `E`. See
[Concepts](concepts.md#rollback).

### Recovery

Running an unfinished execution again with `recovery = true`, the same execution id and the original input. A step
whose last entry is `End` is skipped and its logged output passed on; every other step runs. The application finds
the unfinished executions and starts their recovery; the library does not. See
[Using the library](using.md#7-recover-unfinished-sagas).

## The saga log

### Saga log

The append-only log of an execution's entries, read back in the order they were written. Each log has one writer at a
time. See [sagalog-api](modules/sagalog-api.md). Code: `SagaLog`.

### Entry

One record of the saga log: its id, the execution id, its type, the node it is about, and its data (the saga input
or an action's output). Code: `SagaLogEntry`.

### Entry type

What an entry records: `Start` (before an action, or the saga's start on `S`), `End` (after an action, with its
output, or the saga's end on `E`), `Abort`, `CompStart` (before a compensating action), `Comp` (after it), and
`Ignore`, a control entry about no node. See [the saga-log protocol](protocols.md#the-saga-log-protocol). Code:
`SagaLogEntryType`.

### Last entry

The latest entry of a node in an execution, which decides its state: an execution can hold several rounds of the same
node. See [Reading a node's state](protocols.md#reading-a-nodes-state).

### Truncate

Removing a log's entries, all of them or up to an entry. A finished saga's entries are truncated in `onComplete`.
Code: `SagaLog.truncate()`.

### Saga-log pool

Manages the saga logs of one application instance: it hands out this instance's logs with ownership and connects to
any log of the cluster for recovery. See [Pools and ownership](modules/sagalog-api.md#pools-and-ownership). Code:
`SagaLogPool`.

### Log id

The address of a saga log: a cluster instance id and a log name. Code: `SagaLogId`.

### Cluster instance id

The id of one application instance in a cluster, configured as `cluster.instance-id`. The logs an instance owns
carry it.

### Instance-local log

A log registered for this instance, which the pool hands out with `tryAcquire`. The number of them is a natural
limit on the sagas that run at once. Code: `registerInstanceLocalIdFor(name)`.

### Ownership

The right to write a log, held by one owner at a time. Within a process it is a lock per log; across processes the
provider holds it: an advisory lock and an owner token in PostgreSQL, an operating-system lock on a file. See
[the ownership protocol](protocols.md#the-ownership-protocol-within-a-process). Code: `SagaLogOwner`,
`SagaLogOwnership`.

### Saga-log provider

An implementation of the saga log, found by `ServiceLoader`: in memory (`sagalog-api`), a file (`sagalog-file`) or
PostgreSQL (`sagalog-postgres`). See [Deployment models](deployment-models.md). Code: `SagaLogInitializer`.
