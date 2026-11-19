# saga-serialization

`no.cantara.saga:saga-serialization` · Java module `no.cantara.saga.serialization` · depends on `saga-api`
(`requires transitive`) and `org.json:json`

Converts a `Saga` definition to JSON and back, to store saga definitions, send them between services, or show them.
It serializes the graph only, not executions or log entries.

## API

| Method | Purpose |
|---|---|
| `SagaSerializer.toJson(Saga)` | The saga as a JSON string. |
| `SagaSerializer.writeJson(Saga, Writer)` | The same, written to a `Writer`. |
| `SagaSerializer.writeJson(SagaNode, Writer)` | One node as JSON. |
| `SagaDeserializer.fromJson(String)` | Builds and validates a `Saga` from JSON; throws `SagaException` if it is invalid. |

## Format

```json
{
  "saga": "Polyglot Saga",
  "nodes": [
    {"id": "S", "adapter": "SagaStart", "outgoing": ["rdbms", "graph", "objectstore"]},
    {"id": "rdbms", "adapter": "RDBMS", "outgoing": ["pubsub"]},
    {"id": "pubsub", "adapter": "PubSub", "outgoing": ["E"]},
    {"id": "E", "adapter": "SagaEnd", "outgoing": []},
    {"id": "graph", "adapter": "Graph", "outgoing": ["pubsub"]},
    {"id": "objectstore", "adapter": "ObjectStore", "outgoing": ["pubsub"]}
  ]
}
```

- `saga` is the name; `nodes` lists every node once, in depth-first order from `S`.
- Each node has its `id`, its `adapter` name and the ids of its `outgoing` nodes. Incoming links are derived.

`fromJson` rebuilds the saga with the `saga-api` builder, so the same validation applies. It needs a node with id `S`;
the `S` and `E` entries' adapter names are not read (the builder sets them).

## Behaviour to know

- The format has no version field.
- Unknown fields are ignored.
- A very large definition (tens of thousands of nodes) can cause a `StackOverflowError` in validation; do not
  deserialize untrusted input without a size limit.
