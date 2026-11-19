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
| `cluster.owner` | `mycompany` | The database schema; created if missing. Letters, digits, `_` and `-`; at most 63 bytes. |
| `cluster.name` | `orders` | The namespace of this cluster's logs, part of every table name. Letters, digits and `-`. |
| `cluster.instance-id` | `01` | This instance's id, part of its tables' names. Letters, digits and `-`. |
| `postgres.driver.host` | `localhost` | Database host. |
| `postgres.driver.port` | `5432` | Database port. |
| `postgres.driver.user` | `test` | User; also the owner of the schema when it is created. |
| `postgres.driver.password` | `test` | Password. |
| `postgres.driver.database` | `sagalog` | Database name. |
| `postgres.driver.sslmode` | `verify-full` | The driver's TLS mode (`disable`, `allow`, `prefer`, `require`, `verify-ca`, `verify-full`). Default: the driver's, `prefer`. |
| `postgres.driver.sslrootcert` | `/etc/ssl/rds-global-bundle.pem` | The CA certificates that `verify-ca` and `verify-full` check the server against. |
| `postgres.driver.socket-timeout` | `90000` | Milliseconds a read from the database may wait (the driver's `socketTimeout`, rounded up to whole seconds). Default `0`: none. |
| `postgres.driver.tcp-keep-alive` | `true` | TCP keepalive on the database connections, so a connection whose peer is gone is noticed. Default `false`, as in the driver. |
| `postgres.driver.property.<name>` | `postgres.driver.property.ApplicationName=orders` | Any other [driver property](https://jdbc.postgresql.org/documentation/use/#connection-parameters), as it is. An unknown name fails `initialize`. |
| `postgres.statement-timeout` | `60000` | `statement_timeout` for every session of the log, in milliseconds. Default `0`: none. |
| `postgres.lock-timeout` | `30000` | `lock_timeout` for every session of the log, in milliseconds. Default `0`: none. |
| `connection-pool.max-size` | `10` | The HikariCP pool size (default 10). |
| `connection-pool.connection-timeout` | `30000` | Milliseconds to wait for a connection from the pool (default 30000, HikariCP's). |

`initialize` checks the names, opens a HikariCP pool, creates the schema and the `Locks` table if missing (and
migrates a `Locks` table made by 2.1.0 or earlier, see [Storage](#storage)), and returns the saga-log pool. Keep the
pool size above the number of logs you will open (see [Connections](#connections)).

### Timeouts and TLS

The application decides both; the provider sets nothing beyond the driver's defaults. Without timeouts, a statement
that waits on a lock, or a connection whose peer vanished without a reset (a failover, a network partition), waits
for ever, holding the log's connection and every other operation on that log, `close()` included. For a production
database, set them, for example:

```properties
postgres.statement-timeout=60000
postgres.lock-timeout=30000
postgres.driver.socket-timeout=90000
postgres.driver.tcp-keep-alive=true
```

Keep the socket timeout above the statement timeout, so that the server cancels a slow statement before the client
gives up on its connection: a connection dropped by the client leaves its server session, and the log's advisory
lock, in place until the server notices.

For Amazon RDS with certificate checks: `postgres.driver.sslmode=verify-full` and `postgres.driver.sslrootcert=` the
path of the RDS CA bundle (`global-bundle.pem`).

## Storage

- Schema `<cluster.owner>`:
  - Table `Locks (namespace, instance_id, log_id, lock_key, owner_token)`: one row per log, with the advisory-lock key
    assigned to it the first time it is opened, and the ownership token of the process that took the lock last (see
    [Ownership](#ownership-across-processes)). A `Locks` table made by 2.1.0 or earlier gets the `owner_token` column
    (`bigint NOT NULL DEFAULT 0`) when `initialize` runs; the column is added only if it is missing, so this is safe
    to run on every start.
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

    and an index on `(txid, entry_id)` for `readEntries(executionId)`, named `<table>_txid` (or `SAGALOG_txid_<hash>`
    when that would pass 63 bytes). Opening a log creates the index if it is missing, so tables made by earlier
    versions get it on their next open. On a large table, that first open waits for the index to be built, and
    holds off writes to that log meanwhile.

- Reads are `ORDER BY entry_id`, which is write order within a log. `readIncompleteSagas()` reads the whole table into
  memory, finished executions included, so truncate logs after their sagas finish.
- `truncate(id)` deletes `entry_id <= id`; `truncate()` truncates the table.
- `clusterWideLogIds()` lists the tables of the namespace in the schema (`pg_tables`); a table whose name is not a
  valid log id is skipped with a warning.
- Entry ids are UUIDv7 (RFC 9562) in the layout of PostgreSQL 18's `uuidv7()`, generated in Java; `toString` is the
  36-character UUID text, `toBytes` the 16 bytes, big-endian.

Tables made before 2.0.0 have `data json`. They keep working for JSON data; for other data, run once per table:
`ALTER TABLE "<schema>"."<table>" ALTER COLUMN data TYPE text;` (see the [CHANGELOG](../../CHANGELOG.md)).

### Names

Names are SQL identifiers, so they are checked where they enter, and quoted with any `"` doubled:
- `cluster.owner`: letters, digits, `_` and `-`;
- `cluster.name` and `cluster.instance-id`: letters, digits and `-` (`_` separates the parts of a table name);
- log names: letters, digits and `_ . : $ -`;
- the schema and the whole table name at most 63 bytes, PostgreSQL's limit. With `cluster.name=orders` and
  `cluster.instance-id=01`, a log name can have up to 45 characters.

A name outside these rules fails with `IllegalArgumentException` when its id is made (`idFor`,
`registerInstanceLocalIdFor`, `initialize`).

## Ownership across processes

Opening a log (`connect`, or taking ownership in the pool) takes the advisory lock `pg_try_advisory_lock(lock_key)`
on a connection that the log keeps, and writes a new random ownership token into the log's `Locks` row. If another
process holds the lock, the open fails with `SagaLogBusyException`. Closing the log unlocks it. So a log is written by
one process at a time, cluster-wide.

The lock belongs to the session. If the session ends while the log is open (a failover, a network reset, a proxy that
drops an idle connection, `pg_terminate_backend`), the lock is free at once, and another process may take the log.
So the next operation of the log, which finds its connection gone:
1. evicts the connection, gets a new one, and takes the lock again, waiting up to 10 s for it;
2. reads the token. If it is still the log's own, nobody held the log in between, and the operation runs again on
   the new session. If it changed (or the row is gone: the log was deleted), another process held the log: the
   operation fails with `SagaLogBusyException`, the log gives the lock up, and every later operation on it fails the
   same way. Remove the log from the pool and connect it again; it then reads the log afresh.
3. If the lock cannot be had within 10 s, the operation fails with `SagaLogBusyException`; the next operation tries
   again.

While it waits, the log's other operations wait too. A process on 2.1.0 or earlier does not write the token, so during
a rolling upgrade a log taken over by an old process in that window is not detected.

## Connections

- Each open log holds one connection from the HikariCP pool for as long as it is open, because the advisory lock
  belongs to that connection. `close()` (also called by `remove(logId)` and `shutdown()`) releases every advisory lock
  of the session (`pg_advisory_unlock_all()`), commits, and gives the connection back; if that fails, the connection is
  evicted instead, which ends the session and its locks. Set `connection-pool.max-size` above the number of logs
  open at the same time (instance-local logs plus the logs of other instances being recovered). When the pool is used
  up, opening a log waits once for the connection timeout and fails with `PostgresSagaLogException` whose message gives
  the pool size and says to raise `connection-pool.max-size`.
- Every operation runs in a transaction on that connection. When a statement or its commit fails because the
  connection is gone (SQLState class `08`, or `57P01` to `57P03`), the log reconnects as above and runs the operation
  once more. Any other failure (a timeout, a missing table, a constraint) is not retried. Failing to get a connection
  from the pool is not retried either.
- A `write` whose commit succeeded but whose reply was lost is retried with the same entry id; finding that entry
  already stored, identical, counts as success.
- Every operation runs on the calling thread. `write` and `truncate` return a future that is already complete, with
  the entry or with the failure: the log runs its operations one at a time on its one connection, so running them on
  another thread would add a thread hop and nothing else. No thread pool is created, and the common fork-join pool is
  not used.

## Failures

- `SagaLogBusyException`: another process holds the log, or took it after this log's connection was lost.
- `IllegalStateException`: the log was used after it was closed. The message names the log.
- `PostgresSagaLogException` (in `no.cantara.sagalog.postgres`, unchecked): any other database failure. The message
  names the operation (`open`, `write`, `truncate`, `readIncompleteSagas`, `readEntries`, `delete`,
  `clusterWideLogIds`, `initialize`), the log or schema, the database's message and SQLState; the cause is the
  `SQLException`. A failure while cleaning up is added to it as suppressed.

## Behaviour to know

- Before 2.1.0 a log never gave its connection back, and `close()` left it idle in a transaction; opening a log again
  after `remove(logId)` returned the closed log. Both are fixed in 2.1.0.
- `delete(logId)` drops the log's table and its row in `Locks`, holding the log's advisory lock while it does; it fails
  with `SagaLogBusyException` if another process holds the log. It returns false if the table did not exist.
- With RDS Proxy, session-level advisory locks pin each log's connection to its database session; this has not been
  tested.
