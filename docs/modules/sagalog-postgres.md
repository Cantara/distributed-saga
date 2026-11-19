# sagalog-postgres

`no.cantara.saga:sagalog-postgres` · Java module `no.cantara.sagalog.postgres` · depends on `sagalog-api`, HikariCP,
the PostgreSQL JDBC driver and the SLF4J API

A saga log in PostgreSQL. Every log is a table; logs are shared storage, so several application instances can run
against one database, and any instance can recover the logs of another. Ownership of a log across processes is a
PostgreSQL advisory lock.

## Configuration

Provider: `no.cantara.sagalog.postgres.PostgresSagaLogInitializer`.

| Key | Example | Meaning |
|---|---|---|
| `cluster.owner` | `mycompany` | The database schema; created if missing. Must not contain `.`. |
| `cluster.name` | `orders` | The namespace of this cluster's logs, part of every table name. Must not contain `_`. |
| `cluster.instance-id` | `01` | This instance's id, part of its tables' names. Must not contain `_`. |
| `postgres.driver.host` | `localhost` | Database host. |
| `postgres.driver.port` | `5432` | Database port. |
| `postgres.driver.user` | `test` | User; also the owner of the schema when it is created. |
| `postgres.driver.password` | `test` | Password. |
| `postgres.driver.database` | `sagalog` | Database name. |
| `connection-pool.max-size` | `10` | The HikariCP pool size (default 10). |

`initialize` opens a HikariCP pool, creates the schema and the `Locks` table if missing, and returns the saga-log
pool. Keep the pool size above the number of logs you will open (see [Connections](#connections)).

## Storage

- Schema `<cluster.owner>`:
  - Table `Locks (namespace, instance_id, log_id, lock_key)`: one row per log, with the advisory-lock key assigned to
    it the first time it is opened.
  - One table per log, `SAGALOG_<cluster.name>_<instance-id>_<log-name>`, created when the log is first opened:

    ```sql
    entry_id    uuid     NOT NULL,   -- UUIDv7, strictly increasing per log
    txid        varchar  NOT NULL,   -- the execution id
    entry_type  smallint NOT NULL,
    node_id     varchar  NOT NULL,
    saga_name   varchar  NULL,
    data        text     NULL,       -- the entry data, as written
    PRIMARY KEY (entry_id, txid)
    ```

- Reads are `ORDER BY entry_id`, which is write order within a log.
- `truncate(id)` deletes `entry_id <= id`; `truncate()` truncates the table.
- `clusterWideLogIds()` lists the tables of the namespace in the schema (`pg_tables`).
- Entry ids are UUIDv7 (RFC 9562) in the layout of PostgreSQL 18's `uuidv7()`, generated in Java; `toString` is the
  36-character UUID text, `toBytes` the 16 bytes, big-endian.

Tables made before 2.0.0 have `data json`. They keep working for JSON data; for other data, run once per table:
`ALTER TABLE "<schema>"."<table>" ALTER COLUMN data TYPE text;` (see the [CHANGELOG](../../CHANGELOG.md)).

## Ownership across processes

Opening a log (`connect`, or taking ownership in the pool) takes the advisory lock `pg_try_advisory_lock(lock_key)`
on a connection that the log keeps. If another process holds the lock, the open fails with `SagaLogBusyException`.
Closing the log unlocks it. So a log is written by one process at a time, cluster-wide.

## Connections

- Each open log holds one connection from the HikariCP pool for as long as it is open, because the advisory lock
  belongs to that connection. `close()` (also called by `remove(logId)` and `shutdown()`) releases the lock, commits,
  and gives the connection back. Set `connection-pool.max-size` above the number of logs open at the same time
  (instance-local logs plus the logs of other instances being recovered); when the pool is used up, opening or writing
  waits for HikariCP's connection timeout and fails.
- Every operation runs in a transaction on that connection. On an `SQLException` the log gets a new connection,
  takes the lock again, and retries the operation once.
- `write` and `truncate` run on the common fork-join pool (`CompletableFuture.supplyAsync`/`runAsync`); the returned
  futures complete when the transaction has committed. The reads run on the calling thread.

## Behaviour to know

- Before 2.0.1 a log never gave its connection back, and `close()` left it idle in a transaction; opening a log again
  after `remove(logId)` returned the closed log. Both are fixed in 2.0.1.
- `delete(logId)` only closes the log and forgets it; it does not drop its table.
- Names are put into SQL without quoting checks: use plain names for `cluster.owner`, `cluster.name`,
  `cluster.instance-id` and log names. PostgreSQL truncates identifiers longer than 63 bytes, so keep table names
  shorter than that.
