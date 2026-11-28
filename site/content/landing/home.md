---
title: "Distributed Saga"
description: >-
  Java libraries that run sagas: a graph of steps, each with a compensating action, recorded in a durable saga log so
  a saga that fails is rolled back and one cut off by a crash is continued. 2.2.0 is released.
eyebrow: "Distributed Saga 2.2.0 is released"
headline: "Operations across many systems that finish, or undo, even after a crash"
subline: >-
  Split a long operation into steps, each with an action and a compensating action. Distributed Saga runs the steps as
  a graph, in parallel where it can, and logs every step before and after it runs. A step that aborts rolls the saga
  back; a saga cut off by a crash is continued where it stopped.
primary-label: "Get started"
primary-href: "/docs/using/"
secondary-label: "Read the concepts"
secondary-href: "/docs/concepts/"
---

## The problem

An order reserves stock in one system, charges a card in another and books a shipment in a third. No single
transaction covers all three. If the payment is declined after the stock is reserved, or the process stops halfway,
something has to remember what was done and either finish the rest or undo it.

## How a saga solves it

A saga splits the operation into steps. Each step has an action and a compensating action that undoes it. Every step
is written to a durable saga log before and after it runs, so the log always says how far the saga got. There is no
distributed transaction and no lock across the systems the steps call.
[The saga pattern](/docs/concepts/#the-saga-pattern)

## Steps in a graph, run in parallel

A saga is a directed acyclic graph that starts at `S` and ends at `E`. A step runs once every step it depends on is
done, so independent branches run at the same time and a join waits for all of them. A waiting join holds no thread:
the last branch to finish starts it.
[Concepts: Saga](/docs/concepts/#saga)

<!-- label: Reserve stock and charge the card in parallel, then ship -->
```java
Saga saga = Saga.start("Order")
        .linkTo("reserve-stock", "charge-card")
        .id("reserve-stock").adapter("Stock").linkTo("ship")
        .id("charge-card").adapter("Payment").linkTo("ship")
        .id("ship").adapter("Shipping").linkToEnd()
        .end();
```

## Every step is logged before and after it runs

`Start` is written before an action and `End` after it, with the action's output. When a process stops in the middle
of a saga, the log holds an execution without `End` on `E`. Run it again with `recovery = true`: a step whose last
entry is `End` is skipped and its logged output passed on, and every other step runs. Actions run at least once, so
make them idempotent.
[Recovery](/docs/concepts/#recovery)

## Undo with compensating actions

An action that must fail the whole saga throws `AbortSagaException`. The saga writes `Abort`, stops going forward
and walks back: every step that started, and has a compensating action, is undone between `CompStart` and `Comp`, a
step only after the steps that depend on it. The caller gets the `AbortSagaException` once the rollback is in the log.
[Rollback](/docs/concepts/#rollback)

## Choose where the saga log lives

One service-provider interface, three saga logs. Each log has one writer at a time: a pool hands out the logs of
this instance, and connects to any log of the cluster for recovery.
[Deployment models](/docs/deployment-models/)

### In memory

In `sagalog-api`, with nothing to set up. For tests, development, and sagas whose loss on a crash is acceptable.

### A file

`sagalog-file` keeps each log in a file on a local disk, held with an operating-system lock. The same instance
recovers its sagas after a restart.

### PostgreSQL

`sagalog-postgres` for several instances. An advisory lock and an owner token keep one writer per log, and any
instance can recover the logs of an instance that is gone.

## A library inside your application

Distributed Saga starts no threads, opens no ports and runs no process of its own. There is no coordinator service.
[Architecture](/docs/architecture/)

### Your threads

Sagas run on a `SelectableThreadPoolExecutor` that you create and size. A saga's steps block on that pool, never on
the common pool.

### Small and modular

The core modules have no third-party dependencies. Every module is a named Java module, and the library modules can
be linked with jlink.

### Java 17

Built and tested on Java 17 and later, and published to Cantara's Maven repository under the Apache License 2.0.

## Add it to your project

`saga-execution` brings the saga model, the saga-log interfaces with the memory log, and the thread pool. Add
`sagalog-file` or `sagalog-postgres` for a durable log.
[Dependencies](/docs/using/#dependencies)

<!-- label: pom.xml -->
```xml
<repositories>
    <repository>
        <id>cantara-releases</id>
        <url>https://mvnrepo.cantara.no/content/repositories/releases/</url>
    </repository>
</repositories>

<dependency>
    <groupId>no.cantara.saga</groupId>
    <artifactId>saga-execution</artifactId>
    <version>2.2.0</version>
</dependency>
```

## Run a saga

Register an adapter for each kind of step, then run the saga on your pool and saga log. The handoff future completes
once the saga is safely started, the completion future once it is finished.
[Using the library](/docs/using/)

<!-- label: An action and its compensating action -->
```java
AdapterLoader adapters = new AdapterLoader()
        .register(new Adapter<>(String.class, "Stock",
                (input, dependeesOutput) -> "reservation-42",
                (input, reservation) -> System.out.println("release " + reservation)));
```

<!-- label: Run it and wait -->
```java
SagaHandoffControl control = new SagaExecution(sagaLog, pool, saga, adapters)
        .executeSaga(executionId, input, false, result -> { });
SagaHandoffResult result = control.getCompletionFuture().join();
```
