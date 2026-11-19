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

- One file per log: `<cluster-instance-id>.--.<log-name>.sagalog` in the folder. `clusterWideLogIds()` lists every
  `*.sagalog` file in the folder.
- Each file is a queue file: a ring buffer with a small header, where a change takes effect only when the header is
  rewritten. Every write is synchronous; data is on disk when `write` returns. The implementation is a copy of Square's
  Tape `QueueFile` (2.0.0-beta1), in the package `no.cantara.sagalog.file.queue`; see [NOTICE](../../NOTICE). The queue
  file format is Tape's, unchanged; the entry records inside it changed in 2.0.1 (below).
- Each entry is one queue element in a binary record: a format-version byte (`2`), the id as a long, then execution
  id, entry type, node id, saga name and data, each as a length-prefixed UTF-8 string (length `-1` for null). Any
  text is stored as it is.
- `truncate(id)` removes the first elements up to and including that id; `truncate()` clears the file. `delete`
  deletes the file.
- Writes, truncates and reads of one log are synchronized within the process.

## Behaviour to know

- **Logs written before 2.0.1 cannot be read.** Their entries were space-separated text that lost data not starting
  with `{`; opening such a log fails with `IllegalStateException` ("unsupported format"). Let their sagas finish with
  the old version, or delete the log file, before upgrading.
- **One open log per file.** Opening a log takes an exclusive lock on `<log file>.lock` (an operating-system file
  lock, so it holds between processes) until `close()`. A second open, in another process or in the same one, fails
  with `SagaLogBusyException`. `delete` removes the lock file too.
- Entry ids continue after the highest id in the file when a log is opened again, so they stay unique across
  restarts.
- Power loss during a write can corrupt the file on file systems that do not write a segment atomically (most of
  them); this is a property of the Tape format.
