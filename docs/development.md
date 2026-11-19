# Development

## Requirements

- JDK 17 or later (the code is compiled with `--release 17`; it is built and tested on newer JDKs too).
- Maven 3.6.3 or later.
- No database or service: the PostgreSQL tests start an embedded PostgreSQL (zonky `embedded-postgres`, PostgreSQL 18
  binaries from Maven).

## Build and test

```sh
mvn clean install            # build, test and install every module
mvn clean verify -pl sagalog-postgres -am    # one module and what it needs
mvn -o clean verify          # offline, once the dependencies are in ~/.m2
```

Every module's tests use TestNG and run in `mvn verify`. `sagalog-postgres`'s database tests are in the TestNG group
`integration`, and run in the normal build.

## Layout

```
pom.xml                      the reactor: modules, versions, plugins, repositories
saga-parent/                 pom-only parent of the library modules
future-selector/             selectable futures and the thread pool
saga-api/                    the saga model
saga-execution/              runs sagas
saga-serialization/          sagas to and from JSON
sagalog-api/                 the saga-log SPI, the pool, the memory log
sagalog-file/                the file saga log (with a copy of Tape's QueueFile)
sagalog-postgres/            the PostgreSQL saga log
saga-samples/                sample applications (not deployed)
docs/                        this documentation
CHANGELOG.md, NOTICE, LICENSE
```

Module dependencies use `${project.version}`, so the reactor builds them together and an IDE that opens the root
`pom.xml` links the modules as sources.

## Conventions

- **Dependencies.** The library modules have as few third-party dependencies as possible: `future-selector`,
  `saga-api`, `sagalog-api`, `saga-execution` and `sagalog-file` have none. Versions are managed in the root or the
  module's pom; Renovate (`renovate.json`, Cantara's shared configuration) proposes updates.
- **Java modules.** Every library module has a `module-info.java`. A type from another module in a public signature
  needs `requires transitive`. Tests run inside the module (patched in), so a test needs only what the module reads.
- **Providers.** A new saga-log provider registers its `SagaLogInitializer` in both `module-info.java` and
  `META-INF/services` (see [sagalog-api](modules/sagalog-api.md#writing-a-provider)).
- **Compatibility.** The library is used by applications other than those in this repository. A change of behaviour,
  of the public API, of a stored format or of a database schema goes in [CHANGELOG.md](../CHANGELOG.md), with what a
  user must do to migrate.
- **Third-party code.** Code copied from other projects keeps its copyright header, lists its changes at the top of the
  file, and is attributed in [NOTICE](../NOTICE).

## Releases

The libraries are released to Cantara's Maven repository, not to Maven Central:

- releases: `https://mvnrepo.cantara.no/content/repositories/releases/`
- snapshots: `https://mvnrepo.cantara.no/content/repositories/snapshots/`

Releases use the Maven Release Plugin (`autoVersionSubmodules`, tag `v<version>`, profile `release`, which attaches
source and Javadoc jars). Deploying needs credentials for the server id `cantara` in the Maven `settings.xml`.
