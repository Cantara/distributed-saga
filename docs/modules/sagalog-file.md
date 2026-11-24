# sagalog-file

`no.cantara.saga:sagalog-file` · Java module `no.cantara.sagalog.file` · depends on `sagalog-api`

A saga log that stores each log in one file on the local file system. It survives a restart of the process, so a
single application instance can recover its sagas. It is not meant for logs shared between processes.

## Configuration

Provider: `no.cantara.sagalog.file.FileSagaLogInitializer`.

| Key | Required | Meaning |
|---|---|---|
| `filesagalog.folder` | yes | The folder for the log files; created if missing. |
| `cluster.instance-id` | yes | This instance's id; part of every file name. |

```java
SagaLogPool pool = initializer.initialize(Map.of(
        "filesagalog.folder", "./var/sagalog",
        "cluster.instance-id", "01"));
```

## Storage

- One file per log: `<cluster-instance-id>.--.<log-name>.sagalog` in the folder, next to its lock file
  `<log file>.lock`. `clusterWideLogIds()` lists every `*.sagalog` file in the folder; a file with that suffix whose
  name is not a saga-log id (no `.--.`, for example an operator's `backup.sagalog`) is skipped with a warning (logged
  through `System.Logger`). A log name or instance id with a path separator (`/`, `\`) or NUL is refused with
  `IllegalArgumentException`, and so is an instance id that contains `.--.` or ends in a part of it.
- Each file is a queue file: a ring buffer with a small header, where a change takes effect only when the header is
  rewritten. Every write is synchronous; data is on disk when `write` returns. When a log file is created or deleted,
  and when the folder is created, the folder is synced too, so the file's name is durable as well (on Windows, where a
  folder cannot be synced this way, this step is skipped). The implementation is a copy of Square's Tape `QueueFile`
  (2.0.0-beta1), in the package `no.cantara.sagalog.file.queue`; see [NOTICE](../../NOTICE). The queue file format is
  Tape's, unchanged; the entry records inside it changed in 2.1.0 (below).
- Each entry is one queue element in a binary record: a format-version byte (`2`), the id as a long, then execution
  id, entry type, node id, saga name and data, each as a length-prefixed UTF-8 string (length `-1` for null). Any
  text is stored as it is. A record with a length that does not fit it, or an unknown entry type, is reported as a
  corrupt entry (`UncheckedIOException`, "Corrupt saga-log entry in ...").
- `truncate(id)` removes the first elements up to and including that id; `truncate()` clears the file. `delete`
  deletes the file and keeps its lock file.
- Writes, truncates, reads and `close()` of one log are serialised within the process.
- **The file shrinks only when it becomes empty.** It doubles when full, and goes back to 4 KiB only when the last
  entry is removed: by `truncate()`, or by `truncate(id)` of the last entry. A log that is never emptied (no truncation,
  or `truncate(id)` with a saga always in flight) stays at its peak size. Truncate with `truncate()` once a saga ends,
  as the engine's `onComplete` pattern does.
- **The file is read once, when the log is opened.** Opening decodes every entry and keeps them in memory; after
  that, `readIncompleteSagas`, `readEntries` and `truncate(id)` use the in-memory copy and do no file I/O. Only the
  open log can change its file (it holds the lock), so the copy stays exact; if a write or truncate fails, the log
  reads the file again. If that fails too, the log refuses every later operation with `IllegalStateException`; close
  it, which releases the file and the lock, and open it again. Up to 2.1.4 such a log marked itself closed without
  releasing them, so it could not be opened again in the same process. Memory follows the log's size; with the documented
  pattern the log stays small. A corrupt file is reported when the log is opened.

## Failures

- `write` and `truncate` report every failure through the returned future, as the PostgreSQL log does: the future
  fails, and the call itself does not throw. An I/O failure is an `UncheckedIOException` that names the operation and
  the file ("Cannot write to saga-log ...", "Cannot truncate saga-log ...").
- The reads (`readIncompleteSagas`, `readEntries`, and so `getSnapshotOfSagaLogEntriesByNodeId`) throw an I/O failure
  as `UncheckedIOException` ("Cannot read saga-log ..."), never as an undeclared checked `IOException`.
- Opening a log throws `SagaLogBusyException` (see below), `UncheckedIOException` ("Cannot open saga-log ...") or
  `IllegalStateException` (an entry in an unsupported format). A failed open releases everything it took.
- `close()` is idempotent: a second call does nothing. It waits for a running write, truncate or read of the log. Any
  use after close fails with `IllegalStateException` ("Saga-log is closed: ..."), through the future for `write` and
  `truncate`.
- A failure while cleaning up (closing the file or releasing the lock after an earlier failure) is attached to the
  first failure with `addSuppressed`.

## Behaviour to know

- **Logs written before 2.1.0 cannot be read.** Their entries were space-separated text that lost data not starting
  with `{`; opening such a log fails with `IllegalStateException` ("unsupported format"). Let their sagas finish with
  the old version, or delete the log file, before upgrading.
- **One open log per file.** Opening a log takes an exclusive lock on `<log file>.lock` (an operating-system file
  lock, so it holds between processes) until `close()`. A second open, in another process or in the same one, fails
  with `SagaLogBusyException`. Because the operating-system lock belongs to the process (closing any handle of the
  lock file in the process releases it), the module never opens a second handle on a lock file this process holds: a
  process-wide registry refuses the second open first. The registry lives in the module's classes, so two copies of
  the module loaded by different class loaders in one JVM do not see each other's logs; do not open one log through
  both.
- **An interrupt does not break the log.** The JDK closes a file channel when the thread using it is interrupted, and
  closing the lock file's channel releases the lock. So `write` and `truncate` set a pending interrupt aside while
  they use the files, and restore it before they return: on an interrupted thread they complete as on any other, and
  the thread stays interrupted. If the lock is lost all the same (an interrupt that arrives during the I/O), the log
  refuses every later operation with `SagaLogBusyException`, so it never writes a file another process may have opened
  since; close it and open it again. Up to 2.1.4, a truncate that emptied the log on an interrupted thread released the
  lock and left the log writing without it.
- **`delete` takes the lock.** `delete` takes the log's lock, deletes the log file while holding it, then releases it.
  It fails with `SagaLogBusyException` when another process, or an open log or another pool in this process, holds the
  log (as the PostgreSQL log does). The lock file stays: every process always locks the same file, so a delete can't
  leave one process holding a lock on a removed lock file while another locks a new one. A deleted log leaves its
  small `.lock` file in the folder; listing logs ignores it.
- **Entry ids stay unique for the life of the log file, across restarts, also after the log was emptied.** When a log
  is opened again, ids continue after the highest id in the file and after the next id kept in the lock file. That
  record is written (12 bytes, synced) before a truncate empties the log; a lock file from 2.1.0 is empty and reads
  as no record. The lock file outlives `delete`, so a log made again under the same name continues after that record
  when there is one. The log
  assigns ids; an id already set in the builder is kept, must be greater than every id in the log, and ids assigned
  later follow it.
- Power loss during a write can corrupt the file on file systems that do not write a segment atomically (most of
  them); this is a property of the Tape format.
