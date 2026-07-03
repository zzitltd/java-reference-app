---
name: converge-to-reference-app
description: >-
  Align an existing Java/Maven project (ideally Spring Boot) to the ZZ-IT (hu.zzit) reference-app
  build, quality, and supply-chain standard — Java 25 + Maven version pins, the maven-enforcer gate,
  Spotless + tidy formatting, the unit/integration test split, git-based versioning, reproducible
  builds, logback logging, graceful shutdown, Prometheus metrics on a side port, optional
  multi-cloud (AWS + Azure behind a retrying port), an optional least-privilege database setup
  (platform-provisioned roles/permissions, routed read-write/read-only pools, grant-free Liquibase
  changelogs), an optional Kubernetes API example (Lease-based leader election; k3s locally), a
  hardened multi-stage container image, and the ci-reports/ci-gates profiles with SBOM +
  Trivy/Grype + SpotBugs + license gates (coverage ≥ 80%). Use when asked to standardize, harden,
  modernize, migrate, or audit a project against reference-app, or to set up its build/CI to match
  the fleet standard.
---

# Converge a project to the reference-app standard

This skill turns an existing project's build into the one defined by **reference-app**. The reference
POM is the source of truth — it is heavily commented. **Before doing anything, read the canonical
files** (in this repo, or wherever reference-app lives): `pom.xml`, `README.md`, `.mvn/`,
`logback-spring.xml`, `application.yaml`, and the two test classes. Mirror *intent*, not bytes.

## Operating principles

1. **Audit first, then converge in small, reviewable commits.** One concern per commit (e.g. "add
   enforcer", "apply Spotless"). Spotless/tidy commits are huge mechanical diffs — keep them alone.
2. **Verify everything empirically.** After each change run the relevant Maven command and confirm it
   passes (and that gates actually *fail* when they should). Never assume a plugin "just works" on
   the project's JDK — the reference-app discovered several that didn't until configured correctly.
3. **Adapt — do not blind-copy.** Object storage (S3/Blob), Floci/Azurite, and Jetty are *this*
   service's choices. Carry over the *structure* (BOM-managed deps, the gates, the profiles, the
   test split, the port-and-adapters cloud pattern); swap the domain-specific pieces for whatever
   the target project actually uses — including keeping only the cloud provider(s) it needs.
4. **Introduce blocking gates carefully.** A real project will fail several enforcer rules and the
   coverage/SpotBugs/vuln gates on day one. Land the *non-blocking* `ci-reports` profile first, read its
   output, fix or triage findings, and only then turn on the `ci-gates` profile. Start coverage thresholds
   low. Don't make the team's build red in a single commit.
5. **Don't reformat what you're also changing semantically in the same commit** — reviewers can't see
   the real change under the formatting noise.

## Phase 0 — Audit

Produce a gap list: for each item in the checklist below, note present / partial / missing. Read the
target `pom.xml`, any `.mvn/`, CI config, and `application.*`. Report the gaps and a proposed
ordering before making changes if the project is non-trivial.

## Convergence checklist (ordered to minimize breakage)

Work top-down; earlier items are prerequisites for later ones.

### A. Toolchain pins
- `<java.version>` = the fleet target (currently **25**). Pin `<maven.version>` too.
- Pin Maven in **all** the places that must agree: `mvnvm.properties`, `.mvn/wrapper/maven-wrapper.properties`
  (so `./mvnw` downloads it), and the enforcer's `requireMavenVersion`. A wrapper that pulls a
  *different* version than the enforcer requires will fail every build.

### B. The enforcer gate (`maven-enforcer-plugin` + `extra-enforcer-rules`, at `validate`)
Add the rules from reference-app §3. **Expect failures on a real project** and resolve them:
- `dependencyConvergence` almost always fails first — fix by importing the right BOMs (Phase D) and
  adding targeted `<dependencyManagement>` pins.
- `requirePluginVersions` fails if any plugin floats — pin them. Excuse `maven-site-plugin` via
  `<unCheckedPluginList>` (its version comes from Maven's defaults, not the POM).
- `requireEncoding` fails on non-UTF-8 files — set `<acceptAsciiSubset>true</acceptAsciiSubset>` and
  quarantine genuinely-legacy-encoded resources under an excluded path.
- The **Oracle-JDK ban** must test `java.vm.name` (commercial = `Java HotSpot(TM)`), **not**
  `java.vendor` (both commercial and Oracle OpenJDK report "Oracle Corporation").

### C. Formatting gates
- `tidy-maven-plugin:check` at `validate`; fix with `mvn tidy:pom`.
- `spotless-maven-plugin` + `palantir-java-format`, `check` at `process-sources`; fix with
  `mvn spotless:apply`. **Run `spotless:apply` + `tidy:pom` as their own commit** before anything else.

### D. Dependencies via BOMs
- Prefer BOM imports over per-dependency versions. For Spring Boot use the parent; add the relevant
  cloud/test BOMs (`spring-cloud-aws-dependencies` 4.x and/or `spring-cloud-azure-dependencies` 7.x
  for Boot 4). **Testcontainers BOM is not managed by the Boot 4.1 parent — import it explicitly**
  (also makes transitive Testcontainers deps converge).
- Only standalone artifacts keep an explicit `<version>`. Record CVE-driven pins in `<properties>`
  tagged with the CVE/GHSA id; record convergence pins the same way (the Azure BOM does NOT manage
  azure-identity's transitives — reference-app pins `msal4j` and JNA, see its POM).
- **Optional multi-cloud:** if the project talks to cloud storage, mirror the reference pattern — a
  small port (`ObjectStorage`-shaped), one adapter per provider gated by
  `@ConditionalOnProperty("cloud.provider")`, SDK autoconfigurations off by default, and `aws` /
  `azure` Spring profiles as the single knob that enables one SDK + its adapter together.

### E. Versioning + reproducible builds
- qoomon `maven-git-versioning-extension` as a **core extension** in `.mvn/extensions.xml` (pinned
  literally — `extensions.xml` can't read POM properties). POM `<version>` becomes a placeholder.
- **Declare `project.build.outputTimestamp` in `<properties>`** — the extension only overrides
  properties that *already exist*, so omitting it silently disables reproducibility. Fallback value
  `1980-01-01T00:00:02Z` (epoch 0 / 1970 is **rejected** by Spring Boot's `build-info`).
- CI must checkout full history (`fetch-depth: 0`) for the extension to see tags.

### F. Build metadata (Spring Boot)
- `spring-boot-maven-plugin:build-info`.
- `git-commit-id-maven-plugin`: **declare configuration only, no execution** — the Spring Boot parent
  already binds the `revision` execution; adding your own runs it twice.

### G. Test split
- `maven-surefire-plugin` for unit tests (`*Test`/`*Tests`, on `mvn test`); `maven-failsafe-plugin`
  (`integration-test` + `verify` goals) for integration tests (`*IT`, on `mvn verify`).
- **Mockito agent:** resolve its jar via `maven-dependency-plugin:properties`, then load it on
  surefire/failsafe `argLine` as `-javaagent:${org.mockito:mockito-core:jar} -Xshare:off` (self-attach
  is deprecated on modern JDKs). Keep `@{...}` late-binding placeholders for the JaCoCo agent args.
- Integration tests that need infra use **Testcontainers**; inject endpoints via
  `@DynamicPropertySource`. If a starter needs config to even build its beans (e.g. AWS needs a
  region), give the test classpath a self-contained `src/test/resources/application.yaml`.

### H. Logging (logback)
- `logback-spring.xml` (the `-spring` suffix is required for `<springProperty>`).
- Console modes via flags, staying close to the Boot defaults: default = Boot's stock
  `console-appender.xml`; `logging.json.enabled` = logback's `JsonEncoder`;
  `logging.custom-json.enabled` = a collector-friendly schema via logstash-logback-encoder
  (`@timestamp` ISO-8601+zone, lowercase_underscore keys). The two JSON flags are mutually
  exclusive — enforce fail-fast in a `@PostConstruct` (throwing from a constructor trips SpotBugs
  `CT_CONSTRUCTOR_THROW`). Use the **class-based `<condition>`** (`PropertyEqualityCondition`,
  logback 1.5.20+) **preceding** `<if>` (nesting works) — **not** the deprecated `condition="..."`
  attribute (needs Janino). A rolling plaintext `./logs` file behind `logging.develop.enabled`
  (dev-only; keep the size caps within the ephemeral-storage budget). A separate console-only
  `logback-test.xml` for tests.
- **If the target project uses log4j2 instead** (a build-time choice — SLF4J allows exactly one
  backend, so do NOT try to support both in one artifact): exclude `spring-boot-starter-logging`
  from the starters and add `spring-boot-starter-log4j2`; never let `log4j-to-slf4j` and
  `log4j-slf4j2-impl` coexist (routing loop — `banDuplicateClasses`/convergence will usually catch
  it). Map the config: `logback-spring.xml` → `log4j2-spring.xml` (with `${spring:...}` lookups
  instead of `<springProperty>`), `logback-test.xml` → `log4j2-test.xml`, JSON output via
  `JsonTemplateLayout` (custom schemas become a JSON template file — logstash-logback-encoder is
  logback-only). log4j2 has no `<condition>/<if>`; select the console format by choosing the whole
  config file per environment via `logging.config`, or with log4j2's `<SpringProfile>` arbiters.
  Application code is unaffected (everything logs through the SLF4J API).

### I. The `local-*` profiles
- Per-backend workstation profiles (`local-aws`, `local-azure`, `local-db`) that turn on
  `spring-boot-docker-compose` (`spring.docker.compose.enabled`) and select ONE compose service via
  a compose profile (`spring.docker.compose.profiles.active`), with container healthchecks so
  Spring waits for real readiness. Keep docker-compose **off** in base config so it never triggers
  in tests/production.
- Spring profile groups (`local-aws` = `aws` + emulator bits) only compose cleanly over **disjoint
  keys** — group members are applied LAST and win on overlapping keys, which is why reference-app's
  `local-db` is self-contained rather than a group over `db`.

### J. The `ci-reports` profile (non-blocking) and `ci-gates` profile (blocking)
- `ci-reports`: JaCoCo (split unit/integration/merged; **XML + CSV only, no HTML** — HTML embeds source),
  the SBOM-based vuln scans, license inventory, SpotBugs — **no `check` goals; nothing fails.** Bind
  each goal to the earliest phase its inputs are ready so scope follows the phase (`test` = static +
  unit, `verify` = + integration).
- `ci-gates`: the same concerns as **blocking** `check`/gate goals — and SELF-SUFFICIENT: repeat
  the JaCoCo instrumentation/merge executions inside it with the SAME execution ids as ci-reports,
  so `mvn verify -Dci-gates` is a complete gate on its own and co-activating both profiles merges
  the shared executions instead of instrumenting twice (Maven has no profile-implies-profile — this
  is the composable substitute). Give each profile a property activation matching its id. Canonical
  CI: `mvn verify -Dci-reports -Dci-gates` (gate + artifacts). Start the coverage threshold low and
  raise it over time.

### K. SBOM + supply-chain
- Declaring `cyclonedx-maven-plugin` activates the **Spring Boot parent's** managed `makeAggregateBom`
  → `target/classes/META-INF/sbom/application.cdx.json` at `package`, in the jar, served by
  `/actuator/sbom` (expose `sbom` in the actuator). **Do not also add your own `makeBom`** — that
  generates the SBOM twice. Point scanners at the `META-INF/sbom` file; they run at `verify` (the SBOM
  exists as of `package`).
- Scan the SBOM with **both Trivy and Grype** (independent DBs). Base-image scanning and the Docker
  image build are **out of Maven scope** — separate pipeline.
- If CycloneDX logs `WARN ... Unknown keyword meta:enum/deprecated`, quiet just that validator via
  `.mvn/maven.config`: `-Dorg.slf4j.simpleLogger.log.com.networknt.schema=error`. There is no
  skip-validation flag.

## Known pitfalls (these cost real time in reference-app)

| Symptom | Cause / fix |
|---|---|
| Reproducible build silently not applied | qoomon only overrides *existing* properties → declare `project.build.outputTimestamp`. |
| `build-info` rejects the timestamp | epoch 0/1970 is invalid → use `1980-01-01T00:00:02Z`. |
| `git-commit-id` runs twice | Spring Boot parent binds `revision` → config-only, no own execution. |
| `requirePluginVersions` fails on site plugin | add it to `<unCheckedPluginList>`. |
| `requireEncoding` fails on ASCII files | `<acceptAsciiSubset>true</acceptAsciiSubset>`. |
| Oracle ban also blocks Oracle OpenJDK | test `java.vm.name` contains `Java HotSpot(TM)`, not `java.vendor`. |
| Mockito "self-attaching agent" warning | load via `-javaagent` + `dependency:properties`. |
| Coverage HTML leaks source code | JaCoCo `<formats>XML,CSV</formats>` (no HTML toggle exists). |
| `dependencyConvergence` red after adding Testcontainers | import `testcontainers-bom`; artifact is `testcontainers-junit-jupiter` in 2.x. |
| SBOM generated twice / extra warnings | don't add `makeBom`; use the parent's `makeAggregateBom`. |
| logback `<if condition=>` deprecation WARN | switch to class-based `<condition>` element. |

## Verification (do this, don't assume)

1. `mvn validate` — enforcer + tidy gates pass (and fail when you deliberately break a rule).
2. `mvn test` — fast, unit-only, no Docker.
3. `mvn verify` — integration tests run (Docker up).
4. `mvn verify -Pci-reports` — reports/artifacts generated, **build still green** (non-blocking).
5. `mvn verify -Dci-gates` — gates enforce standalone from a CLEAN build (stale coverage data can
   fake a pass); confirm each gate *can* fail (e.g. lower a threshold, add a forbidden license) so
   you know it's wired, then restore.
6. If Spring Boot: run the jar, hit `/actuator/info` (version + git) and `/actuator/sbom`.

Report what passed, what you triaged vs fixed, and any gate you intentionally left non-blocking for
the team to tighten later.
