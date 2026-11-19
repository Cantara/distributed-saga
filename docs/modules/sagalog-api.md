# sagalog-api

`no.cantara.saga:sagalog-api` · Java module `no.cantara.sagalog` · no dependencies

The saga-log service-provider interface (SPI), the shared pool implementation, and the in-memory saga log. Every
saga-log provider implements these interfaces; `saga-execution` uses only them.

## Types

| Type | Purpose |
|---|---|
| `SagaLog` | One append-only log: write, read, truncate, and id conversions. |
| `SagaLogEntry` | One entry (immutable). |
| `SagaLogEntryBuilder` | Builds entries; has a method for each kind of entry. |
| `SagaLogEntryType` | `Start`, `End`, `Abort`, `CompStart`, `Comp`, `Ignore`. A provider must store and return every type. |
| `SagaLogEntryId` | A provider's entry id (opaque). |
| `SagaLogId` | Identifies a log: `getClusterInstanceId()`, `getLogName()`. |
| `SagaLogPool` | Opens logs, hands them out, and tracks ownership. |
| `AbstractSagaLogPool` | The pool logic shared by all providers. |
| `SagaLogInitializer` | The service a provider registers: `initialize(configuration)` returns its pool. |
| `SagaLogOwner`, `SagaLogOwnership` | Who owns a log, and since when. |
| `SagaLogBusyException`, `SagaLogAlreadyAquiredByOtherOwnerException`, `SagaPoolShutdownException` | Errors. |
| `no.cantara.sagalog.memory.*` | The memory saga log (not exported except through the service). |

## SagaLog

| Method | Contract |
|---|---|
| `write(builder)` | Appends an entry; the log assigns its id. Returns a future of the written entry. |
| `readIncompleteSagas()` | Every entry in the log, in write order, finished executions included, until truncated. (The name is historical.) |
| `readEntries(executionId)` | The entries of one execution, in write order. |
| `getSnapshotOfSagaLogEntriesByNodeId(executionId)` | `readEntries` grouped by node id (default method). |
| `truncate()` | Removes every entry. |
| `truncate(id)` | Removes the entries up to and including the entry with that id. |
| `toString(id)` / `fromString(text)` | Entry id as text and back. |
| `toBytes(id)` / `fromBytes(bytes)` | Entry id as bytes and back. |
| `builder()` | A new `SagaLogEntryBuilder`. |
| `id()` | This log's `SagaLogId`. |
| `close()` | Closes the log. |

Entry data (`getJsonData()`) is any text, written by an adapter's serializer; see each provider for what it stores.

Each provider has its own entry ids:

| Provider | Entry id | Text form |
|---|---|---|
| memory | a counter per log, from 0 | `"0"`, `"1"`, … |
| file | a counter per log, from 0 | `"0"`, `"1"`, … |
| PostgreSQL | a UUIDv7, strictly increasing per log | `"0192f5c4-…"` (36 characters) |

### Entry builder

```java
sagaLog.builder().startSaga(executionId, sagaName, inputText);   // Start on S
sagaLog.builder().startAction(executionId, nodeId);              // Start on a node
sagaLog.builder().endAction(executionId, nodeId, outputText);    // End on a node
sagaLog.builder().abort(executionId, nodeId);                    // Abort on a node
sagaLog.builder().compStart(executionId, nodeId);                // CompStart on a node
sagaLog.builder().compDone(executionId, nodeId);                 // Comp on a node
sagaLog.builder().endSaga(executionId);                          // End on E
```

`saga-execution` writes all of these; applications normally only read them.

## Pools and ownership

A pool (`SagaLogPool`) is created by a provider's `SagaLogInitializer.initialize(configuration)`. Every pool has a
*cluster instance id* (`cluster.instance-id`), the id of this application instance in a cluster.

Log ids:

- `idFor(clusterInstanceId, logName)` makes the id of any log.
- `registerInstanceLocalIdFor(logName)` makes an id for this instance and registers it as available for
  `tryAcquire`.
- `instanceLocalLogIds()` returns the registered ids; `clusterWideLogIds()` returns every log in the cluster that the
  provider can see (for recovery).

Getting a log:

| Method | What it does |
|---|---|
| `tryAcquire(owner)` / `tryAcquire(owner, timeout, unit)` | Takes the next available registered instance-local log, with ownership. Returns null if none (or on timeout). |
| `release(logId)` | Same as `releaseOwnership(logId)`. |
| `tryTakeOwnership(owner, logId)` / with timeout | Takes ownership of a specific log, connecting it if needed. Returns null if another caller holds it. |
| `releaseOwnership(logId)` | Releases ownership. A registered instance-local log becomes available to `tryAcquire` again, however it was taken. |
| `connect(logId)` | Opens the log without taking ownership in the pool; throws `SagaLogBusyException` if another process holds it. |
| `remove(logId)` | Closes the log and removes it from the pool; ownership is unchanged. Call it while you own the log: own, remove, release. |
| `delete(logId)` | Deletes the log's storage (the file, or the table and its lock row); the log must be removed first. |
| `shutdown()` | Closes and removes every log. |
| `instanceLocalSagaLogOwnerships()` | A snapshot of current ownerships, for monitoring. |

Ownership inside a pool is a lock per log: one owner at a time. A registered instance-local log is available to
`tryAcquire` exactly when nobody owns it, and at most once; taking it with `tryTakeOwnership` makes it unavailable
too. Across processes, a provider can also lock the log's storage when it connects (the PostgreSQL log with an
advisory lock, the file log with an operating-system file lock; the memory log is local to the process).

A typical pattern for a server:

```java
for (int i = 0; i < 16; i++) {
    pool.registerInstanceLocalIdFor("log-" + i);            // at start-up
}

SagaLog log = pool.tryAcquire(owner, 10, TimeUnit.SECONDS);   // per saga
try {
    // execute the saga; truncate the log in onComplete
} finally {
    pool.release(log.id());                                   // after the saga is complete and the log is clean
}
```

## The memory saga log

Provider `no.cantara.sagalog.memory.MemorySagaLogInitializer`. Configuration: `cluster.instance-id` (default
`TheOnlyInstance`).

- Entries live in memory and are lost when the process ends: no recovery after a restart.
- It stores entry data exactly as written.
- `clusterWideLogIds()` returns the instance-local ids; `delete` does nothing.
- Use it for tests, and for applications that accept losing in-flight sagas on a crash.

## Writing a provider

1. Implement `SagaLog` and `SagaLogId`.
2. Extend `AbstractSagaLogPool`: implement `idFor`, `clusterWideLogIds`, `connectExternal` (open the storage, and
   lock it against other processes if it can be shared) and `deleteExternal`.
3. Implement `SagaLogInitializer` and register it as a service in `module-info.java`
   (`provides no.cantara.sagalog.SagaLogInitializer with ...`) and in
   `META-INF/services/no.cantara.sagalog.SagaLogInitializer`.

Keep the contract above: write order on read, `truncate(id)` inclusive, data stored unchanged, `toString`/`fromString`
and `toBytes`/`fromBytes` round-trip.
