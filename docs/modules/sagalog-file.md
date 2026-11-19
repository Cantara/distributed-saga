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
  Tape `QueueFile` (2.0.0-beta1), in the package `no.cantara.sagalog.file.queue`; see [NOTICE](../../NOTICE). Its file
  format is unchanged, so files written by 1.x are read by 2.x.
- Each entry is one queue element: the text `<id> <executionId> <type> <nodeId>[ <sagaName>][ <data>]` in UTF-8.
- `truncate(id)` removes the first elements up to and including that id; `truncate()` clears the file. `delete`
  deletes the file.
- Writes, truncates and reads of one log are synchronized within the process.

## Behaviour to know

- **Entry data is kept only when it starts with `{` (a JSON object).** Other data, such as `OK`, `[1,2]` or a JSON
  string, is read back as `null`. The same applies to the saga input on the first entry; input that is not a JSON object
  is lost, and the saga name read back is wrong. Use JSON-object output and input with this log.
- **No spaces in execution ids or node ids.** The fields are separated by spaces and not escaped; a space makes the
  entry, and the log, unreadable. Node ids cannot have spaces (see [saga-api](saga-api.md)); execution ids must not
  either. Saga names may contain spaces.
- **Entry ids restart at 0 when a log is opened again.** After a restart, new entries can have the same ids as
  entries already in the file, so `truncate(id)` can remove the wrong entries. Use `truncate()` (all entries) after a
  restart, not `truncate(id)`.
- **No lock between processes.** Two processes that open the same file both write to it, and entries are lost. Make
  sure each log file is used by one process only (one `cluster.instance-id` per process and folder).
- Power loss during a write can corrupt the file on file systems that do not write a segment atomically (most of
  them); this is a property of the Tape format.
