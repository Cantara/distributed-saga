---
title: "saga-serialization"
description: "Sagas to and from JSON, and the JSON format."
group: Modules
order: 7
---

# saga-serialization

`no.cantara.saga:saga-serialization` · Java module `no.cantara.saga.serialization` · depends on `saga-api`
(`requires transitive`) and `org.json:json`

Converts a `Saga` definition to JSON and back, to store saga definitions, send them between services, or show them.
It serializes the graph only, not executions or log entries.

## API

| Method | Purpose |
|---|---|
| `SagaSerializer.toJson(Saga)` | The saga as a JSON string. |
| `SagaSerializer.writeJson(Saga, Writer)` | The same, written to a `Writer`. A failing `Writer` gives `UncheckedIOException` ("Writing saga ... as JSON"), with the `IOException` as its cause. |
| `SagaSerializer.writeJson(SagaNode, Writer)` | One node as JSON. |
| `SagaDeserializer.fromJson(String)` | Builds and validates a `Saga` from JSON; throws `SagaException` if it is invalid. |

Both classes hold static methods only, cannot be instantiated, and are stateless and thread-safe.

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
- Each node has its `id`, its `adapter` name and the ids of its `outgoing` nodes, in declaration order. Incoming
  links are derived.
- Every string is written as an escaped JSON string, so a saga name with quotes, backslashes, `{`, newlines or any
  Unicode character survives a round trip.

`fromJson` rebuilds the saga with the `saga-api` builder, so the same validation applies. It needs a node with id `S`;
the `S` and `E` entries' adapter names are not read (the builder sets them).

Every error is a `SagaException`: malformed JSON or a missing or mistyped field gives `De-serialization error: ...`
with the parser's `org.json.JSONException` as the cause, and a graph error gives the builder's message. Up to 2.1.0
the `JSONException` reached the caller as it was.

## Behaviour to know

- The format has no version field.
- Unknown fields are ignored.
- A very large definition (tens of thousands of nodes) can cause a `StackOverflowError` in validation; do not
  deserialize untrusted input without a size limit.
