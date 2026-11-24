# Changelog

## 2.1.5 (unreleased)

### Changed
- **A join node holds no pool thread.** A node with several incoming nodes is now started by the walk of the last of
  them to be done: each incoming node signals it when its visit has ended, and the signal that was the last one it
  waited for starts it, with every incoming node's output. Before, the walk of the first incoming node to be done
  waited at the join on its pool thread for the others. With fewer pool threads than the branches waiting at joins
  at the same time, the walks that would complete the joins stayed queued behind them, and the sagas deadlocked, with
  no timeout, and a saga that had aborted never rolled back. Reproduced with two sagas `S -> {a, b} -> j -> E` on a
  pool of two threads (`SagaExecutionJoinTest`), and in 21 of 25 random sagas on pools of one to three threads
  (`SagaExecutionRandomGraphTest`, 300 cases in the build). A pool smaller than the branches now makes sagas slower,
  not stuck. The log entries, their order, the rollback and recovery are unchanged; the traversal and its walks are
  internal. Two things that only a thread could observe differ: a join's `preAction`, action and `postAction` run on
  the thread of its last incoming node instead of its first, and its `ancestors` are still those of the first.
- `org.postgresql:postgresql` 42.7.15.

### Fixed
- **A saga log held by another process no longer keeps the free logs from being acquired.** A registered log that
  failed to connect (`SagaLogBusyException`: another process holds its file lock or its PostgreSQL advisory lock) went
  back to the front of the available logs, so every later `tryAcquire` took it again: the free logs were never handed
  out while it was held, and the timed `tryAcquire` threw instead of waiting. It now goes to the back, behind the other
  available logs, and stays available for a later attempt, as the Javadoc says.

## 2.1.4 (2026-11-22)

### Changed
- **`sagalog-postgres` fails at start when the database has no room for the pool.** `initialize` takes all
  `connection-pool.max-size` connections at once and gives them back. When PostgreSQL refuses one for lack of
  connections (SQLState `53300`: `max_connections` used up, the slots left reserved for superusers, or a role's or the
  database's `CONNECTION LIMIT`), it closes the pool at once, without waiting for the connection timeout (it would
  hold the server's free connections meanwhile), and throws `PostgresSagaLogException` (operation `initialize`,
  SQLState `53300`). Any other failure to open a connection may pass, so it waits, and fails (with that failure's
  SQLState, or none) only when no connection has come for a whole `connection-pool.connection-timeout`. The message
  gives how many connections the pool got, the server's `max_connections`, `superuser_reserved_connections`,
  `reserved_connections`, the client connections in use, any `CONNECTION LIMIT`, and what to change. Before, HikariCP
  started with one connection and logged its refusals only at DEBUG; each open saga log holds a connection, so the
  logs failed later, under load, after a connection timeout each. Found by LDS: a second LDS on the same PostgreSQL
  started, and its writes failed with "too many clients" after 30 s.
- **A pool that is short of connections says why.** When no connection is available and the pool has fewer than its
  maximum, the `SQLTransientConnectionException` says whether the database refused more for lack of connections (make
  room on the server) or could not be reached or logged in to, instead of advising a larger pool.
- `PostgresSagaLogException`'s message ends with the SQLState also when it has a message of its own.
- **CI builds and tests on JDK 17 and JDK 27** (was 17 only): 17 is the library's target and the oldest Java it runs
  on, 27 the newest (LDS runs on it). The code is compiled with `--release 17` on both; coverage and SonarCloud run
  on 17. Test dependency `embedded-postgres` 2.2.3.

### Migration
- An application whose pool did not fit on the server used to start and fail later; it now fails to start. Lower
  `connection-pool.max-size` or make room on the server. Size the pool for every saga log the application keeps open,
  plus the logs of other instances it recovers at the same time, plus one (see "Connections" in
  `docs/modules/sagalog-postgres.md`); processes that share a server need room for all their pools together. An
  application that retries `initialize` should wait a random time between tries.

## 2.1.3 (2026-11-20)

No behaviour or API changes: code quality from SonarCloud, and CI.

### Changed
- **CI on GitHub Actions** (`.github/workflows/ci.yml`): every push to `main` and every pull request builds on JDK 17
  and runs every test, the fault matrix included; pushes and pull requests from this repository add JaCoCo coverage
  (`-P coverage`) and a SonarCloud analysis (`-P sonar`). Releases are still made on Jenkins.
- **SonarCloud findings** (28, all settled; 0 bugs, vulnerabilities, code smells and hotspots after):
  - `FutureSelector.select` restores the interrupt where it catches `InterruptedException` (`launder` already did).
  - `SagaExecution`'s backward visit returns nothing instead of always `null`.
  - `PostgresSagaLogId.SCHEMA` (package-private) is `SCHEMA_PATTERN`; the sample adapters' `NAME` is `ADAPTER_NAME`.
  - SQL that formats validated, quoted identifiers into a statement (`sagalog-postgres`), and `FileSagaLogLock`'s
    channel, which is always closed, carry `@SuppressWarnings` with the reason.
- **Tests:** two tests no longer depend on thread timing: the se-2 test (`SagaExecutionFailureTest`) and
  `SagaExecutionAbortNextToSiblingTest`, which order the branches of a fork with latches instead of sleeps. They failed
  on the two-core CI runner and on Jenkins, while the engine behaved correctly.
- **README:** SonarCloud quality-gate and reliability badges.

## 2.1.2 (2026-11-20)

### Added
- **jlink support.** All library modules link into a custom runtime image. `sagalog-postgres` no longer requires the
  PostgreSQL JDBC driver's module (`org.postgresql.jdbc`, an automatic module, which jlink rejects): it creates the
  driver's `PGSimpleDataSource` by class name, once per pool, with the same settings as before. The application
  provides the driver, on the class path (as it must be with jlink) or the module path; Maven still brings it in, now
  as a runtime dependency. Without the driver, `initialize` fails with `IllegalStateException` that names it. A test
  fails the build if an automatic module enters the module's graph. See "jlink" in `docs/architecture.md`.

### Changed
- **PostgreSQL saga log, module descriptor:** the module no longer `opens` its package (nothing reflects on it), and
  `PostgresSagaLogInitializer.initialize` is declared to return `SagaLogPool` instead of the package-private
  `PostgresSagaLogPool`, which no caller outside the package could name. Code that uses the initializer through
  `SagaLogInitializer` or `ServiceLoader` is unchanged. javac's only remaining module warning is `requires-automatic`
  for the PostgreSQL JDBC driver, which ships an `Automatic-Module-Name` and no `module-info`.

## 2.1.1 (2026-11-20)

Fixes and changes from the module design review of 2026-11-19. Each entry names its finding id.

### Breaking changes and how to migrate

- **Upgrade every instance together.** Stop every instance that runs 2.1.0 before the first instance starts on this
  version; don't run the two side by side. The PostgreSQL log's takeover check (sp-1) works only when every process
  writes the owner token, which 2.1.0 doesn't, and the file log stores its next entry id where 2.1.0 doesn't look
  (sf-9).
- **The PostgreSQL schema gets two additions, applied automatically and idempotently** (sp-1, sp-10):
  - the `Locks` table gets a column `owner_token bigint NOT NULL DEFAULT 0` when the pool is initialised;
  - each saga-log table gets an index on `(txid, entry_id)` when the log is opened.

  Nothing is dropped or renamed, and 2.1.0 ignores both, so a downgrade works. On a large existing log table, the
  first open after the upgrade builds the index and briefly blocks writes to that table. The database user needs the
  rights to alter `Locks` and to create indexes; the owner of the schema the library creates has them. The stale
  example script `sagalog-postgres/init-db.sql` was removed: the library creates its schema and tables itself.
- **File saga log: `delete` keeps the log's lock file** and deletes only the log file. Before (2.1.0, and the first
  fix of sf-3), it also deleted the lock file, which let a process that had just opened the old lock file hold a lock
  nobody else saw. **Migration:** a deleted log leaves a small `<log>.sagalog.lock` file in the folder. The library
  ignores it; remove such files only when no process uses the folder.

- **sp-6: the PostgreSQL saga log refuses names that are not plain identifiers, and table names over 63 bytes.**
  `cluster.owner` must be letters, digits, `_` and `-`; `cluster.name` and `cluster.instance-id` letters, digits and
  `-`; log names letters, digits and `_ . : $ -`; the schema and the table name `SAGALOG_<name>_<instance>_<log>` at
  most 63 bytes. Before, any character was accepted and put into SQL with a `"` unescaped (a crafted log name could run
  SQL), and PostgreSQL silently truncated longer table names, so two logs could share one table. Ids are refused with
  `IllegalArgumentException` when made; `clusterWideLogIds()` skips such tables with a warning. **Migration:** rename
  logs whose names break these rules (or whose table names were truncated) before upgrading; with the default
  `cluster.name` (`internal-sagalog-integration-testing`, 36 characters) a log name can have at most 15 characters.
- **sp-1: a PostgreSQL saga log that another process took over after its connection was lost fails with
  `SagaLogBusyException`**, and so does every later operation on it. Before, the log took the advisory lock back on a
  new connection and wrote on, on top of what the other process had done (for example after a recovery pass had
  truncated it). **Migration:** on `SagaLogBusyException` from an open log, remove it from the pool and connect it
  again, which reads the log afresh. A process on 2.1.0 or earlier does not take part in this check: during a rolling
  upgrade, a log taken over by an old process is not detected.
- **sp-4: the PostgreSQL saga log's `write` and `truncate` run on the calling thread** and return a future that is
  already complete. Before, they ran on the JVM's common `ForkJoinPool`, where writes waiting on the database blocked
  every other user of that pool. **Migration:** a caller that relied on `write` returning before the database answered
  now waits for it; run the call on your own executor if you need that.
- **sp-7: the PostgreSQL saga log's failures have types.** Using a closed log throws `IllegalStateException`; a
  database failure throws the new `no.cantara.sagalog.postgres.PostgresSagaLogException`, whose message names the
  operation, the log and the SQLState, with the `SQLException` as its cause; `SagaLogBusyException` as before. Before,
  all were a bare `RuntimeException` (wrapping the `SQLException` once more). The module now exports
  `no.cantara.sagalog.postgres` so the exception can be caught by type. **Migration:** code that caught
  `RuntimeException` still catches them; code that looked at `getCause().getCause()` for the driver's exception finds
  it at `getCause()`.
- **sp-13: `h2.enabled` (and `h2.driver.url`) is removed from the PostgreSQL saga log.** It could not work: H2 has no
  advisory locks. **Migration:** remove the keys; use PostgreSQL.
- **sp-16: `HikariPostgresDataSource`, `PostgresDataSource` and `PostgresSagaTools` are no longer public**; they were
  internal. **Migration:** none known; nothing outside the module used them.
- **sl-6: a shut-down saga-log pool refuses new work.** After `SagaLogPool.shutdown()`, `connect`, `tryAcquire` and
  `tryTakeOwnership` throw `SagaPoolShutdownException`, and a connect in progress during shutdown closes its log and
  throws it too. Before, the pool opened logs (connections, locks) again after shutdown. **Migration:** shut the pool
  down after the sagas and the recovery that use it have stopped. A provider that extends `AbstractSagaLogPool` and
  overrides `shutdown()` must call `super.shutdown()`.
- **sl-5: `SagaLogPool.delete` of a log still in the pool throws `IllegalStateException`** (a `RuntimeException`
  before), and the message names the log id (it showed `%s` before). **Migration:** code that caught
  `RuntimeException` still catches it; code that compared the exact class must expect `IllegalStateException`.
- **sl-10: the memory saga log refuses a `null` cluster instance id** with `NullPointerException` when the id is made
  (`idFor(null, name)`). Before, the id was made, and comparing it threw `NullPointerException` later. **Migration:**
  pass a cluster instance id.
- **A built saga can no longer be changed (sa-1).** `SagaNode.incoming` and `SagaNode.outgoing` are unmodifiable views;
  a write throws `UnsupportedOperationException`. Before, they were public mutable lists, so a saga that `end()` had
  validated could be changed afterwards, while it was shared by running executions. The field types
  (`Collection<SagaNode>`) and reading them are unchanged. **Migration:** build a new saga with the builder instead of
  changing the links of a built one.
- **The builder rejects a duplicate link (sa-6).** `linkTo("a", "a")` throws `SagaException` ("Duplicate link from
  node(x) to node(a)"); before, the target got two incoming entries from the same node. `SagaDeserializer.fromJson`
  rejects a node whose `outgoing` lists an id twice in the same way. **Migration:** list each target once.
- **A `null` saga name, node id or adapter throws `SagaException` (sa-4),** as a `null` link already did. Before, it
  threw `NullPointerException`. **Migration:** catch `SagaException`.
- **`SagaDeserializer.fromJson` throws `SagaException` for malformed JSON and missing or mistyped fields (ss-1),** with
  the `org.json.JSONException` as its cause and a message that starts with "De-serialization error". Before, the
  `JSONException` reached the caller. **Migration:** catch `SagaException` (it already covered graph errors).
- **`Selection.future` and `Selection.control` are `final` (fs-3).** Reading them is unchanged. **Migration:** code
  that assigned them must keep its own values instead.
- **`Utils`, `SagaSerializer` and `SagaDeserializer` cannot be instantiated (fs-5, ss-3):** they have private
  constructors. **Migration:** call their static methods.
- **File saga log: `delete` fails with `SagaLogBusyException` when another holder has the log open** (sf-3). It takes
  the log's lock, deletes the log file and its lock file while holding it, and then releases it. Before, it deleted
  both files without trying the lock, so a process that still had the log open went on writing to a deleted file, and
  the next open succeeded next to it. **Migration:** delete a log only once no other process or pool has it open; a
  caller that deletes logs of other instances handles `SagaLogBusyException` (unchecked, as the PostgreSQL log's
  `delete` already throws).
- **File saga log: `write` and `truncate` no longer throw; every failure fails the returned future** (sf-7), as the
  PostgreSQL log does. **Migration:** a caller that caught an exception from the call itself gets it from `join()`
  (as `CompletionException`) or `exceptionally`/`whenComplete` instead. The engine is not affected.
- **File saga log: exception types** (sf-4, sf-10, sf-12). An I/O failure is an `UncheckedIOException` that names the
  operation and the file ("Cannot open saga-log ...", "Cannot write to saga-log ...", "Cannot truncate saga-log ...",
  "Cannot read saga-log ...", "Cannot delete saga-log ...", "Cannot list the saga-log folder ...", "Cannot create the
  saga-log folder ..."). Before, it was a `RuntimeException` with only the cause, and a read failure escaped as an
  undeclared checked `IOException` (for example a bare `EOFException`) that `catch (RuntimeException e)` missed. A
  record with a bad string length or an unknown entry type is now "Corrupt saga-log entry" (`UncheckedIOException`)
  instead of `NegativeArraySizeException`, a huge allocation or `IllegalArgumentException`. Use after `close()` is
  `IllegalStateException` ("Saga-log is closed: ..."). **Migration:** code that matches on `RuntimeException` still
  matches; code that matched on the cause of a plain `RuntimeException` reads the cause of the `UncheckedIOException`.
- **File saga log: log names and instance ids with a path separator (`/`, `\`) or NUL are refused**, and so are
  instance ids that contain `.--.` or end in a part of it (sf-13), with `IllegalArgumentException` from `idFor`. Such a
  name pointed outside the folder, failed at open, or made `clusterWideLogIds()` fail. **Migration:** use plain names.
- **File saga log: `FileSagaLog` is package-private, and `FileSagaLogInitializer.initialize` returns `SagaLogPool`**
  (sf-14). Nothing changes on the module path, where the package was never exported. **Migration:** on the class
  path, open logs through the pool (`SagaLogPool.connect`), not with `new FileSagaLog(...)`.

- **A saga whose walk the executor rejects now fails through its futures; `executeSaga` no longer throws
  `RejectedExecutionException`** (se-4). Before, a rejected first walk was thrown from `executeSaga`, and the saga's
  futures and `onComplete` were never completed. Now `executeSaga` returns, `onComplete` is called once with a failure
  result, and the handoff and completion futures fail with the `RejectedExecutionException`. **Migration:** handle a
  rejection where you handle the saga's other failures, on the completion future (or the handoff future); a
  `try`/`catch` around `executeSaga` no longer sees it. **Resources held for the saga** (its saga log, a semaphore
  permit that limits concurrent sagas) must be released in `onComplete` or on the completion future, which see every
  outcome, rejection included. A caller that released them only in a `catch` around `executeSaga` now leaks them
  whenever the executor rejects a walk.
- **`executeSaga` checks its arguments** (se-14): a null `executionId`, `preAction` or `postAction` throws
  `NullPointerException` at once. Before, a null callback failed the saga inside its first walk. **Migration:** pass
  non-null callbacks (the four-argument `executeSaga` passes no-op ones).
- **`AdapterLoader.load` returns `SagaAdapter<?>`, and `register`, `SagaExecutionTraversalContext`'s constructor and
  `getAdapter()` take or return `SagaAdapter<?>`, not the raw type** (se-13). Binary compatible. **Migration:** code
  that assigned the result to a parameterized `SagaAdapter<X>` (an unchecked conversion before) casts explicitly.
- **A rejection policy that runs tasks on the submitting thread no longer runs walks** (se-5). Under
  `ThreadPoolExecutor.CallerRunsPolicy`, a walk the pool would run on the submitting thread is rejected, and the saga
  fails with `RejectedExecutionException`, instead of deadlocking the caller (an abort on that walk waited for its
  own thread) or hanging at a join (a child walk run inline waited for siblings its parent had not started).
  **Migration:** use a queue, or `AbortPolicy`.
- **`SagaExecution` takes and returns `SelectableThreadPoolExecutor`** (fs-9), the correctly spelled type: its
  constructor and `getExecutorService()`. A pool created as the deprecated `SelectableThreadPoolExectutor` still
  passes, since it is a subclass. **Migration:** code that assigns `getExecutorService()` to a
  `SelectableThreadPoolExectutor` uses the new type. README, `using.md` and the sample use the new name.

### Fixed

- **sp-2: the PostgreSQL saga log retries the statement that meets a lost connection.** Every operation wrapped its
  `SQLException` before the retry could see it, so the first operation after a lost connection failed, and only the
  next one reconnected. Now an operation whose statement or commit fails because the connection is gone (SQLState
  class `08`, `57P01` to `57P03`) reconnects, takes the lock back (sp-1) and runs once more. Other failures are no
  longer retried (before, a failed commit of any kind was).
- **sp-3: closing a PostgreSQL saga log leaves no advisory lock on a pooled connection.** A reconnect waited with
  `Object.wait`, which let a second operation take the lock a second time on the same session; `close()` unlocked once
  and gave the connection, still holding the lock, back to the pool, so no process could open the log until the
  connection was retired. The reconnect now waits holding the log, and `close()` and `delete` release with
  `pg_advisory_unlock_all()`, evicting the connection if that fails.
- **sp-8: a PostgreSQL write whose commit succeeded but whose reply was lost succeeds.** Its retry met its own row and
  failed on the primary key, so a durable write was reported as failed. The insert is now
  `ON CONFLICT (entry_id, txid) DO NOTHING`, and finding the same entry stored counts as success.
- **sp-14: two processes that open the same new PostgreSQL log at once** get the log and `SagaLogBusyException`; the
  loser got a duplicate-key `RuntimeException` before.
- **sp-11: `PostgresSagaLogPool.shutdown()` calls `super.shutdown()`**, so the pool's ownerships and logs are cleared
  and the shut-down state applies (sl-6); it then closes the connection pool, keeping the first failure. The
  provider's own map of every log ever opened is gone.
- **sl-1: `SagaLogPool.shutdown()` closes every log.** Before, it stopped at the first log that failed to close, left the
  others open (connections, file and advisory locks) and the pool not cleared. Now every log is closed, the pool is
  cleared either way, and the first failure is thrown with later ones added as suppressed.
- **sl-3: releasing a log that was never taken throws `IllegalStateException`** (`releaseOwnership(logId)`,
  `release(logId)`), as documented, instead of `NullPointerException`.
- **sl-4: the `SagaLogEntry.getJsonData()` Javadoc** said the file log drops data that is not a JSON object. Every saga
  log stores the data unchanged, the file log too since 2.1.0.
- **Laundering an `InterruptedException` keeps the thread interrupted (fs-1).** `Utils.launder` restores the
  interrupt status before it returns the `InterruptedRuntimeException`, so `FutureSelector.select` and every other
  caller leave the thread interrupted, as the Java contract asks. Before, the status was cleared, and code further up
  that caught the runtime exception (a pool worker, a request loop, `shutdownNow`) no longer saw the interrupt. A
  caller that catches `InterruptedRuntimeException` and then blocks again on the same thread now fails at once;
  **migration:** call `Thread.interrupted()` first if that thread must go on waiting.
- **`SelectableFuture.run()` runs its task at most once (fs-6),** like `FutureTask`. Before, a second call ran the task
  again and dropped the result. The executor calls `run()` once, so only code that ran a future by hand is affected.
- **The sample's saga-log lifecycle (pp-1, pp-2, pp-3).** The polyglot-persistence sample registered a new log per
  request thread, never truncated it and never recovered. It now registers a fixed set of logs at start-up, recovers
  their unfinished sagas before serving, takes a log per request with `tryAcquire(owner, timeout)`, truncates the
  execution's entries once the saga has finished, and releases the log with the owner-checked
  `releaseOwnership(owner, logId)` (sl-3). It replies 409 for a rolled-back saga and
  500 for another failure, with the execution id; it builds the saga once; and its module no longer requires
  `java.net.http`.
- **File saga log: a refused second open in the same process no longer releases the first open's lock** (sf-1). The
  operating-system lock belongs to the process, and closing the refused open's handle on the lock file released it, so
  another process could then open the log and write to it at the same time. The module now keeps a process-wide
  registry of the lock files it holds and refuses a second open before it touches the file.
- **File saga log: `clusterWideLogIds()` no longer leaks a directory handle per call** (sf-2); periodic recovery ran
  out of file descriptors.
- **File saga log: a log file, and the folder, are durable once created or deleted** (sf-5): the folder is synced
  after a log file is created or deleted and after the folder is created (skipped on Windows).
- **File saga log: `close()` is idempotent and waits for a running write, truncate or read** (sf-6). A second close
  threw `ClosedChannelException`, so closing a pooled log and then `pool.shutdown()` failed.
- **File saga log: a stray `*.sagalog` file without the `.--.` separator is skipped with a warning** (sf-8); it made
  `clusterWideLogIds()` fail for every log.
- **File saga log: entry ids stay unique across restarts, also after the log was emptied** (sf-9). Ids restarted at 0
  when an emptied log was opened again. The next id is now kept in the log's lock file (a 12-byte record, written
  before a truncate empties the log); the queue file format is unchanged, and a 2.1.0 lock file, which is empty, reads
  as no record. A failed open releases the lock even on an `Error`, and a cleanup failure is attached to the first
  failure with `addSuppressed` (sf-10).

- **Any failure stops the saga, and a failed saga completes only once none of its walks can still run** (se-1, se-2;
  matrix rows fm-1). Before, only an abort stopped the traversal. After any other failure, a walk waiting at a join
  for a step behind the failed one stayed blocked for ever, one pool thread per failed saga (a step between the
  failing step and the join, `S → f, a; f → b; b → j; a → j`, or the same backwards after a failed compensation);
  and the completion future failed at once while other branches still started steps, ran actions and wrote `Start`
  and `End` entries, after `onComplete` and after the caller might have truncated or reused the log. Now a failing
  walk stops the traversal: waiting walks return, no new step starts, a running action ends and writes its `End`, and
  then the saga completes, with the step's own exception, as before. Abort and rollback are unchanged. A failed saga
  completes later, by the time its running actions take.
- **A failure in the rollback itself completes the saga** (se-3; matrix rows fm-2). If the rollback's read of the
  log's entries failed (a lost database connection), or its first walk could not start, the saga never completed and
  `onComplete` was never called. The rollback now has a catch-all entry point: the saga fails with that exception, the
  abort suppressed on it, as when a compensating action fails.
- **The rollback no longer waits for a pool thread, and no thread waits for the forward walks** (se-3, se-5). It
  starts on the thread of the last forward walk to end, once all have ended, instead of a task that waited on a pool
  thread for them (one more thread per aborting saga, and dropped by `shutdownNow`), and the aborting walk no longer
  waits for `executeSaga` to return.
- **`shutdownNow()` no longer leaves sagas waiting for ever** (se-4; matrix rows fm-2). A walk the executor dropped
  never completed the saga's futures. Now a walk that the executor drops and the caller cancels (the tasks
  `shutdownNow()` returns) fails the saga with the `CancellationException`, and when another walk of the saga fails
  (it is interrupted), the walks that have not started are ended at once, so the saga completes without the caller
  cancelling them. A saga all of whose walks were queued completes only when they are cancelled: documented in
  `using.md` and `limitations.md`.
- **`Adapter`'s serializer says what it could not deserialize** (se-11): `IllegalStateException("cannot deserialize
  <class> from its String constructor")`, with the constructor's own exception as cause (not the
  `InvocationTargetException`), instead of a bare `RuntimeException`.

### Changed

- **sp-5, sp-13: timeouts and TLS for the PostgreSQL saga log are configurable** with new keys:
  `postgres.statement-timeout`, `postgres.lock-timeout`, `postgres.driver.socket-timeout`,
  `postgres.driver.tcp-keep-alive`, `postgres.driver.sslmode`, `postgres.driver.sslrootcert`,
  `connection-pool.connection-timeout`, and `postgres.driver.property.<name>` for any other driver property. The
  defaults are the driver's own (no timeouts, no keepalive, `sslmode=prefer`), so nothing changes unless the
  application sets them; [sagalog-postgres](docs/modules/sagalog-postgres.md#timeouts-and-tls) recommends values.
- **sp-1: the PostgreSQL `Locks` table has a new column, `owner_token bigint NOT NULL DEFAULT 0`.** `initialize` adds
  it to a table made by 2.1.0 or earlier, only if it is missing; the rows and keys are kept. 2.1.0 processes can run
  against the migrated table.
- **sp-10: each PostgreSQL log table has an index on `(txid, entry_id)`** for `readEntries(executionId)`, which scanned
  and sorted the whole table before. It is created when a log is opened, also on tables made by earlier versions; on a
  large table that first open waits for the index to be built.
- **sp-9, sp-12, sp-15, sp-16, sp-17:** the PostgreSQL log is split into `LogSession` (connection, lock, retry),
  `LockRegistry` (the `Locks` table) and the entry SQL; the test hook `failOnXAttempts` is gone in favour of fault
  tests with `pg_terminate_backend`, a second process and a locked table (fm-3, `PostgresSagaLogFaultTest`); logging
  uses placeholders and no longer logs and rethrows; `module-info` drops `requires java.logging` and the unused `init`
  package; `PostgresSagaLogId.compareTo` no longer overflows; switch expressions and text blocks.
- **sl-2: `connect` no longer runs the provider's connect inside a `ConcurrentHashMap` lock.** One connect runs per log
  id; callers for the same id wait for it and get the same log, and callers for other ids no longer wait behind it.
  A failed connect leaves nothing in the pool, as before.
- **sl-3: new `SagaLogPool.releaseOwnership(SagaLogOwner owner, SagaLogId logId)`**, which releases the log only for
  the owner that holds it: `SagaLogAlreadyAquiredByOtherOwnerException` if another owner holds it (nothing is
  released), `IllegalStateException` if nobody does. `releaseOwnership(SagaLogId)` and `release(SagaLogId)` are
  deprecated and work as before. The interface method is a default method that throws
  `UnsupportedOperationException`; `AbstractSagaLogPool` implements it, so every provider in this repository has it.
- **sl-7: new `SagaLogPool.tryAcquire(SagaLogOwner, long, TimeUnit)`**, so the timeout types of `tryAcquire` and
  `tryTakeOwnership` agree. The `int` overload stays. A negative timeout does not wait.
- **sl-8, sl-9: Javadoc** of `SagaLogPool` and `SagaLog` completed, including the threading, ownership and shutdown
  contracts, and that nothing in the library uses the entry-id conversions.
- **sl-10:** the `sagalog-api` module no longer `opens` `no.cantara.sagalog.memory` (nothing reflects on it); raw types
  removed; `serialVersionUID` on the three exception classes.
- **`SelectableThreadPoolExecutor` (fs-9):** the executor under a correctly spelled name. `SelectableThreadPoolExectutor`
  is kept as a deprecated subclass, so existing code compiles and runs unchanged. `SagaExecution` still takes the old
  type; until it takes the new one, create the pool for it under the old name.
- **Saga names may contain `{` and newlines (sa-2).** That rule protected the file log's text format before 2.1.0;
  both saga logs and `saga-serialization` now store any name as it is. A saga name may be any non-null string. Node
  ids and adapter names keep their rule, documented as the rule for identifiers: visible characters only.
- **`SagaException(String, Throwable)` (sa-5),** a constructor that keeps the cause.
- **`Saga.toString()` and `SagaNode.toString()` (sa-7)** show the name and node count, and the id and adapter.
- **`SagaSerializer` throws `UncheckedIOException` when the `Writer` fails (ss-2),** with a message that names the saga
  or node. Before, it threw a bare `RuntimeException`; both are unchecked and keep the `IOException` as the cause.
- **`FutureSelector` documents its threading contract (fs-2):** any thread may add, one thread selects.
- **File saga log: the file is read once, when the log is opened** (sf-11). The open log keeps its decoded entries in
  memory, and `readIncompleteSagas`, `readEntries` and `truncate(id)` use them with no file I/O. Before, each of them
  read the whole file under the log's monitor, so a recovery of *k* executions read it *k + 2* times. A corrupt file
  is now reported when the log is opened, not at the first read. Memory follows the log's size. The file format is
  unchanged.
- **File saga log: internal structure** (sf-15, sf-16). The entry id is a record (its `toString` is now
  `FileSagaLogEntryId[id=...]`), the record codec is its own class, and the lock is its own class. The module no
  longer `opens` its package. New tests: two-process tests of the lock and `delete`, failure paths, close twice,
  descriptor leak, stray files, and concurrent writes, reads and truncates.

- **Several failures of one saga:** the first is the saga's failure, and a later, different one is added to it as
  suppressed (se-1). Before, the later ones were dropped.
- **Parallel walks no longer share an ancestors deque** (se-14): each walk gets its own, so a `preAction` or
  `postAction` that reads it does not race with the sibling walks.
- **`SagaTraversalResult`** (for tests, se-9, se-12): documented as such; no longer prints to standard output; its
  package-private walk counter is gone; after a rollback it holds the forward walks only. Waiting on it no longer
  blocks on a node a stopped traversal never visited.
- **Documentation** (se-6, se-7, se-10, se-14): every saga-log call blocks a pool thread and has no timeout in the
  engine; the application sizes the pool and limits concurrent sagas; the handoff and completion futures must not be
  completed or cancelled by callers; actions throw a new exception each time; Javadoc on `SagaHandoffControl`,
  `SagaHandoffResult`, `SagaExecutionTraversalContext`, `AdapterLoader`.
- se-13: `serialVersionUID` on `AbortSagaException` and `SagaOnCompleteException`; `AdapterLoader`'s constructor no
  longer calls the overridable `register`.

## 2.1.0 (2026-11-19)

### Breaking changes and how to migrate

- **The file saga log has a new entry format.** Each entry is a versioned binary record with length-prefixed fields,
  so any text is stored as it is: data that is not a JSON object, spaces in ids and names, empty strings, `null` and
  large data. Before, data not starting with `{` was read back as `null`, a space in an execution id made the log
  unreadable, and a saga name stored without data lost its last character. **Migration:** logs written before 2.1.0
  cannot be read (opening one fails with `IllegalStateException`, "unsupported format"): let their sagas finish with
  the old version, or delete the log files, before upgrading.
- **`delete(logId)` on the PostgreSQL saga log drops the log's table and its row in `Locks`**, holding the log's
  advisory lock while it does, and fails with `SagaLogBusyException` if another process holds the log. It returns
  whether the table existed. Before, it only closed the log, so the tables of departed instances stayed and were found
  again by `clusterWideLogIds()` on every recovery pass. **Migration:** call `delete` only for logs whose entries you no
  longer need.
- **The saga-log pool keeps one availability state per instance-local log: available exactly when nobody owns it, at
  most once.** `tryTakeOwnership` of a registered local log now makes it unavailable to `tryAcquire`, and
  `releaseOwnership` makes it available again, as `release` does (the two are now the same). Before, `release` added the
  id again without checking, so each `tryTakeOwnership`/`release` pair added a duplicate (Linked Data Store measured 3
  ids growing to 21 in 6 s of recovery), and `tryAcquire` could pick a log another caller held and throw
  `IllegalStateException`; it now skips it and returns `null` when no log is free. **Migration:** code that relied on
  `releaseOwnership` keeping a local log out of `tryAcquire` must not register that log as instance-local.
- **`SagaLogPool.remove` documentation corrected:** it does not require the log to be released first, and never did;
  the safe order is own, remove, release.

- **Compensation is run and logged only for steps that have a compensating action, and the last log entry of a step
  decides its state.** Before, a rollback wrote `Comp` for every ended step, also when its compensating action did
  nothing, and recovery and rollback judged a step by whether *any* `End` or `Abort` entry existed (taking the first
  `End`'s output). After a crash during a rollback, forward recovery then skipped the compensated steps and reported
  success while their effects stayed undone (finding M1 of the fault-injection matrix).
  - `SagaAdapter` has a new method, `boolean hasCompensatingAction()`, with no default. **Migration:** implementations
    of `SagaAdapter` must implement it. `Adapter` implements it: true when a compensating action is given to the
    constructor or a subclass overrides `executeCompensatingAction`; the constructors without one (or with `null`)
    mean none.
  - New entry type `SagaLogEntryType.CompStart`, written before each compensating action runs (`Comp` after it). The
    PostgreSQL log stores it as `6`. **Migration:** a custom saga-log provider must store and return it.
  - Rollback runs the compensating action of a step whose last entry is `Start`, `End` or `CompStart`, if the adapter
    has one. A step that started but did not end is compensated with a `null` output, and its action is **not** run
    again first (before, it was run, to be undone). **Migration:** compensating actions must handle a `null` output
    and an action that may never have run.
  - Forward recovery skips only a step whose last entry is `End`, and passes on the output of its last `End`. A step
    whose last entry is `CompStart` or `Comp` (its effect may be undone), `Abort` or `Start` runs again, after a new
    `Start` unless the last entry already is one.
  - **Migration:** saga logs written by 2.0.0 hold `Comp` entries for steps whose compensating action did nothing;
    forward recovery would run those steps again. Let such executions finish, or truncate the logs, before upgrading.

### Fixed

- **An exhausted PostgreSQL connection pool fails after one wait, with a clear message.** Opening a saga log when
  every connection was held by an open log waited twice for HikariCP's connection timeout (60 s by default), because
  the reconnect retry also ran when no connection could be had, and failed with a bare `RuntimeException`. It now
  fails after one timeout; the message gives the pool size and says to raise `connection-pool.max-size`.

- **An abort next to another failure is no longer lost.** If one node threw `AbortSagaException` while another failed
  with a different exception, the saga failed with the other exception and the abort disappeared; it is now attached
  as a suppressed exception. No rollback runs in that case, as before: the saga is left for forward recovery.

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
- **The file saga log locks its file.** Opening a log takes an exclusive operating-system lock on `<log file>.lock`
  until it is closed; a second open, in any process, fails with `SagaLogBusyException`. Before, two processes could
  write the same file and lose entries. `delete` removes the lock file too. Migration: close a `FileSagaLog` before
  opening the same log again (the saga-log pools already do).
- **File saga log entry ids stay unique across restarts.** They restarted at 0 when a log was opened again, so
  `truncate(id)` could remove the wrong entries; now they continue after the highest id in the file.
- **Rollback of a node that started but did not end** no longer fails with a `NullPointerException` while gathering
  the outputs of the node's dependencies.
- **`FutureSelector.select(timeout, unit)` no longer counts a timeout as a selection**, so `pending()` stays right
  after a timeout.
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
