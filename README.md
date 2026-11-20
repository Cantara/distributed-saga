![GitHub tag (latest SemVer)](https://img.shields.io/github/v/tag/Cantara/distributed-saga) ![Build Status](https://jenkins.cantara.no/buildStatus/icon?job=Cantara%20Distributed-Saga) ![GitHub commit activity](https://img.shields.io/github/commit-activity/m/Cantara/distributed-saga?foo=bar) [![Project Status: Active – The project has reached a stable, usable state and is being actively developed.](http://www.repostatus.org/badges/latest/active.svg)](http://www.repostatus.org/#active) [![Known Vulnerabilities](https://snyk.io/test/github/Cantara/distributed-saga/badge.svg)](https://snyk.io/test/github/Cantara/distributed-saga) [![Quality gate status](https://sonarcloud.io/api/project_badges/measure?project=Cantara_distributed-saga&metric=alert_status)](https://sonarcloud.io/summary/new_code?id=Cantara_distributed-saga) [![Reliability rating](https://sonarcloud.io/api/project_badges/measure?project=Cantara_distributed-saga&metric=reliability_rating)](https://sonarcloud.io/summary/new_code?id=Cantara_distributed-saga)

# Distributed Saga

Java libraries that implement the [distributed saga pattern](docs/concepts.md#the-saga-pattern): a long-lived
operation is a directed acyclic graph of actions, each with a compensating action, run in parallel where the graph
allows. Every step is recorded in a saga log, so a saga that fails is rolled back and a saga interrupted by a crash
can be continued.

- Define a saga with a fluent builder; branches run in parallel, joins wait for all their branches.
- Write *adapters*: an action, a compensating action and a serializer for each kind of step.
- Pick a saga log: in memory, in a file, or in PostgreSQL for several application instances.
- Small: the core modules have no third-party dependencies. Java 17, and every module is a named Java module.

## Add it to your project

The libraries are published to Cantara's Maven repository, not to Maven Central:

```xml
<repositories>
    <repository>
        <id>cantara-releases</id>
        <url>https://mvnrepo.cantara.no/content/repositories/releases/</url>
    </repository>
</repositories>

<dependencies>
    <dependency>
        <groupId>no.cantara.saga</groupId>
        <artifactId>saga-execution</artifactId>       <!-- brings saga-api, sagalog-api and future-selector -->
        <version>2.1.2</version>
    </dependency>
    <dependency>
        <groupId>no.cantara.saga</groupId>
        <artifactId>sagalog-postgres</artifactId>     <!-- or sagalog-file; the memory log is in sagalog-api -->
        <version>2.1.2</version>
    </dependency>
</dependencies>
```

The latest release is 2.1.2 (Java 17; the library modules can be linked with jlink). If you are upgrading from 2.1.0,
2.0.0 or 1.0.x, the [CHANGELOG](CHANGELOG.md) lists what changed and how to migrate; 2.1.0 and 2.1.1 have breaking
changes. Upgrade every instance of an application together: 2.1.1 and later must not run side by side with 2.1.0.

| Artifact | What it is |
|---|---|
| `no.cantara.saga:saga-api` | The saga model and builder |
| `no.cantara.saga:saga-execution` | Runs sagas |
| `no.cantara.saga:saga-serialization` | Sagas to and from JSON |
| `no.cantara.saga:sagalog-api` | The saga-log interfaces and the memory log |
| `no.cantara.saga:sagalog-file` | The file saga log |
| `no.cantara.saga:sagalog-postgres` | The PostgreSQL saga log |
| `no.cantara.concurrent.futureselector:future-selector` | Selectable futures and the thread pool the execution runs on |

## Example

```java
// 1. The saga: reserve stock and charge the card in parallel, then ship.
Saga saga = Saga.start("Order")
        .linkTo("reserve-stock", "charge-card")
        .id("reserve-stock").adapter("Stock").linkTo("ship")
        .id("charge-card").adapter("Payment").linkTo("ship")
        .id("ship").adapter("Shipping").linkToEnd()
        .end();

// 2. The adapters: an action and a compensating action per step.
AdapterLoader adapters = new AdapterLoader()
        .register(new Adapter<>(String.class, "Stock",
                (input, dependeesOutput) -> "reservation-42",
                (input, reservation) -> System.out.println("release " + reservation)))
        .register(new Adapter<>(String.class, "Payment",
                (input, dependeesOutput) -> "payment-7",
                (input, payment) -> System.out.println("refund " + payment)))
        .register(new Adapter<>(String.class, "Shipping",
                (input, dependeesOutput) -> "shipment-1"));

// 3. A thread pool and a saga log.
SelectableThreadPoolExecutor pool = new SelectableThreadPoolExecutor(
        10, 10, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
SagaLogPool logPool = ServiceLoader.load(SagaLogInitializer.class).stream()
        .filter(p -> p.type().getName().equals("no.cantara.sagalog.memory.MemorySagaLogInitializer"))
        .findFirst().orElseThrow().get()
        .initialize(Map.of("cluster.instance-id", "01"));
SagaLogId logId = logPool.registerInstanceLocalIdFor("log-1");
SagaLogOwner owner = new SagaLogOwner("main");
SagaLog sagaLog = logPool.tryTakeOwnership(owner, logId);

// 4. Run it, clean the log when it is done, and wait.
SagaHandoffControl control = new SagaExecution(sagaLog, pool, saga, adapters)
        .executeSaga(UUID.randomUUID().toString(), "{\"order\":1}", false, result -> sagaLog.truncate().join());
SagaHandoffResult result = control.getCompletionFuture().join();
System.out.println("success: " + result.isSuccess());   // success: true
logPool.releaseOwnership(owner, logId);
```

An action that throws `AbortSagaException` rolls the saga back: the compensating actions of the steps that completed
run, and the completion future fails. [Using the library](docs/using.md) covers rollback, recovery after a crash,
thread pools and the saga-log choices step by step.

## Build

Requires JDK 17 or later and Maven 3.6.3 or later. No database is needed: the PostgreSQL tests start an embedded one.

```sh
git clone https://github.com/Cantara/distributed-saga.git
cd distributed-saga
mvn clean install
```

Try the sample, an HTTP server that runs a saga per request:

```sh
java -jar saga-samples/polyglot-persistence/target/polyglot-persistence-*-SNAPSHOT.jar
curl -X PUT http://127.0.0.1:8139/any/resource/path -d 'Hello Saga!' | jq .
```

## Documentation

The developer documentation is in [docs/](docs/README.md):

- [Concepts](docs/concepts.md): sagas, adapters, the saga log, execution, rollback, recovery, guarantees
- [Architecture](docs/architecture.md): modules, Java modules, threading, saga-log providers
- [Using the library](docs/using.md): a step-by-step guide
- [Known limitations](docs/limitations.md): read before you go to production
- Modules: [future-selector](docs/modules/future-selector.md), [saga-api](docs/modules/saga-api.md),
  [saga-execution](docs/modules/saga-execution.md), [saga-serialization](docs/modules/saga-serialization.md),
  [sagalog-api](docs/modules/sagalog-api.md), [sagalog-file](docs/modules/sagalog-file.md),
  [sagalog-postgres](docs/modules/sagalog-postgres.md), [saga-samples](docs/modules/saga-samples.md)
- [Development](docs/development.md): building, conventions, releases

## Related work

The notion of a saga as a way to solve problems with long-lived
transactions was first published in 1987:
http://www.cs.cornell.edu/andru/cs711/2002fa/reading/sagas.pdf

A paper on Distributed Sagas which generalized sagas for distributed
systems with a proposed algorithm was published in 2015:
https://github.com/aphyr/dist-sagas/blob/master/sagas.pdf

Caitie McCaffrey has some very educational presentations on distributed sagas, here's one:
https://speakerdeck.com/caitiem20/distributed-sagas-a-protocol-for-coordinating-microservices

Chris Richardson has a web-page
https://microservices.io/patterns/data/saga.html

Camunda is doing something similar, here is a blog post
https://blog.bernd-ruecker.com/saga-how-to-implement-complex-business-transactions-without-two-phase-commit-e00aa41a1b1b

Pat Helland (Amazon) has written the "Life beyond Distributed Transactions" paper
http://adrianmarriott.net/logosroot/papers/LifeBeyondTxns.pdf

## Acknowledgements

The file saga log (`sagalog-file`) stores its entries with `QueueFile`, a modified copy of the class of the same
name from [Tape](https://github.com/square/tape) 2.0.0-beta1 by Square, Inc., licensed under the Apache License,
Version 2.0. See [NOTICE](NOTICE).

## License

Apache License, Version 2.0. See [LICENSE](LICENSE).

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for the changes in each version and how to migrate.
