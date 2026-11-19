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

The response holds the handoff and execution times, the saga definition as JSON (`saga-serialization`), and the log
entries of the execution:

```json
{
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

- `PolyglotMain`: creating the `SelectableThreadPoolExectutor`, loading a saga-log provider through `ServiceLoader`
  by class name, and wiring the HTTP server.
- `PolyglotHttpHandler`: building a saga, registering adapters, taking ownership of a log per request thread
  (`registerInstanceLocalIdFor(threadName)` and `tryTakeOwnership`), executing, waiting for the handoff and completion
  futures, reading the log, and releasing ownership.
- The shade configuration merges `META-INF/services` (`ServicesResourceTransformer`), which a fat jar of these
  libraries needs.

### What it leaves out

The sample never truncates its logs, so the log files grow with every request, and it never recovers. See
[Using the library](../using.md) for both.
