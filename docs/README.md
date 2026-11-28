---
title: "Distributed Saga"
description: "Small Java libraries that run sagas: a graph of actions, each with a compensating action, recorded in a durable saga log so a failed saga can be rolled back and an interrupted one continued."
order: 0
---

# Distributed Saga: developer documentation

Distributed Saga is a set of small Java libraries that run a *saga*: a directed acyclic graph of actions in which
every action has a compensating action. Each step is recorded in a *saga log*, so that a failed saga can be rolled
back and a saga interrupted by a crash can be continued.

These pages are for developers who use the libraries or work on them. They are also published as a site at
<https://cantara.github.io/distributed-saga/docs/>. To get started, see the
[README](https://github.com/Cantara/distributed-saga/blob/main/README.md) at the root of the repository.

## Contents

| Page | What it covers |
|---|---|
| [Concepts](concepts.md) | Sagas, nodes, adapters, the saga log and its entries, execution, rollback, recovery, and what the library guarantees |
| [Architecture](architecture.md) | The modules and how they depend on each other, Java modules (JPMS), threading, the life of one saga execution, and service loading of saga-log providers |
| [Topology](topology.md) | How the parts connect: modules, the objects in one application instance, the shape of a saga, and instances sharing saga logs in a cluster |
| [Design decisions](design-decisions.md) | Why actions run at least once, why steps block on the saga's own pool, and why the saga log is synchronous |
| [Using the library](using.md) | A step-by-step guide: define a saga, write adapters, choose a saga log, run, wait, recover, handle errors |
| [Deployment models](deployment-models.md) | In memory, one instance with a file log, or several instances on PostgreSQL: what each needs, sizing, start-up, shutdown, packaging and upgrades |
| [Known limitations](limitations.md) | Behaviour you must know about before you rely on the library in production |
| [API reference](api.md) | Every public type, by task, and the exceptions |
| [Protocols](protocols.md) | The saga-log entries and how recovery and rollback read them, the two futures, the traversal, and log ownership within and across processes |
| [Sequence flows](sequence-flows.md) | Diagrams of a saga that succeeds, a fork and join, an abort, a failure, recovery, taking a log, a lost connection, and cross-instance recovery |
| [Development](development.md) | Building, testing, project layout, conventions and releases |
| [Glossary](glossary.md) | The terms of this documentation, each with one meaning, and the name the code gives it |

### Modules

| Module | Artifact | Java module | Page |
|---|---|---|---|
| Future selector | `no.cantara.concurrent.futureselector:future-selector` | `no.cantara.concurrent.futureselector` | [future-selector](modules/future-selector.md) |
| Saga API | `no.cantara.saga:saga-api` | `no.cantara.saga.api` | [saga-api](modules/saga-api.md) |
| Saga execution | `no.cantara.saga:saga-execution` | `no.cantara.saga.execution` | [saga-execution](modules/saga-execution.md) |
| Saga serialization | `no.cantara.saga:saga-serialization` | `no.cantara.saga.serialization` | [saga-serialization](modules/saga-serialization.md) |
| Saga log API (with the memory log) | `no.cantara.saga:sagalog-api` | `no.cantara.sagalog` | [sagalog-api](modules/sagalog-api.md) |
| File saga log | `no.cantara.saga:sagalog-file` | `no.cantara.sagalog.file` | [sagalog-file](modules/sagalog-file.md) |
| PostgreSQL saga log | `no.cantara.saga:sagalog-postgres` | `no.cantara.sagalog.postgres` | [sagalog-postgres](modules/sagalog-postgres.md) |
| Samples | `no.cantara.saga.samples:polyglot-persistence` | `no.cantara.saga.samples.polyglot` | [saga-samples](modules/saga-samples.md) |

Changes between versions, and what users must do to migrate, are in the [CHANGELOG](https://github.com/Cantara/distributed-saga/blob/main/CHANGELOG.md).
