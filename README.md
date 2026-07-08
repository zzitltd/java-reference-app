# reference-app

The **ZZ-IT reference application** — a Spring Boot 4 / Java 25 service that exists to be *copied
from*. The business code is deliberately tiny (an object-storage round-trip behind a small port, and
a minimal database example). The real product is the **build, quality, and supply-chain setup**: the
toolchain pins, the enforced gates, the test split, the profiles, the logging, the SBOM, and the
container image. Other fleet services (and the AI agents working on them) should converge to what is
documented here.

If you are an **AI agent** asked to bring another project in line with this one, read
[`.claude/skills/converge-to-reference-app/SKILL.md`](.claude/skills/converge-to-reference-app/SKILL.md).

---

## Table of contents

- [Quick command reference](#quick-command-reference)
- [Prerequisites](#prerequisites)
- [What we set up, and why](#what-we-set-up-and-why)
  - [1. JVM: Java 25, and a ban on the commercial Oracle JDK](#1-jvm-java-25-and-a-ban-on-the-commercial-oracle-jdk)
  - [2. Maven 3.9.16, pinned](#2-maven-3916-pinned)
  - [3. The enforcer gate](#3-the-enforcer-gate)
  - [4. POM hygiene (tidy)](#4-pom-hygiene-tidy)
  - [5. Versioning from git + reproducible builds](#5-versioning-from-git--reproducible-builds)
  - [6. Build metadata for the actuator](#6-build-metadata-for-the-actuator)
  - [7. Dependencies via BOMs](#7-dependencies-via-boms)
  - [8. Web on Jetty; actuator, probes and metrics on a side port](#8-web-on-jetty-actuator-probes-and-metrics-on-a-side-port)
  - [9. Code formatting gate (Spotless)](#9-code-formatting-gate-spotless)
  - [10. The unit / integration test split](#10-the-unit--integration-test-split)
  - [11. Optional multi-cloud: the ObjectStorage port (+ retries)](#11-optional-multi-cloud-the-objectstorage-port--retries)
  - [12. Optional database: platform-provisioned roles, routed pools, Liquibase](#12-optional-database-platform-provisioned-roles-routed-pools-liquibase)
  - [13. Optional Kubernetes API: leader election over a Lease](#13-optional-kubernetes-api-leader-election-over-a-lease)
  - [14. Optional Kafka: native clients, protobuf contract, MSK via config](#14-optional-kafka-native-clients-protobuf-contract-msk-via-config)
  - [15. The `local-*` profiles](#15-the-local--profiles)
  - [16. Logging](#16-logging)
  - [17. Graceful shutdown](#17-graceful-shutdown)
  - [18. The `ci-reports` profile (non-blocking)](#18-the-ci-reports-profile-non-blocking)
  - [19. The `ci-gates` profile (blocking gates)](#19-the-ci-gates-profile-blocking-gates)
  - [20. SBOM + vulnerability scanning](#20-sbom--vulnerability-scanning)
  - [21. The container image](#21-the-container-image)
- [Environment variables](#environment-variables)
- [Build pipeline matrix](#build-pipeline-matrix)
- [Project layout](#project-layout)
- [Conventions for new code](#conventions-for-new-code)

---

## Quick command reference

Use the wrapper (`./mvnw`) — it pins Maven 3.9.16 to match the enforcer. On Windows use `mvnw.cmd`.

| You want to… | Command |
|---|---|
| Fast inner loop: compile + **unit** tests | `./mvnw test` |
| Full local build: + **integration** tests + all default gates | `./mvnw verify` |
| Auto-fix code formatting | `./mvnw spotless:apply` |
| Auto-fix `pom.xml` formatting/order | `./mvnw tidy:pom` |
| Generate **non-blocking** reports (coverage, license, SpotBugs; +scans at `verify`) | `./mvnw verify -Pci-reports` |
| **Emulate the CI gate** (everything blocking) | `./mvnw verify -Dci-gates` |
| Run with **no cloud, no DB** at all | `./mvnw spring-boot:run` |
| Run on **AWS** locally (Floci emulator auto-started) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-aws` |
| Run on **Azure** locally (Azurite emulator auto-started) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-azure` |
| Run with **PostgreSQL** locally (auto-started, migrated) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-db` |
| Run with **Kubernetes** locally (k3s auto-started) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-k8s` |
| Run with **Kafka** locally (broker auto-started) | `./mvnw spring-boot:run -Dspring-boot.run.profiles=local-kafka` |
| Run the packaged jar | `java -jar target/reference-app-*.jar` |
| Switch console logs to JSON | add `--logging.json.enabled=true` |
| Build the image from a host-built jar | `docker build --target app-prebuilt -t reference-app .` |
| Build the image incl. the jar (no local JDK needed) | `docker build --target app -t reference-app .` |

Generated artifacts after `-Pci-reports` land under `target/`: `site/jacoco-{unit,integration,merged}/`
(coverage XML/CSV), `trivy-report.json`, `grype-report.json`, `license/THIRD-PARTY.txt`,
`spotbugsXml.xml`, and the SBOM at `classes/META-INF/sbom/application.cdx.json`.

---

## Prerequisites

- **A Java 25 JDK** — any OpenJDK build (Temurin, Corretto, Zulu, Microsoft, Oracle *OpenJDK*, …).
  The *commercial* Oracle JDK is rejected by the build (see [below](#1-jvm-java-25-and-a-ban-on-the-commercial-oracle-jdk)).
- **Maven 3.9.16** — already pinned by `./mvnw` and `mvnvm.properties`; you don't need a system Maven.
- **Docker** — required for the integration tests, the `local-*` profiles, and the image build.
- **Trivy** and **Grype** — only needed to run the vulnerability scans (`-Pci-reports`/`-Pci-gates`). Without
  them, `-Pci-reports` just prints a note; `-Pci-gates` *fails* (CI images must have them installed).

---

## What we set up, and why

### 1. JVM: Java 25, and a ban on the commercial Oracle JDK

`java.version` is **25** (the current LTS). We don't just *target* 25, we *enforce* the build JDK is
≥ 25 so nobody compiles on an older one.

We also **ban the commercial (paid) Oracle JDK** while still allowing every free OpenJDK build —
including Oracle's *own* OpenJDK. The two are indistinguishable by `java.vendor` (both say "Oracle
Corporation"), so the enforcer checks `java.vm.name`: the paid JDK is the one branded
`Java HotSpot(TM)`; every OpenJDK reports `OpenJDK ... Server VM`. The rule lives in the enforcer's
`evaluateBeanshell` (see [§3](#3-the-enforcer-gate)). OS/architecture are intentionally *not*
constrained.

### 2. Maven 3.9.16, pinned

Maven has **no LTS line** — the guidance is "use the latest stable 3.9.x". We pin it in three places
that must agree:

- **`mvnvm.properties`** (`mvn_version=3.9.16`) — for the [mvnvm](https://mvnvm.org/) version manager.
- **`.mvn/wrapper/maven-wrapper.properties`** — so `./mvnw` downloads exactly 3.9.16.
- **`<maven.version>` + enforcer `requireMavenVersion`** — so any *other* Maven fails the build early.

### 3. The enforcer gate

`maven-enforcer-plugin` (+ `extra-enforcer-rules`) runs at `validate` — the very first phase — so a
misconfigured environment fails in seconds, before compilation. The rules:

| Rule | What it does |
|---|---|
| `requireJavaVersion` | Build JDK ≥ `${java.version}` (25). |
| `evaluateBeanshell` | Bans the commercial Oracle JDK (see [§1](#1-jvm-java-25-and-a-ban-on-the-commercial-oracle-jdk)). |
| `requireMavenVersion` | Maven ≥ `${maven.version}` (3.9.16). |
| `requirePluginVersions` | Every plugin must be pinned — no `LATEST`/`RELEASE`/`SNAPSHOT`. (`maven-site-plugin` is excused; its version comes from Maven's defaults, not our POM.) |
| `dependencyConvergence` | All transitive copies of a dependency must resolve to one version. |
| `requireReleaseDeps` | No `-SNAPSHOT` dependencies. |
| `requireEncoding` | Everything under `src/**` + `pom.xml` is UTF-8 (ASCII counts as a subset). Legacy non-UTF-8 files belong under `src/main/resources/legacy/`, which is excluded. |
| `enforceBytecodeVersion` | No dependency may ship bytecode newer than our Java target. |
| `banDuplicateClasses` | No two jars may ship the same class — catches shaded copies and overlapping "-all" jars, a real risk with two cloud SDK trees on the classpath. |

### 4. POM hygiene (tidy)

`tidy-maven-plugin`'s `check` goal runs at `validate` and **fails the build** if `pom.xml` isn't in
canonical element order/formatting. It's the POM analogue of Spotless. Fix with **`./mvnw tidy:pom`**.

### 5. Versioning from git + reproducible builds

- The POM `<version>` is a placeholder (`0.0.0-SNAPSHOT`). The **real** version is derived at build
  time by the [qoomon `maven-git-versioning-extension`](https://github.com/qoomon/maven-git-versioning-extension)
  (a *core extension* in `.mvn/extensions.xml`, configured in `.mvn/maven-git-versioning-extension.xml`):
  a `vX.Y.Z` tag → `X.Y.Z`; `main` → the next-patch `-SNAPSHOT`. CI must checkout with full history
  (`fetch-depth: 0`).
- **Reproducible builds:** `project.build.outputTimestamp` is declared in `<properties>` (the
  extension only overrides properties that *already exist*). The literal fallback is
  `1980-01-01T00:00:02Z` — the earliest timestamp a ZIP/JAR can encode, and the floor Spring Boot's
  `build-info` accepts (epoch 0 / 1970 is rejected).

### 6. Build metadata for the actuator

Two plugins feed `/actuator/info`:

- `spring-boot-maven-plugin:build-info` → `META-INF/build-info.properties` (version, build time).
- `git-commit-id-maven-plugin` → `git.properties` (commit, branch, tags, dirty flag). We declare
  **only configuration**, no execution — the Spring Boot parent already binds the `revision`
  execution, and adding our own would run it twice.

### 7. Dependencies via BOMs

Versions are managed centrally, not scattered on individual `<dependency>` entries:

- The **Spring Boot parent** manages the whole Spring/Jackson/JDBC/Liquibase/test stack.
- **Spring Cloud AWS BOM** (`spring-cloud-aws-dependencies`, 4.0.x → targets Boot 4) manages the AWS
  starters + a compatible AWS SDK v2.
- **Spring Cloud Azure BOM** (`spring-cloud-azure-dependencies`, 7.x → targets Boot 4) manages the
  Azure starters + (via the imported `azure-sdk-bom`) a compatible Azure SDK.
- **Testcontainers BOM** — not managed by the Boot 4.1 parent, so we import it ourselves; this also
  makes Floci's transitive Testcontainers deps converge (needed for `dependencyConvergence`).

Only genuinely standalone artifacts (the Floci Testcontainers module) carry an explicit `<version>`.
Where a BOM leaves a transitive gap, we add a **convergence pin** in `<dependencyManagement>`, each
documented in the `<properties>` block (currently: `msal4j` + JNA under `azure-identity`,
`commons-text` under Liquibase) — re-check them on every BOM bump.

### 8. Web on Jetty; actuator, probes and metrics on a side port

- **Jetty instead of Tomcat** — a fleet-wide convention. We exclude `spring-boot-starter-tomcat`
  from `spring-boot-starter-web` and add `spring-boot-starter-jetty`.
- **Actuator on port 6080** (`management.server.port`), separate from application traffic —
  `health`, `info`, `sbom` and `prometheus` are exposed there and never mix with business traffic.
- **Probes:** the Kubernetes-style liveness/readiness groups are enabled everywhere
  (`/actuator/health/{liveness,readiness}`), not just on Kubernetes.
- **Metrics:** `micrometer-registry-prometheus` activates **`/actuator/prometheus`** with JVM,
  HTTP-server and connection-pool metrics out of the box — point the scraper at the side port.

### 9. Code formatting gate (Spotless)

`spotless-maven-plugin` with **palantir-java-format** runs `check` at `process-sources` — i.e.
formatting is enforced *before* compilation. Palantir is intentionally non-configurable (4-space
indent, 120 columns), so there is no ruleset to bikeshed. Fix with **`./mvnw spotless:apply`**.

### 10. The unit / integration test split

| | Unit tests | Integration tests |
|---|---|---|
| Runner | `maven-surefire-plugin` | `maven-failsafe-plugin` |
| Naming | `*Test`, `*Tests` | `*IT` (e.g. `ReferenceS3IT`) |
| Runs on | `mvn test` (and up) | `mvn verify` only |
| Needs Docker? | No | Yes (Floci / Azurite / PostgreSQL / k3s via Testcontainers) |

So **`./mvnw test` is the fast inner loop** (no Docker) and **`./mvnw verify` is the full pass**.
Integration tests use Testcontainers to start real backends — Floci (LocalStack-compatible AWS) for
the S3 adapter, Azurite for the Azure Blob adapter, PostgreSQL for the database example — and prove
each works end-to-end; endpoints/credentials are injected via `@DynamicPropertySource`.

> **Mockito agent:** modern JDKs deprecate the self-attaching Java agent. `maven-dependency-plugin`'s
> `properties` goal resolves Mockito's jar path, and surefire/failsafe load it explicitly via
> `-javaagent:…` (with `-Xshare:off` to avoid the CDS warning). New projects should keep this.

### 11. Optional multi-cloud: the ObjectStorage port (+ retries)

Cloud support is **optional, and dual**: both the AWS and the Azure SDK are on the classpath, but
**both autoconfigurations are off by default** and no cloud credentials are needed to run the app.
The pattern:

- **`ObjectStorage`** (`hu.zzit.reference.storage`) is a tiny cloud-agnostic port:
  `ensureContainer` / `put` / `get`.
- **`S3ObjectStorage`** and **`AzureBlobObjectStorage`** are its adapters, each gated by
  `@ConditionalOnProperty` on **`cloud.provider`** (`aws` | `azure` | `none`, default `none`).
- The **`aws` / `azure` Spring profiles** are the single knob: each sets `cloud.provider` *and*
  enables the matching SDK autoconfiguration (`application-aws.yaml` / `application-azure.yaml`),
  so the two settings can't drift apart.

**Resilience:** external calls must expect network failure. The port carries a type-level
`@Retryable` — Spring Framework's **built-in** resilience support (`@EnableResilientMethods` on the
application class), no extra dependency — giving every adapter method 3 retries with exponential
backoff (1s → 2s → 4s, tunable via `storage.retry.*`, see the
[environment variables](#environment-variables)). This is safe **because these operations are
idempotent** — don't copy it blindly onto non-idempotent calls. `ObjectStorageRetryTest` proves the
behavior. The same package also offers `@ConcurrencyLimit` (bulkhead) when you need it.

Derived services keep the provider(s) they actually use and delete the other adapter + starter (and
its BOM import). Application code depends on the port, never on `S3Client`/`BlobServiceClient`
directly.

### 12. Optional database: platform-provisioned roles, routed pools, Liquibase

Also off by default (`db.enabled=false` — no DataSource, no migration, no DB needed to run). The
`db` profile activates the example. The division of labor:

- **Access is provisioned with the database, not by the app.** The expected layout (documented and
  locally created by `db/init/01-roles.sql`, used by the compose PostgreSQL and the integration
  test): NOLOGIN **group roles** (`<db>_owner`/`_rw`/`_ro`) that carry all permissions, a schema
  owned by the owner group, **per-application login users** with group membership only (credentials
  from a secret store), and — crucially — **default privileges**, so every object the migrating
  user creates is automatically granted to the rw/ro groups. The **changelog therefore contains
  schema objects only, no GRANTs**.
- **The app connects with two login users**:

| User | Rights | Used for |
|---|---|---|
| `…_rw_user` | DML + `CREATE` on the schema | writes **and the Liquibase migration** |
| `…_ro_user` | `SELECT` only | queries; its URL typically points at a replica |

- **Liquibase needs zero connection configuration**: Boot's autoconfiguration migrates through the
  primary (read-write) pool at startup, and the changelog sits at Boot's *default* location
  (`db/changelog/db.changelog-master.yaml`). With no DataSource beans (db example off) the
  autoconfiguration backs off entirely.
- **`spring.liquibase.enabled` is set explicitly** (to its default, `true`) because it is the knob
  that separates *migrating* from *serving*: deployments typically run the migration once from a
  pre-install/pre-upgrade hook job and set `SPRING_LIQUIBASE_ENABLED=false` in the serving pods —
  mandatory for read-only app variants, and it keeps parallel rollouts from queueing on the
  changelog lock.
- **Why a config class at all?** `spring.datasource.*` configures exactly **one** DataSource —
  Boot has no property-driven second pool and no read/write routing. `DatabaseConfiguration`
  therefore defines the two pools the Boot-documented way (`DataSourceProperties` per pool), with
  key names mirroring `spring.datasource`: `db.{readwrite,readonly}.{url,username,password}` plus
  `db.*.hikari.*` for pool tuning. Boot's single-DataSource autoconfiguration is excluded.
- **Pool routing is Spring's own**: the `@Primary` DataSource is a `LazyConnectionDataSourceProxy`
  with a `readOnlyDataSource` — application code uses **one** `JdbcTemplate` and marks read paths
  `@Transactional(readOnly = true)`, which routes them to the read-only pool (see
  `GreetingRepository`).
- **Trap worth stealing**: when docker-compose management is active, Boot creates a *service
  connection* from the compose postgres service — with the container's **superuser** credentials —
  which would silently outrank the configured pools for autoconfigured consumers. The compose
  service opts out via the `org.springframework.boot.service-connection: false` label
  (see `compose.yaml`).
- `ReferencePostgresIT` proves the behavior: the migration ran, `readOnly` transactions execute as
  the read-only user (asserted via `SELECT current_user`), and writes in a `readOnly` transaction
  fail.

### 13. Optional Kubernetes API: leader election over a Lease

Some apps need the Kubernetes API itself; the reference shows the canonical case: **leader election
over a `coordination.k8s.io` Lease** — THE tool for "exactly one replica does this" work (scheduled
jobs, cleanup tasks, …). Off by default (`k8s.enabled=false`); the `k8s` profile activates it.

- **`K8sConfiguration`** (`hu.zzit.reference.k8s`) is the generic, copy-me part: a Fabric8
  `KubernetesClient` bean. In-cluster it authenticates with the pod's **service account**
  automatically — no configuration at all; outside the cluster `k8s.kubeconfig` points it at a
  kubeconfig file. (The JDK HTTP-client flavor is used: fewer transitive dependencies.)
- **`LeaderElection`** is the use case on top: Fabric8's `LeaderElector` (acquire/renew/release
  with fencing) wired to the Spring lifecycle. All replicas compete for one Lease; exactly one
  holds it; a dead leader is replaced within ~15 s and the lease is **released on shutdown**, so
  rollouts hand leadership over immediately. Guard singleton work with `isLeader()` — and delete
  this class if all you need is the client.
- **RBAC is deployed with the app, not by it**: the Role/RoleBinding the election needs
  (get/update pinned to the one Lease, plus create) is documented in
  `k8s/leader-election-rbac.yaml`.
- `local-k8s` runs a compose-managed **k3s** — a *real* single-node Kubernetes API server, the
  Floci/Azurite analogue — which writes its admin kubeconfig to `./k3s-output/` for the app.
  `ReferenceLeaderElectionIT` proves the behavior against a Testcontainers k3s: the sole candidate
  acquires the Lease, and the Lease object names it as holder.

### 14. Optional Kafka: native clients, protobuf contract, MSK via config

Event streaming with three deliberate choices baked in. Off by default (`kafka.enabled=false`); the
`kafka` profile activates it and reads the broker list from `KAFKA_BOOTSTRAP_SERVERS`. Everything
lives in `hu.zzit.reference.kafka`.

**Choice 1 — the plain Apache `kafka-clients`, not spring-kafka.** The raw client is the stable,
universal API; its version is even managed by the Spring Boot parent. The two things a wrapper would
add are a handful of explicit lines here instead of framework behavior to debug:

- **`GreetingEventProducer`**: `KafkaProducer` *is* thread-safe, so the app shares one long-lived
  instance — `acks=all` + idempotence spelled out, sends keyed by name (per-key ordering), a bounded
  `close()` that flushes on shutdown. `publish()` returns a future: block on it when the event must
  not be lost, drop it for fire-and-forget.
- **`GreetingEventConsumer`**: `KafkaConsumer` is *not* thread-safe, so one `SmartLifecycle`-managed
  thread owns the poll loop end to end; `stop()` interrupts a blocking poll with `wakeup()` (the one
  cross-thread-safe method) inside the graceful-shutdown drain budget, and the close leaves the
  group explicitly so partitions rebalance immediately on rollouts. Delivery is **at-least-once**:
  auto-commit off, offsets committed *after* the batch is processed — consumers deduplicate on the
  event's producer-generated `id`. Records are fetched as raw *bytes* and parsed per record, so a
  **poison pill** is logged and skipped instead of wedging the partition (a throwing `Deserializer`
  inside `poll()` re-fetches the same broken record forever). Handler failures are logged and
  skipped too — pair anything unskippable with a retry or dead-letter topic.
- The app-side seam is the **`GreetingEventHandler`** bean — replace the default logging one.

**Choice 2 — schema support via a protobuf contract, no registry.** The contract is
`src/main/protobuf/greeting_event.proto`; protoc (pinned to the protobuf-java version by the
`protobuf-maven-plugin`) generates the Java at build time under `target/generated-sources` — nothing
generated is committed, and generated classes are excluded from coverage. Compatibility is enforced
at *build* time by sharing the `.proto` (evolution rules are documented in the file: field numbers
are forever, adding fields is safe both ways); the wire format is the plain protobuf encoding with
**no registry framing** (magic byte + schema id), so no schema-registry infrastructure exists to
run, secure, or outage-manage. Note the trade-off honestly: a runtime registry (AWS Glue, Apicurio)
adds *centralized* governance and framing that is **incompatible on the wire** with this setup —
choose per topic, don't mix.

**Choice 3 — broker security is configuration, not code.** The `kafka.properties.*` map is passed
verbatim to both clients (applied last, so it can override anything), which keeps the example
generic the way the storage port is multi-cloud. **Amazon MSK with IAM auth** is the worked example:
the `kafka-msk-iam` profile (a *group*, pulls `kafka` in) adds the four SASL entries and the
`aws-msk-iam-auth` runtime jar supplies the `AWS_MSK_IAM` mechanism — credentials flow through the
standard AWS provider chain (IRSA / pod identity), exactly like S3. Point `KAFKA_BOOTSTRAP_SERVERS`
at the IAM endpoint (`:9098`) and it just works; there is **no MSK emulator**, so the property
assembly is pinned by a unit test (`KafkaMskIamProfileTest`) and the handshake itself is verified
against a real cluster. SCRAM or mTLS for on-prem clusters are the same move: entries in
`kafka.properties.*`.

Topics are **provisioned by the platform**, not created by the app (partitions/replication/retention
are capacity decisions — the same philosophy as the database roles). `local-kafka` runs a
compose-managed single-node **Apache Kafka (KRaft)** on `localhost:9092`; `ReferenceKafkaIT` proves
the full round trip against a Testcontainers broker: publish → broker → poll loop → parsed event
reaches the handler bit-for-bit.

### 15. The `local-*` profiles

For running the app on a workstation against real-ish backends. Profile **groups** (in
`application.yaml`) make `local-aws`/`local-azure` pull in their provider profile automatically;
`local-db` and `local-kafka` are deliberately **self-contained** — group members are applied *last*,
so the `db`/`kafka` profile's env-var placeholders would override the concrete local values on the
shared keys (a subtlety worth stealing: groups only compose cleanly over disjoint keys, which is why
`kafka-msk-iam` *can* be a group — it only adds `kafka.properties.*` entries):

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-aws     # Floci   (S3)       on :4566
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-azure   # Azurite (Blob)     on :10000
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-db      # PostgreSQL (+ Liquibase) on :5432
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-kafka   # Apache Kafka (KRaft) on :9092
```

Each `application-local-*.yaml` turns on `spring-boot-docker-compose`
(`spring.docker.compose.enabled=true`) and selects its service via a **Compose profile**
(`spring.docker.compose.profiles.active`), so only the needed container from `compose.yaml` is
started — with a healthcheck, so Spring waits for actual readiness. The `local-*` profiles also
enable the [development file log](#16-logging). Base config keeps docker-compose **off** (so it
never triggers in tests or production).

### 16. Logging

`src/main/resources/logback-spring.xml` (named `-spring` so `<springProperty>` works) stays as close
to the Spring Boot defaults as possible. Everything goes to **STDOUT**; with no flags set the
console uses Spring Boot's **default plaintext layout** (the stock `console-appender.xml`). Flags:

- **`logging.json.enabled`** (default `false`): console in logback's **default JSON** format
  (`JsonEncoder`) — for platforms that just want structured output.
- **`logging.custom-json.enabled`** (default `false`): console in a **stable, collector-friendly
  JSON schema** (logstash-logback-encoder): `@timestamp` in ISO-8601 with time-zone,
  `lowercase_with_underscore` keys, `level` + `message`, one event per physical line (stack traces
  stay inside the JSON string). Use this when the log platform prescribes the field names.
- The two JSON modes are **mutually exclusive** — `LoggingModeGuard` fails startup with a clear
  error if both are on (inside logback, custom-json would win).
- **`logging.develop.enabled`** (default `false`): *additionally* write a rolling plaintext file
  under `./logs/` — a **development-only** convenience (the `local-*` profiles switch it on). In
  production the file is useless (STDOUT is collected) and ephemeral storage is scarce; the caps
  default to `10MB`/file, 7 days, `100MB` total and are overridable (see
  [environment variables](#environment-variables)).

The conditional blocks use logback's class-based `<condition>` (logback 1.5.20+) — no Janino, no
runtime code compilation. Tests use a separate console-only `logback-test.xml`.

```bash
java -jar target/reference-app-*.jar --logging.json.enabled=true    # JSON console
```

### 17. Graceful shutdown

`server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=25s`: on SIGTERM the
readiness probe flips to false, in-flight requests drain (bounded to fit inside Kubernetes' default
30 s termination grace period with headroom), then the app exits deterministically. Rollouts and
scale-downs lose no requests.

### 18. The `ci-reports` profile (non-blocking)

`-Pci-reports` adds analysis that **never fails the build** — it only *generates* artifacts. Each goal
binds to the earliest phase its inputs are ready, so the scope follows the lifecycle:

- **`mvn test -Pci-reports`** → static + unit-test reports: unit **coverage** (JaCoCo), **license**
  inventory (`THIRD-PARTY.txt`), **SpotBugs** (+ FindSecBugs) static analysis.
- **`mvn verify -Pci-reports`** → additionally: integration + merged **coverage**, and the **Trivy +
  Grype** SBOM vulnerability scans (the SBOM is built at `package`).

Coverage is emitted as **XML + CSV only — no HTML**, on purpose: JaCoCo's HTML report embeds the
annotated source and has no switch to omit it.

### 19. The `ci-gates` profile (blocking gates)

`ci-gates` turns the same concerns into **blocking gates**, and is **self-sufficient**: the SBOM
comes from the default build, the license/SpotBugs gates generate their own inputs, and the JaCoCo
instrumentation/merge executions are carried by the profile itself (mirrored from `ci-reports` with
*identical execution ids*, so activating both profiles merges them instead of instrumenting twice —
Maven has no profile-implies-profile mechanism, this is the composable substitute). Each profile
also activates on a property of the same name:

```bash
./mvnw verify -Dci-gates                # the full gate, pass/fail only
./mvnw verify -Dci-reports -Dci-gates   # the canonical CI command: gate + report artifacts
```

(The `-P` forms work identically: `-Pci-gates`, `-Pci-reports,ci-gates`.)

Gates: **coverage ≥ 80%** (the fleet delivery standard — measured on the merged unit+integration
data), **Trivy + Grype** fail on `HIGH`/`CRITICAL`, **license** fails on forbidden licenses (AGPL
family to start), **SpotBugs** fails on findings at/above the threshold. Under `ci-gates` the scanners
drop the "missing binary is OK" tolerance — Trivy and Grype **must** be on the CI image.

### 20. SBOM + vulnerability scanning

- The **SBOM is Spring Boot's built-in one**: declaring `cyclonedx-maven-plugin` activates the
  parent's managed `makeAggregateBom` execution, which writes
  `target/classes/META-INF/sbom/application.cdx.json` at `package` — packaged into the jar and served
  at **`/actuator/sbom`**. (We don't run a second `makeBom`; one SBOM, one source of truth.)
- **Trivy *and* Grype** both scan that SBOM (two independent vulnerability databases — a deliberate
  cross-check). **Base-image** scanning belongs in the image pipeline.

> `.mvn/maven.config` quiets one cosmetic WARN: CycloneDX's bundled JSON-schema validator logs
> "Unknown keyword" for some CycloneDX 1.6 keywords it doesn't recognize. The setting only lowers
> that one library's log level; genuine validation errors still surface.

### 21. The container image

`Dockerfile` is multi-stage with **two selectable final targets**:

```bash
docker build --target app-prebuilt -t reference-app .   # package a host/CI-built jar (fast; CI already ran verify)
docker build --target app          -t reference-app .   # full in-image build (no local JDK/Maven needed)
```

- The **`base` stage** is a hardened runtime base on `amazoncorretto:25`, fully parameterized by
  build args (defaults in parentheses):
  - **Patching**: `OS_UPGRADE` (`true`) runs `dnf upgrade` to the latest releasever — re-run it on
    rebuilds via `CACHEBUST`; set it `false` when pinning the base by digest for reproducibility.
  - **Package sets**: the production set is **minimal by default (empty)** — `BASE_PACKAGES` adds
    always-installed extras, and `INCLUDE_DEV_PACKAGES=true` adds the `DEV_PACKAGES` debug toolset
    (viewers/editors: less, vim, nano, jq, file; process/system: procps-ng, lsof, strace; network:
    iputils, iproute, nmap-ncat, traceroute, bind-utils, tcpdump; plus findutils/tar/unzip). JVM
    diagnostics — jcmd, jstack, jmap, JFR — need no package: the JDK base ships them. This works on
    **any target**, so `--build-arg INCLUDE_DEV_PACKAGES=true --target app-prebuilt` yields a
    tools-included build of the very same app for troubleshooting environments.
  - **Identity**: `APP_USER`/`APP_GROUP` (`javauser`/`javagroup`), `APP_UID`/`APP_GID`
    (1000/1000), `APP_HOME` (`/app` — home, workdir, and jar location, exported as `$APP_HOME` for
    `entrypoint.sh`), `APP_SHELL` (`/bin/bash`). The final `USER` is set **numerically**
    (`uid:gid`) on purpose: Kubernetes' `runAsNonRoot` admission can only verify numeric UIDs.

  In a fleet setup this stage is typically maintained as a separate shared base image, published in
  both flavors from the same file (e.g. `java-base:25` and `java-base:25-dev`) — it is inlined here
  so the reference is self-contained. The `builder` stage installs its own build tooling (`tar` for
  the Maven wrapper), so it works on the minimal base too.
- **Reproducible release builds**: pass `BASE_IMAGE=amazoncorretto@sha256:…` (a digest pin) together
  with `OS_UPGRADE=false` and the image builds from exactly the same inputs every time — the
  container-side pair of the reproducible jar. OS patching then happens by bumping the digest
  deliberately (auditable), not by whatever dnf served that day. The default (`tag` + upgrade)
  remains right for everyday CI.
- **OCI annotations**: the pipeline passes `IMAGE_REVISION`/`IMAGE_VERSION`/`IMAGE_CREATED` (from
  git) and they land as `org.opencontainers.image.*` labels — registries and scanners surface them,
  and they tie the image to the SBOM and `/actuator/info`.
- **Read-only root filesystem ready**: the app writes only `/tmp` (Jetty's docbase) and — dev flag
  only — `./logs`, so it runs with `readOnlyRootFilesystem: true` plus an emptyDir on `/tmp`
  (verified with `docker run --read-only --tmpfs /tmp`).
- **`CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL`** (build arg): optionally installs an extra trusted
  root CA (corporate TLS inspection, internal CA).
- **Ports**: `8080` application, `6080` actuator. The `HEALTHCHECK` (actuator health) is a
  local-run convenience — Kubernetes ignores it and uses probes.
- **`entrypoint.sh`** starts the JVM. Runtime JVM configuration is **environment-driven, not baked
  in**: put the standard flags into `JAVA_TOOL_OPTIONS` (the JVM picks it up automatically — this is
  what a Helm chart typically sets), and use `JVM_OPTS`/`JAVA_OPTS` for ad-hoc additions. Guidance:
  - Memory: `-XX:MaxRAMPercentage=75.0` (never `-Xmx` in containers).
  - GC: **G1 and ZGC are both valid choices** — select per workload:
    `-XX:+UseG1GC -XX:MaxGCPauseMillis=200` (balanced throughput/latency) or `-XX:+UseZGC`
    (lowest pause; generational by default on Java 25).
  - GC logging when needed: `-Xlog:gc*:file=/app/logs/gc.log:time,uptime,level,tags:filecount=5,filesize=10m`.
- The image build is intentionally **not** wired into Maven — it's the delivery pipeline's step
  (`docker build` / Kaniko), after `./mvnw verify -Dci-gates` went green.

---

## Environment variables

Every Spring property can be overridden via the environment (relaxed binding, e.g.
`logging.json.enabled` → `LOGGING_JSON_ENABLED`); the table lists the knobs this app *intends* to be
tuned with.

| Variable | Values (default) | Description |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `aws`, `azure`, `db`, `k8s`, `kafka`, `kafka-msk-iam`, `local-*` variants, combinable (none) | Feature selection. `aws`/`azure` pick the cloud adapter + SDK; `db` enables the database example; `k8s` the Kubernetes example; `kafka` the Kafka example (`kafka-msk-iam` = the same + MSK IAM auth); `local-*` variants add the compose-managed emulator/DB/k3s/broker. |
| `CLOUD_PROVIDER` | `aws` \| `azure` \| `none` (`none`) | Which ObjectStorage adapter is active. Prefer setting it via the profiles above so the SDK enable-flags stay in sync. |
| `LOGGING_JSON_ENABLED` | `true`/`false` (`false`) | Console in logback's default JSON format. Mutually exclusive with the custom JSON flag. |
| `LOGGING_CUSTOMJSON_ENABLED` | `true`/`false` (`false`) | Console in the collector-friendly JSON schema (`@timestamp`, lowercase keys — §16). Mutually exclusive with the flag above. |
| `LOGGING_DEVELOP_ENABLED` | `true`/`false` (`false`) | Development-only rolling plaintext file under `./logs/`. Keep off in production. |
| `LOG_FILE_MAXFILESIZE` | size (`10MB`) | Development file log: max size per file. |
| `LOG_FILE_MAXHISTORY` | days (`7`) | Development file log: days of rolled files kept. |
| `LOG_FILE_TOTALSIZECAP` | size (`100MB`) | Development file log: total cap — keep it under the container's ephemeral-storage budget. |
| `STORAGE_RETRY_MAXRETRIES` | int (`3`) | ObjectStorage retry: attempts after the first failure. |
| `STORAGE_RETRY_INITIALDELAYMS` | ms (`1000`) | ObjectStorage retry: first backoff delay. |
| `STORAGE_RETRY_MULTIPLIER` | float (`2.0`) | ObjectStorage retry: backoff multiplier. |
| `STORAGE_RETRY_MAXDELAYMS` | ms (`8000`) | ObjectStorage retry: backoff ceiling. |
| `DB_RW_URL` / `DB_RW_USERNAME` / `DB_RW_PASSWORD` | — (required with `db`) | Read-write connection. |
| `DB_RO_URL` / `DB_RO_USERNAME` / `DB_RO_PASSWORD` | — (required with `db`) | Read-only connection; typically a replica endpoint. |
| `DB_RW_POOL_SIZE` / `DB_RO_POOL_SIZE` | int (`10`) | Hikari `maximumPoolSize` of the two runtime pools. |
| `SPRING_LIQUIBASE_ENABLED` | `true`/`false` (`true` with `db`) | Run the migration at startup. Set `false` in serving pods when a hook job migrates (§12). |
| `K8S_NAMESPACE` | name (pod’s own) | Namespace of the leader-election Lease. |
| `K8S_LEASENAME` | name (`reference-app-leader`) | Name of the Lease. |
| `K8S_IDENTITY` | name (hostname = pod name) | This instance’s identity in the election. |
| `K8S_KUBECONFIG` | path (in-cluster/service account) | Kubeconfig file for outside-cluster use (the `local-k8s` profile sets it). |
| `KAFKA_BOOTSTRAP_SERVERS` | `host:port,…` (required with `kafka`) | Broker list; with `kafka-msk-iam` the cluster's IAM bootstrap endpoint (`:9098`). |
| `KAFKA_TOPIC` | name (`reference-greetings`) | Topic of the Kafka example — provisioned by the platform, never created by the app. |
| `KAFKA_GROUPID` | name (`reference-app`) | Consumer group; shared by all replicas of one deployment. |
| `MANAGEMENT_SERVER_PORT` | port (`6080`) | Actuator side port (health, probes, prometheus, sbom, info). |
| `JAVA_TOOL_OPTIONS` | JVM flags (unset) | Read by the JVM automatically — the deployment's standard flags (memory, GC, GC logging; see [§21](#21-the-container-image)). |
| `JVM_OPTS`, `JAVA_OPTS` | JVM flags (unset) | Appended explicitly by `entrypoint.sh` — ad-hoc additions on top of `JAVA_TOOL_OPTIONS`. |

Secrets (`DB_*_PASSWORD`, cloud credentials) must come from the platform's secret store — never from
files in git, in any encoding.

---

## Build pipeline matrix

Three contexts. **Local** optimizes for speed; **Generic CI** is the full blocking gate on every
push; **Release CI** is Generic CI + supply-chain/publish on a `vX.Y.Z` tag.

| Step | Maven phase | Local | Generic CI | Release CI |
|---|---|:--:|:--:|:--:|
| Enforcer + tidy gate | `validate` | ✓ | ✓ | ✓ |
| Format gate (Spotless) | `process-sources` | ✓ | ✓ | ✓ |
| Build + git metadata | `initialize`/`generate-resources` | ✓ | ✓ | ✓ |
| Unit tests | `test` | ✓ | ✓ | ✓ |
| Integration tests (Floci + Azurite + PostgreSQL + k3s + Kafka) | `integration-test` | ✓ (`verify`) | ✓ | ✓ |
| Coverage (gate: ≥ 80%) | `test`/`verify` | `-Pci-reports` | ✓ | ✓ |
| SBOM (in jar + `/actuator/sbom`) | `package` | opt | ✓ | ✓ |
| Vuln scan (Trivy + Grype) | `verify` | `-Pci-reports` | ✓ | ✓ |
| License inventory / gate | `process-test-classes`/`verify` | `-Pci-reports` | ✓ | ✓ |
| SpotBugs (+ FindSecBugs) | `test`/`verify` | `-Pci-reports` | ✓ | ✓ |
| Image build (`docker build`) | separate | opt | opt | ✓ |
| Publish / sign | `deploy` / separate | — | snapshot (opt) | *planned* |

Invocation: Local `./mvnw verify` (± `-Pci-reports`); Generic CI `./mvnw verify -Dci-reports -Dci-gates`. Release
CI is **planned**: `./mvnw deploy -Dci-reports -Dci-gates -Prelease` on the tag — the `release` profile
(publish/sign/changelog) does not exist yet; the image build runs after the gates.

---

## Project layout

```
reference-app/
├── pom.xml                       # the heart — toolchain pins, gates, profiles (heavily commented)
├── mvnw, mvnw.cmd                # Maven wrapper (pins 3.9.16)
├── mvnvm.properties              # mvnvm version pin (3.9.16)
├── compose.yaml                  # Floci / Azurite / PostgreSQL / k3s / Kafka (compose profiles aws / azure / db / k8s / kafka)
├── Dockerfile                    # hardened base + `app` / `app-prebuilt` targets (§21)
├── entrypoint.sh                 # container start; applies JVM_OPTS/JAVA_OPTS
├── db/init/01-roles.sql          # the expected DB role/permission layout (local bootstrap)
├── k8s/leader-election-rbac.yaml # the RBAC the leader election needs (deployed with the app)
├── .mvn/
│   ├── extensions.xml            # qoomon git-versioning (core extension)
│   ├── maven-git-versioning-extension.xml
│   └── maven.config              # quiets the CycloneDX schema-validator WARN
├── .claude/skills/converge-to-reference-app/SKILL.md   # agent guide to adopt this setup
└── src/
    ├── main/java/hu/zzit/reference/
    │   ├── ReferenceApplication.java       # + @EnableResilientMethods
    │   ├── storage/                        # multi-cloud port + adapters (§11)
    │   │   ├── ObjectStorage.java          # port; carries the @Retryable policy
    │   │   ├── S3ObjectStorage.java
    │   │   └── AzureBlobObjectStorage.java
    │   ├── db/                             # database example (§12)
    │   │   ├── DatabaseConfiguration.java  # rw/ro pools + read-only routing proxy
    │   │   └── GreetingRepository.java     # rw writes / ro reads
    │   ├── k8s/                            # Kubernetes example (§13)
    │   │   ├── K8sConfiguration.java       # KubernetesClient (service account / kubeconfig)
    │   │   ├── K8sProperties.java
    │   │   └── LeaderElection.java         # Lease-based leader election; isLeader()
    │   └── kafka/                          # Kafka example (§14)
    │       ├── KafkaConfiguration.java     # native clients from config; kafka.properties.* pass-through
    │       ├── KafkaProperties.java
    │       ├── ProtobufSerializer.java     # plain protobuf on the wire (no registry framing)
    │       ├── GreetingEventProducer.java  # shared producer; acks=all + idempotence; keyed sends
    │       ├── GreetingEventConsumer.java  # owned poll loop; at-least-once; poison-pill safe
    │       └── GreetingEventHandler.java   # the seam to business logic (default bean: logs)
    ├── main/protobuf/
    │   └── greeting_event.proto            # THE message contract (evolution rules inside)
    ├── main/resources/
    │   ├── application.yaml                # base config (everything optional off, actuator on 6080)
    │   ├── application-{aws,azure,db,k8s,kafka}.yaml        # provider profiles (+ kafka-msk-iam overlay)
    │   ├── application-local-{aws,azure,db,k8s,kafka}.yaml  # workstation profiles (compose auto-start)
    │   ├── db/changelog/                   # Liquibase changelog (+ grants)
    │   └── logback-spring.xml              # default/JSON console + dev-only ./logs file
    └── test/
        ├── java/hu/zzit/reference/                # tests mirror the package under test
        │   ├── ReferenceApplicationTests.java     # unit (surefire)
        │   ├── LoggingModeGuardTest.java          # unit: JSON-mode exclusivity
        │   ├── storage/ObjectStorageRetryTest.java # unit: retry policy
        │   ├── storage/ReferenceS3IT.java         # integration, AWS (failsafe)
        │   ├── storage/ReferenceAzureBlobIT.java  # integration, Azure (failsafe)
        │   ├── db/ReferencePostgresIT.java        # integration, DB (failsafe)
        │   ├── k8s/ReferenceLeaderElectionIT.java # integration, k3s (failsafe)
        │   ├── kafka/…Test.java                   # unit: MockProducer/MockConsumer, MSK IAM config
        │   └── kafka/ReferenceKafkaIT.java        # integration, Kafka round trip (failsafe)
        └── resources/{application.yaml, logback-test.xml}
```

---

## Conventions for new code

- **Package:** `hu.zzit.reference` (`hu.zzit.<service>` in derived services).
- **Encoding:** UTF-8 everywhere under `src/**`. Non-UTF-8 legacy resources go under
  `src/main/resources/legacy/` (the only path excluded from the encoding gate).
- **Tests:** name unit tests `*Test`/`*Tests`, integration tests `*IT`, and put every test in the
  **package of the code it exercises**. Anything needing Docker or an external service is an
  integration test. Keep merged coverage ≥ 80% — the `ci-gates` profile enforces it.
- **Cloud access:** depend on the `ObjectStorage` port (or a new port of the same shape), never on
  `S3Client`/`BlobServiceClient` directly; gate provider adapters on `cloud.provider`.
- **External calls:** declare an explicit retry/backoff policy (Spring's `@Retryable`) on idempotent
  operations only; add `@ConcurrencyLimit` (bulkhead) where a slow dependency could exhaust threads.
- **Database access:** roles and permissions are provisioned with the database, never from
  migrations — changelogs hold schema objects only. Use the routed rw/ro pools (§12): one
  `JdbcTemplate`, read paths marked `@Transactional(readOnly = true)`.
- **Messaging:** one `.proto` contract per topic under `src/main/protobuf/` (evolution rules in
  §14 — field numbers are forever); topics are provisioned by the platform, not the app. Design
  consumers for at-least-once: deduplicate on the event id, and never let a record parse/handle
  failure kill the poll loop. Broker security goes into `kafka.properties.*`, never into code.
- **Formatting:** never hand-format — run `./mvnw spotless:apply` and `./mvnw tidy:pom`. The build
  enforces both.
- **Dependencies:** add through a BOM where one exists; pin a version only for genuinely standalone
  artifacts. Keep `dependencyConvergence` green. Record CVE-driven and convergence pins in the
  `<properties>` block, tagged with the reason.
- **Before pushing:** run `./mvnw verify -Dci-gates` locally to reproduce the CI gate.
