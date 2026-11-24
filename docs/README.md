# Distributed Saga: developer documentation

Distributed Saga is a set of small Java libraries that run a *saga*: a directed acyclic graph of actions in which
every action has a compensating action. Each step is recorded in a *saga log*, so that a failed saga can be rolled
back and a saga interrupted by a crash can be continued.

These pages are for developers who use the libraries or work on them. To get started, see the
[README](../README.md) at the root of the repository.

## Contents

| Page | What it covers |
|---|---|
| [Concepts](concepts.md) | Sagas, nodes, adapters, the saga log and its entries, execution, rollback, recovery, and what the library guarantees |
| [Architecture](architecture.md) | The modules and how they depend on each other, Java modules (JPMS), threading, the life of one saga execution, and service loading of saga-log providers |
| [Using the library](using.md) | A step-by-step guide: define a saga, write adapters, choose a saga log, run, wait, recover, handle errors |
| [Known limitations](limitations.md) | Behaviour you must know about before you rely on the library in production |
| [Design decisions](design-decisions.md) | Why actions run at least once, why steps block on the saga's own pool, and why the saga log is synchronous |
| [Development](development.md) | Building, testing, project layout, conventions and releases |

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

Changes between versions, and what users must do to migrate, are in the [CHANGELOG](../CHANGELOG.md).
