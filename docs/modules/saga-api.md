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
| `SagaException` | Thrown when a saga is invalid. |

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

- The saga name must not contain `{` or a newline.
- Node ids and adapter names must consist of visible characters only: no spaces or control characters.
- Node ids must be unique (`Duplicate id: ...`); `S` and `E` are taken.

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
- `SagaNode.incoming` and `SagaNode.outgoing` are the node's neighbours. `SagaNode` equality is by `id`.

## Behaviour to know

- A node may have no path to `E` (a dead end). `end()` accepts it, but execution does not wait for such a branch:
  the saga can finish while the branch still runs. Link every branch to the end.
- `SagaNode.incoming` and `outgoing` are public, mutable collections. Do not change them after `end()`.
- Validation and traversals are recursive; a very deep or very large graph (tens of thousands of nodes) can cause a
  `StackOverflowError`.
