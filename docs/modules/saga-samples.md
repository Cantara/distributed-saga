---
title: "saga-samples"
description: "The polyglot-persistence sample: one saga writing to several stores, run over HTTP, with recovery."
group: Modules
order: 8
---

# saga-samples

`saga-samples` is a pom-only parent for sample applications. It has one sample, `polyglot-persistence`
(`no.cantara.saga.samples:polyglot-persistence`, Java module `no.cantara.saga.samples.polyglot`). It is not deployed.

## polyglot-persistence

A small HTTP server (Undertow) that runs one saga per `PUT` request: it "writes" the request body to three stores in
parallel, then "publishes" an event.

```
        ┌──► rdbms ───────┐
S ──────┼──► graph ───────┼──► pubsub ──► E
        └──► objectstore ─┘
```

The four adapters (`adapter/WriteToRDBMS`, `WriteToGraph`, `WriteToObjectStore`, `PublishToPubSub`) extend
`Adapter<JSONObject>` and return a JSON object that describes what a real store would do; there are no real stores,
and no compensating actions. The saga log is the file log in `./target/sagalog`.

### Run it

```sh
mvn clean install
java -jar saga-samples/polyglot-persistence/target/polyglot-persistence-<version>.jar
curl -X PUT http://127.0.0.1:8139/any/resource/path -d 'Hello Saga!' | jq .
```

The response holds the execution id, the handoff and execution times, the saga definition as JSON
(`saga-serialization`), and the log entries of the execution:

```json
{
  "executionId": "84ae…",
  "sagaHandoffTimeMs": 7,
  "sagaExecutionTimeMs": 11,
  "saga": {"saga": "Polyglot Saga", "nodes": [ ... ]},
  "log": [
    {"id": "0", "executionId": "84ae…", "type": "Start", "nodeId": "S", "sagaName": "Polyglot Saga", "jsonData": {"resource-path": "/any/resource/path", "data": "Hello Saga!"}},
    {"id": "1", "executionId": "84ae…", "type": "Start", "nodeId": "graph"},
    …
    {"id": "9", "executionId": "84ae…", "type": "End", "nodeId": "E"}
  ]
}
```

### What it shows

- `PolyglotMain`: creating the thread pool, loading a saga-log provider through `ServiceLoader` by class name, and
  the saga-log lifecycle that [Using the library](../using.md) describes:
  - a fixed set of instance-local logs (`polyglot-saga-log-0` to `-3`; `polyglot.saga-logs` in the configuration
    changes the number) is registered at start-up, with `registerInstanceLocalIdFor`. Their number limits how many
    sagas run at once;
  - before the server starts, `PolyglotRecovery` takes each log, runs every unfinished execution again with
    `recovery = true`, truncates the log once every execution in it has finished, and releases it.
- `PolyglotSaga`: the saga, built once and shared by every request, and the map from saga name to definition that
  recovery uses.
- `PolyglotHttpHandler`, per request:
  - `tryAcquire(owner, timeout)` takes a free log; the owner is the execution id. If none is free within 5 seconds,
    the reply is `503`;
  - the saga runs; the handler waits for the handoff and completion futures;
  - the reply is `201` on success, `409` when the saga was rolled back (`AbortSagaException`), and `500` for another
    failure. A failure reply also holds `rolledBack` and `error`;
  - when the saga has finished (succeeded or rolled back), the execution's entries are truncated with
    `truncate(id)` of its last entry. The entries of a saga that failed without a rollback are kept, for recovery at
    the next start-up; `truncate(id)` removes every entry before it as well, so the handler truncates only a log that
    holds no other execution's entries;
  - `releaseOwnership(owner, logId)` makes the log free again; it checks that the owner is the one that holds the log.
- The shade configuration merges `META-INF/services` (`ServicesResourceTransformer`), which a fat jar of these
  libraries needs.

### What it leaves out

- It recovers only its own instance-local logs, not the logs of other instances (`clusterWideLogIds()`).
- `GET` does nothing.
