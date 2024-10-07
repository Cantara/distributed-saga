![GitHub tag (latest SemVer)](https://img.shields.io/github/v/tag/Cantara/distributed-saga) ![Build Status](https://jenkins.cantara.no/buildStatus/icon?job=Cantara%20Distributed-Saga) ![GitHub commit activity](https://img.shields.io/github/commit-activity/m/Cantara/distributed-saga?foo=bar) [![Project Status: Active – The project has reached a stable, usable state and is being actively developed.](http://www.repostatus.org/badges/latest/active.svg)](http://www.repostatus.org/#active) [![Known Vulnerabilities](https://snyk.io/test/github/Cantara/distributed-saga/badge.svg)](https://snyk.io/test/github/Cantara/distributed-saga)


# Distributed Saga library and samples

This is a collection of java libraries that constitute an implementation of the distributed-saga pattern.
The libraries can be used to build distributed systems that have well-defined fault-tolerance and recovery mechanisms. The polyglot-persistence sample application under the saga-samples module
demonstrate the full use of all the distributed-saga libraries and is a good place to start.

One goal of this project is a reusable and clean Java 11 library implementation of the distributed-saga
pattern with zero mandatory dependencies. The only external dependency is added by the saga-serialization
library to org.json, but this library is so small that it can literally be rewritten within minutes to fit
any dependency requirements. Any other dependencies that is used during the build and test phase is there
for exactly that, building and testing, the libraries themselves will not have any transitive dependencies
with the one saga-serialization library exception. Even the build and test dependencies are intentionally
kept very small.


### future-selector

future-selector is a library and a pure java.util.concurrent extension that adds a future-selector on
selectable-futures when used with the selectable-thread-pool-executor. This can replace entirely the
use of the basic Java executor-service and future framework to allow selector semantics on futures.

This library is useful when we want to build multi-threaded parallel algorithms with as many as needed
but as few as possible simultaneous threads while maintaining ability to serve early finishers first and
to fail-fast.

The future-selector library depends only on java.base and will not add any transitive dependencies.


### saga-api

saga-api is a library that provides an easy-to-use flow based builder API to create sagas.

A saga is represented as a directed-acyclic-graph which when used with the algorithm in the saga-execution
library will allow the distributed-saga pattern to be used on a runtime defined saga.

This library defines the shared information model of a saga and will be a dependee of all other saga-related
libraries or services that use these or even clients that only create sagas without executing them. See the
polyglot-persistence sample for an example of its use.

The saga-api library depends only on java.base and will not add any transitive dependencies.


### saga-execution

saga-execution is a library that provides a general implementation of the distributed saga pattern and
specifically a parallel multithreaded execution algorithm.

The saga-execution algorithm will integrate with the following parts:
1. The saga. Represented by the Saga class in saga-api which represents the saga as a directed-acyclic-graph.
2. Input data. This is the data that will be passed to adapters as the different actions of the saga is
   executed.
3. Saga-log. This should be a scalable, consistent and fault-tolerant log implementation. Apache DistributedLog
   is an example of such a technology and may be a good fit. This project provides sagalog implementations for testing
   with an in-memory implementation, for non-fault-tolerant cases using file-system, and a postgres implementation that
   can be used for clustering.
5. Adapters. These are implementations of actions and corresponding compensating actions that integrate with
   the relevant technology. All adapters that are named when creating a saga must be registered with the AdapterLoader
   in use for a given execution. See the polyglot-persistence sample for an example of this.

The algorithm will traverse a given saga's directed-acyclic-graph in a multithreaded manner allowing an
optimal action/compensating-action execution order within the boundaries set by the pattern. Parallelism
is added when a node in a saga's directed-acyclic-graph has more than one outgoing link to another node.
The algorithm ensures that execution of actions from all incoming links are complete before the action of
a node is executed, thus maintaining legal action execution ordering while allowing parallelism.

This is the core algorithm and can be used in a saga-execution-coordinator implementation. This can either
be used by a service for a saga that is specific to that service (see the polyglot-persistence sample), or
it can be used in a general coordinator implementation that will handle any saga for which the coordinator
has adapter implementations.

The saga-execution library depends only on java.base, future-selector, and saga-api and will not add any
other transitive dependencies.


### saga-serialization

saga-serialization is a library that provides classes for serialization and deserialization of a saga.

Serialization is typically needed when the saga-execution-coordinator is implemented as a separate
service, where the saga will be transferred through e.g. http to the coordinator service. Saga serialization
is also useful for logging or debugging, see the polyglot-persistence sample for an example of this use.

Because the saga-serialization library uses a specific json library, it is supplied as a separate library
and can easily be re-written to use any json library that fits other requirements or if there is need to use
another serialization format than json to represent a saga.

The saga-serialization library depends only on java.base, saga-api, and org.json library and will not add any
other transitive dependencies.


### polyglot-persistence

polyglot-persistence is a sample microservice application that demonstrates the use of all the saga libraries.

It starts a minimal Undertow web-server that will accept basically any data, create a saga and execute that saga
and then respond with a json containing the saga-log entries and the serialized saga. The saga execution will
write the data to three mocked databases (RDBMS, Graph, ObjectStore) in parallel, then when they all complete
will write the data to a mocked publish-subscribe topic.

To test the polyglot-persistence sample either run the one and only testng integration-test or do the following
to start the app and send data to it:
1. `$ cd <distributed-saga-project-root>` # the folder where this README file is located
1. `$ mvn clean install` # build all modules
1. `$ java -jar saga-samples/polyglot-persistence/target/polyglot-persistence.jar` # start the application
1. `$ curl -X PUT http://127.0.0.1:8139/any/resource/path -d 'Hello Saga!' | jq .` # the `| jq .` part is optional
   for pretty-printing of json


# Development environment notes

The project has been tested to work with maven 3.5.2 and Zulu for Java 11 (OpenJDK 11-ea) from Azul Systems.


# Related work

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
