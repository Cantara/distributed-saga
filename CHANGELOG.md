# Changelog

## 2.0.1 (unreleased)

### Fixed

- **A failed saga no longer leaves a pool thread blocked for ever.** When an action threw something other than
  `AbortSagaException` in a node that has a sibling, the walk waiting at the join for that node blocked in
  `FutureSelector.select()` for good, holding a thread of the saga's `SelectableThreadPoolExectutor`. Each such failure
  cost one thread until the executor was shut down with `shutdownNow`, and `SagaTraversalResult.waitForCompletion…`
  never returned, even with a timeout. Reported by Linked Data Store.
  - `future-selector`: a `SelectableFuture` that completes exceptionally is now selected by `FutureSelector`, like one
    that completes normally. Before, it was never selected and `select()` waited for ever. Callers that select futures
    should check the selected future (`get()` throws for a failed one); code that waited for ever before now gets the
    failure.
  - `saga-execution`: a walk that selects a failed dependency now ends with that failure. The outcome of the saga is
    unchanged: the completion future fails with the action's exception, and nothing is rolled back.
- **A failed saga fails with the action's own exception.** With the fix above, two walks could complete the saga's
  completion future, and in some runs it failed with an `ExecutionRuntimeException` wrapping the action's exception.
  Now it always fails with the action's exception, as in 2.0.0.
- **The PostgreSQL saga log gives its connection back when it is closed.** `PostgresSagaLog.close()` released the
  advisory lock but kept the connection, and left it idle in an open transaction. An application that recovers other
  instances' logs (take ownership, read, truncate, `remove`, `delete`) used up one connection per log on every pass,
  until the HikariCP pool was exhausted and recovery failed with `SQLTransientConnectionException`. Those
  transactions also held a snapshot in PostgreSQL for the life of the process. Reported by Linked Data Store.
  - `close()` (called by `remove`, `delete` and `shutdown`) now releases the lock, commits, and closes the connection,
    which returns it to the pool.
  - A log that cannot be opened (for example `SagaLogBusyException` because another process holds the lock) no
    longer keeps the connection it used.
  - `connect(logId)` after `remove(logId)` now opens the log again; before, it returned the closed log, which failed
    on every use.
  - A `write` or `truncate` that runs after `close()` fails with "already closed" instead of writing on a new
    connection without the lock.

## 2.0.0 (2026-11-19)

Released on Cantara's repository (`mvnrepo.cantara.no`), not on Maven Central. The group ids and artifact ids are
unchanged; only the version changes from 1.0.x to 2.0.0.

### Breaking changes and how to migrate

- **Java 17 is required** (was Java 11). Build and run on JDK 17 or later.
- **`sagalog-postgres`: entry ids are UUIDv7 instead of ULID.** The ULID library (`de.huxhorn.sulky.ulid`) is no
  longer a dependency.
  - Unchanged: ids are stored in the same `uuid` column, `truncate(id)` removes the same entries, ids increase
    strictly within one log, and `toBytes`/`fromBytes` use the same 16 bytes, big-endian. Ids written by 2.0.0
    sort after ULID ids already in a table, so existing tables need no migration.
  - Changed: `SagaLog.toString(id)` returns the 36-character UUID text (`0192f5c4-…`), not the 26-character ULID
    text, and `fromString` reads only the UUID text. If you stored entry ids as text, convert them, or store
    `toBytes` instead, which is unchanged.
  - If your code needs ULID itself, declare the ULID dependency yourself.
- **`SagaExecution.executeSaga`: the completion future completes after `onComplete` has returned.** Before, the
  `onComplete` consumer and the completion future's other dependents ran in no defined order, so a caller waiting on
  the completion future could continue before `onComplete` had finished (for example, before it had truncated the
  saga log). Now:
  - `onComplete` is always called on the thread that completes the saga (an executor thread), never on the thread
    that calls `executeSaga`. It may block; that holds the executor thread.
  - The completion future completes normally when the saga succeeded and `onComplete` returned.
  - When the saga failed, the completion future completes exceptionally with the saga's failure, as before; an
    exception thrown by `onComplete` is added to it as suppressed.
  - When the saga succeeded but `onComplete` threw, the completion future completes exceptionally with the new
    `SagaOnCompleteException`, whose cause is the consumer's exception. Before, that exception was lost and the
    completion future completed normally. Catch `SagaOnCompleteException` if you want the old behaviour.
  - A `null` consumer is allowed and does nothing.
  - Migration: code that waited on the completion future and also did work in `onComplete` can now rely on that
    work being done. If `onComplete` does slow work, consider that the caller now waits for it.
- **`sagalog-postgres`: `readIncompleteSagas()` and `readEntries(executionId)` return entries in write order**
  (`ORDER BY entry_id`), as the memory and file logs always did. Before, PostgreSQL returned the rows in its physical
  order, which can differ after deletes. The `SagaLog` Javadoc now states the order, and that `readIncompleteSagas()`
  returns every entry in the log, including those of finished sagas that have not been truncated yet.
- **`sagalog-postgres`: the `data` column is `text`, not `json`.** An entry's data (`SagaLogEntry.getJsonData()`)
  is whatever the action's serializer produced, which need not be JSON: the default
  `ActionOutputSerializerToStringAndStringConstructor` writes an `Adapter<String>`'s output as bare text. The memory
  log always stored it as it was; the PostgreSQL log refused it. (The file log, unchanged, still keeps data only when
  it starts with `{`; see `docs/modules/sagalog-file.md`.) New tables get a `text` column, and
  data is written untyped, so tables created before 2.0.0 keep working for JSON data. To store non-JSON data in such a
  table, migrate it once per saga-log table:
  `ALTER TABLE "<schema>"."<table>" ALTER COLUMN data TYPE text;` (lossless).

### Java module system

- `no.cantara.saga.execution` now `requires transitive` `no.cantara.saga.api`, `no.cantara.sagalog` and
  `no.cantara.concurrent.futureselector`; `no.cantara.saga.serialization` now `requires transitive`
  `no.cantara.saga.api`. These types appear in their public API, so a module that requires them now reads the others
  too; `requires` you wrote for them yourself still work.
- `no.cantara.sagalog.file` no longer requires the `tape` automatic module (see Dependencies).
- Known: `no.cantara.sagalog.postgres` requires `org.postgresql.jdbc`, an automatic
  module with a stable name from the driver's manifest.

### Dependencies

- HikariCP 6.3.3 → 7.1.0, org.json 20250517 → 20260814, undertow 2.3.20.Final → 2.4.4.Final (sample),
  logback 1.6.7 → 1.6.8 (tests and sample); test only: h2 2.5.252, embedded-postgres 2.2.2 with PostgreSQL 18.
- **Tape removed** (`com.squareup.tape2:tape:2.0.0-beta1`, unmaintained, repository archived). `sagalog-file` now
  has its own copy of Tape's `QueueFile` (`no.cantara.sagalog.file.queue`, not exported), with Square, Inc.'s
  copyright and license kept and attributed in `NOTICE`. The file format is the same: saga-log files written by 1.x
  are read by 2.0.0, which a test checks against a file written by the Tape jar. If your own code used Tape through
  this library's dependency, declare Tape yourself.
- Unchanged: postgresql 42.7.14, slf4j-api 2.0.20.
