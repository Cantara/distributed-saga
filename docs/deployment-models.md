---
title: "Deployment models"
description: "How an application that embeds the library is deployed: in memory, one instance with a file log, or several instances on PostgreSQL; what each needs, how to size it, and how to start, recover, upgrade and stop it."
group: Guides
order: 11
---

# Deployment models

The library is deployed as part of an application: it starts no threads, opens no ports and runs no process of its
own. What changes from one deployment to another is the saga-log provider, and so what survives a crash and who can
recover it. Three models follow from the three providers.

| | In memory | One instance, file log | Several instances, PostgreSQL |
|---|---|---|---|
| Provider | `MemorySagaLogInitializer` (`sagalog-api`) | `FileSagaLogInitializer` (`sagalog-file`) | `PostgresSagaLogInitializer` (`sagalog-postgres`) |
| Survives a process crash | No | Yes | Yes |
| Who recovers a saga | Nobody | The same instance, after a restart | Any instance of the cluster |
| Needs | Nothing | A persistent local disk | A PostgreSQL database |
| Instances | One | One per folder of logs, or several that each keep to their own logs | Any number |
| Use for | Tests, development, sagas whose loss on a crash is acceptable | A single server with its own disk | Production clusters, containers, managed databases |

## What every deployment provides

Whatever the model, the application supplies these, and the library supplies none of them:

1. **A thread pool,** a `SelectableThreadPoolExecutor`, sized for the branches that run at once. Keep `AbortPolicy`
   or another policy that does not run tasks on the submitting thread. See
   [Using the library](using.md#3-create-a-thread-pool).
2. **A limit on sagas at once.** The number of instance-local logs is a natural one: `tryAcquire` waits or returns
   `null` when they are all owned.
3. **The idempotency of every action and compensating action,** because recovery may run a step again. See
   [Design decisions](design-decisions.md#1-delivery-semantics-at-least-once).
4. **A recovery loop** at start-up, and for a cluster, a trigger that recovers the logs of instances that are gone.
5. **An ordered shutdown** (below).

## In memory

```java
SagaLogPool pool = memoryInitializer.initialize(Map.of("cluster.instance-id", "01"));
```

- Entries live in the heap. A crash loses every saga in flight: it is neither finished nor rolled back, and the
  steps it ran stay done.
- Nothing to configure or operate. This is the model of most of the library's own tests.
- Choose it only when a lost saga is acceptable, or the steps are repaired some other way.

## One instance with a file log

```java
SagaLogPool pool = fileInitializer.initialize(Map.of(
        "filesagalog.folder", "/var/lib/myapp/sagalog",
        "cluster.instance-id", "01"));
```

- One file per log in the folder. A write is on disk when `write` returns, and the folder is synced when a file is
  created or deleted, so a saga survives a crash of the process and of the machine.
- After a restart, the instance opens its logs and recovers the executions without `End` on `E`.
- The folder must be on a disk that outlives the process: not a container's writable layer, which a new container
  starts without. Mount a volume, or use PostgreSQL.
- An operating-system lock keeps each file to one process at a time. Several processes can use one folder, each with
  its own `cluster.instance-id`; any of them can open another's log once that process has closed it.
- Power loss during a write can corrupt the file on file systems that do not write a segment atomically; see
  [sagalog-file](modules/sagalog-file.md#behaviour-to-know).
- Keep logs small: truncate in `onComplete`. A file shrinks only when it is emptied, and it is read into memory when
  opened.

## Several instances on PostgreSQL

```properties
cluster.owner=mycompany
cluster.name=orders
cluster.instance-id=01
postgres.driver.host=db.internal
postgres.driver.port=5432
postgres.driver.database=sagalog
postgres.driver.user=orders
postgres.driver.password=...
postgres.driver.sslmode=verify-full
postgres.statement-timeout=60000
postgres.lock-timeout=30000
postgres.driver.socket-timeout=90000
postgres.driver.tcp-keep-alive=true
connection-pool.max-size=20
```

- Every instance uses the same database, `cluster.owner` (the schema) and `cluster.name` (the namespace), and its own
  `cluster.instance-id`. Each log is a table; the `Locks` table holds each log's advisory-lock key and ownership token.
- Each instance runs its own sagas on its own logs. When an instance is gone, another one lists the cluster's logs
  (`clusterWideLogIds()`), takes the logs of the missing instance, recovers their sagas, and removes those logs from
  its pool. Opening a log that a live instance holds fails with `SagaLogBusyException`, so two instances never write
  one log.
- **Timeouts.** They are `0` (none) by default. Set them, as above: without them a statement waiting on a lock, or a
  connection whose peer vanished without a reset, waits for ever and holds the log. Keep the socket timeout above
  the statement timeout.
- **Connections.** Each open log holds one connection while it is open. Set `connection-pool.max-size` above the
  number of logs open at once: the instance-local logs, plus the logs being recovered, plus one for the pool's own
  queries. `initialize` takes every connection once at start-up, so a pool that does not fit the server fails at once
  rather than under load.
- **Poolers.** The advisory lock belongs to the server session, so a connection pooler must use session pooling.
  Behind PgBouncer the start-up check proves only that the pooler accepted the connections. RDS Proxy has not been
  tested.
- **Containers and serverless.** Nothing is kept on the instance's disk, so instances can be replaced freely. The
  model needs long-running processes: a saga runs on the instance's own thread pool until it ends.

Every key, the storage layout and the reconnect rules: [sagalog-postgres](modules/sagalog-postgres.md).

## Sizing

| Setting | Decides | Guideline |
|---|---|---|
| Pool threads (core size, with an unbounded queue) | Branches running at once, across all sagas | The sagas you allow at once × their widest fork. A smaller pool is slower, not stuck. |
| Queue of the pool | What happens when it is full | Unbounded: sagas queue. Bounded: a walk that does not fit fails its saga with `RejectedExecutionException`. |
| Instance-local logs | Sagas running at once in the instance | One per saga you allow at once. |
| `connection-pool.max-size` (PostgreSQL) | Logs open at once | Above the instance-local logs, plus logs being recovered, plus one. |
| PostgreSQL timeouts | How long a stalled database can hold a log | Statement and lock timeouts, and a socket timeout above them. |

Each PostgreSQL log writes on its own connection, one operation at a time, so sagas on different logs write in
parallel, and a log's throughput is set by the database's round trips. Throughput grows with logs, not with threads;
see [Design decisions](design-decisions.md#3-the-saga-log-is-synchronous-there-is-no-asynchronous-saga-log).

## Start-up

1. Open the provider's pool: `initializer.initialize(configuration)`.
2. Register the instance-local logs: `registerInstanceLocalIdFor(name)` for each.
3. Recover: for each log, read its entries, and run every execution without `End` on `E` with `recovery = true`. For a
   cluster, also recover the logs of instances that are gone. See
   [Using the library](using.md#7-recover-unfinished-sagas).
4. Start accepting work that starts sagas.

## Shutdown

The library has no shutdown order of its own; the application keeps this one:

1. Stop starting sagas and stop the recovery trigger.
2. Wait for the running sagas, with a bound.
3. Shut down the thread pool. After `shutdownNow()`, cancel the tasks it returns.
4. `SagaLogPool.shutdown()`.
5. Close what the logs use.

A saga cut off by the shutdown keeps its entries, and is recovered at the next start. Details:
[Using the library](using.md#9-shut-down).

## Packaging

- **Class path or module path.** Every library module is a named Java module; the libraries work on the class path
  too.
- **Shaded jars.** Merge the `META-INF/services` files (`ServicesResourceTransformer` for the Maven Shade plugin), or
  `ServiceLoader` finds only one provider.
- **jlink.** All library modules link into a custom runtime. The PostgreSQL JDBC driver is an automatic module, which
  jlink rejects: put it on the class path of the runtime image. See [Architecture](architecture.md#jlink).

## Upgrades

- Upgrade every instance of a cluster together when the CHANGELOG says so: 2.1.1 and later must not run side by side
  with 2.1.0.
- File logs written before 2.1.0 cannot be read: let their sagas finish with the old version first.
- PostgreSQL tables made before 2.0.0 keep working for JSON data; other data needs a one-time column change.
- From 2.1.x, 2.2.0 is a drop-in upgrade.

The [CHANGELOG](https://github.com/Cantara/distributed-saga/blob/main/CHANGELOG.md) has the steps for each version.
