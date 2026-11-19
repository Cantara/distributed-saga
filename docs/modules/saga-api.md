# saga-api

`no.cantara.saga:saga-api` · Java module `no.cantara.saga.api` · no dependencies

The saga model: a named directed acyclic graph of nodes, and the builder that creates and validates it. It knows
nothing about execution or logs.

## Types

| Type | Purpose |
|---|---|
| `Saga` | A validated saga: its `name`, start node, end node, all nodes, and two traversals. |
| `Saga.SagaBuilder` | The fluent builder, started with `Saga.start(name)`. |
| `SagaNode` | One node: `id`, `adapter` name, `incoming` and `outgoing` nodes. |
| `SagaException` | Thrown when a saga is invalid. Has a constructor that takes a cause. |

Constants on `Saga`: `ID_START = "S"`, `ID_END = "E"`, `ADAPTER_START = "SagaStart"`, `ADAPTER_END = "SagaEnd"`.

## Building a saga

```java
Saga saga = Saga.start("Polyglot Saga")                 // creates S, then: where does S link to?
        .linkTo("rdbms", "graph", "objectstore")
        .id("rdbms").adapter("RDBMS").linkTo("pubsub")   // node id, adapter name, outgoing links
        .id("graph").adapter("Graph").linkTo("pubsub")
        .id("objectstore").adapter("ObjectStore").linkTo("pubsub")
        .id("pubsub").adapter("PubSub").linkToEnd()
        .end();                                          // creates E, builds and validates the graph
```

Names and ids:

- The saga name may be any string, but not `null`. The saga logs store it as it is, and `saga-serialization` escapes
  it in JSON. (Up to 2.1.0 it could not contain `{` or a newline, a rule left over from the file log's old format.)
- Node ids and adapter names are identifiers: visible characters only (`\p{Graph}`: ASCII letters, digits and
  punctuation), no spaces or control characters, and not `null`.
- Node ids must be unique (`Duplicate id: ...`); `S` and `E` are taken.
- A node links to another node at most once (`Duplicate link from node(a) to node(b)`).
- A `null` saga name, id or adapter throws `SagaException`, as a `null` link does.

The builder alternates between three steps: `id(...)` names a node, `adapter(...)` sets its adapter, and `linkTo(...)`
or `linkToEnd()` sets its outgoing links. Nodes can link to nodes defined later.

`end()` throws `SagaException` when:

- a link points to a node id that was never defined (`Missing node(x), linked-to by node(y).`),
- the graph has a cycle or a node links to itself (`Saga must be a Directed Acyclic Graph (DAG)...`),
- a node cannot be reached from `S` (`Unreachables nodes: ...`).

## Reading a saga

- `getStartNode()`, `getEndNode()`, `nodes()`.
- `depthFirstPreOrderFullTraversal(visit)` visits every node from `S` forwards; `reverseDepthFirstPreOrderFullTraversal`
  from `E` backwards. The visitor receives the set of ancestor ids on the current path and the node. Each node is
  visited once, on the first path that reaches it.
- `SagaNode.incoming` and `SagaNode.outgoing` are the node's neighbours, in declaration order. `SagaNode` equality is
  by `id`.
- `Saga.toString()` gives the name and the number of nodes; `SagaNode.toString()` the id and the adapter.

## Behaviour to know

- A node may have no path to `E` (a dead end). `end()` accepts it, but execution does not wait for such a branch:
  the saga can finish while the branch still runs. Link every branch to the end.
- A built saga is immutable and safe to share between threads: one instance serves every concurrent execution.
  `SagaNode.incoming` and `outgoing` are unmodifiable; a write throws `UnsupportedOperationException`.
- Validation and traversals are recursive; a very deep or very large graph (tens of thousands of nodes) can cause a
  `StackOverflowError`.
