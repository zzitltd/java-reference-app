# syntax=docker/dockerfile:1
#
# reference-app container image. Multi-stage, with TWO selectable Java flavors and TWO jar sources:
#
#   docker build -t reference-app .                                   # package a host-built jar (CI: mvn verify ran already)
#   docker build --build-arg JAR_SOURCE=build -t reference-app .      # full in-image build (no local JDK/Maven needed)
#   docker build --build-arg JAVA_FLAVOR=corretto-al2023 -t reference-app .
#
# Startup: the jar is EXTRACTED (Boot's `extract` tool: launcher jar + lib/*.jar — no nested-jar
# loading) and the image carries a JDK AOT cache (JEP 483/514/515: classes pre-parsed, verified,
# linked, plus method profiles) produced by a training run at build time on the very JVM that runs
# in production. Measured on the reference: ready in ~0.9 s instead of ~2.3 s.
#
# Flavors (JAVA_FLAVOR) — picked from the java-base-image survey. The app RUNS on a JRE; the jar is
# BUILT on the matching JDK:
#   temurin-alpine    (default) Eclipse Temurin JRE on Alpine — musl libc; the smallest image with the
#                     fewest findings. Alpine ships busybox (sh, wget, adduser) but no bash or curl.
#   corretto-al2023   Amazon Corretto headless JRE on Amazon Linux 2023 — glibc; for workloads whose
#                     native libraries have no musl build (see README §21).
#
# Build args (all optional — defaults produce the production image of the default flavor):
#   JAVA_VERSION                              Java line (default 25); only feeds the default image tags
#   JAVA_FLAVOR                               temurin-alpine | corretto-al2023
#   JAR_SOURCE                                prebuilt (default): target/*.jar from the build context;
#                                             build: compile + package in the `builder` stage
#   SPRING_AOT                                false (default) | true: run with Spring AOT (the bean
#                                             registrations process-aot generated at build time —
#                                             no classpath scanning or condition evaluation at
#                                             startup). ONLY for a fixed feature set: conditions were
#                                             evaluated at build time, runtime profiles no longer add
#                                             beans. Fails at startup while the (signed) Azure
#                                             auto-configurations are active — see README §21.
#   AOT_TRAINING_JVM_OPTS                     JVM flags for the AOT training run (default: none = G1,
#                                             compressed oops). MUST match what the deployment sets in
#                                             JAVA_TOOL_OPTIONS for GC and pointer mode — a cache
#                                             trained under G1 is REJECTED at runtime under ZGC (or with
#                                             a heap > 32 GB), silently falling back to normal loading.
#   TEMURIN_ALPINE_IMAGE, TEMURIN_ALPINE_JDK_IMAGE, CORRETTO_AL2023_IMAGE, CORRETTO_AL2023_JDK_IMAGE
#                                             runtime (JRE) and builder (JDK) image of each flavor.
#                                             RELEASE POSTURE: pass digest-pinned references
#                                             (eclipse-temurin@sha256:...) together with
#                                             OS_UPGRADE=false for a REPRODUCIBLE image — patching
#                                             then happens by bumping the digest deliberately.
#   OS_UPGRADE                                true (default): upgrade the OS packages (apk upgrade /
#                                             dnf upgrade --releasever=latest); false: keep the base
#                                             image's package versions
#   CACHEBUST                                 pass e.g. the build timestamp to re-run the upgrade
#                                             past Docker's layer cache (rebuilds pick up OS patches)
#   BASE_PACKAGES                             extra packages ALWAYS installed, in the flavor's package
#                                             names (default: none — the production set is minimal)
#   DEV_PACKAGES                              the developer toolset; the default differs per flavor
#                                             because package names do (see the base stages)
#   INCLUDE_DEV_PACKAGES                      true: also install DEV_PACKAGES — build a debug/dev
#                                             variant of ANY target, including the app images, for
#                                             troubleshooting environments (default false)
#   APP_USER / APP_GROUP / APP_UID / APP_GID  runtime identity (default javauser/javagroup, 1000/1000)
#   APP_HOME                                  the user's home + workdir + jar location (default /app)
#   APP_SHELL                                 the user's shell (default /bin/sh — present in both flavors)
#   IMAGE_REVISION / IMAGE_VERSION / IMAGE_CREATED
#                                             OCI annotations, supplied by the pipeline from git:
#                                             rev-parse HEAD / the git-derived version / the commit
#                                             timestamp — they tie the image to the SBOM and
#                                             /actuator/info
#   CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL    optional extra trusted root CA, fetched at build time
#                                             into the OS trust store AND the JVM's cacerts
#
# In a fleet setup the `base-*` stages are typically maintained as SEPARATE, shared, hardened base
# images — published per flavor, each in two variants from the same file (e.g. java-base:25-alpine
# and, with INCLUDE_DEV_PACKAGES=true, java-base:25-alpine-dev); they are inlined here so the
# reference is self-contained. The runtime JVM is configured via env vars, not baked in — see
# entrypoint.sh and the README's environment-variable table (JAVA_TOOL_OPTIONS / JVM_OPTS, GC).

ARG JAVA_VERSION=25
ARG JAVA_FLAVOR=temurin-alpine
ARG JAR_SOURCE=prebuilt
# Alpine tags carry the Alpine minor on purpose: the bare `-alpine` tag silently moves to the next
# Alpine release. Corretto publishes its JRE only as the AL2023 `-headless` package/image.
ARG TEMURIN_ALPINE_IMAGE=eclipse-temurin:${JAVA_VERSION}-jre-alpine-3.24
ARG TEMURIN_ALPINE_JDK_IMAGE=eclipse-temurin:${JAVA_VERSION}-jdk-alpine-3.24
ARG CORRETTO_AL2023_IMAGE=amazoncorretto:${JAVA_VERSION}-al2023-headless
ARG CORRETTO_AL2023_JDK_IMAGE=amazoncorretto:${JAVA_VERSION}-al2023-jdk

## ---------------------------------------------------------------------------
## Stage: base-temurin-alpine — hardened runtime base, musl (patched, non-root, minimal)
## ---------------------------------------------------------------------------
FROM ${TEMURIN_ALPINE_IMAGE} AS base-temurin-alpine
ARG TEMURIN_ALPINE_IMAGE
LABEL org.opencontainers.image.base.name="${TEMURIN_ALPINE_IMAGE}"

ARG OS_UPGRADE=true
# Changing CACHEBUST invalidates this layer so the upgrade actually runs on rebuilds.
ARG CACHEBUST=1
ARG BASE_PACKAGES=""
# The debug toolset: viewers/editors, file tools, process/system and network diagnostics (Alpine names).
ARG DEV_PACKAGES="less vim nano jq file findutils tar unzip procps lsof strace iputils iproute2 netcat-openbsd traceroute bind-tools tcpdump"
ARG INCLUDE_DEV_PACKAGES=false
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_UID=1000
ARG APP_GID=1000
ARG APP_HOME=/app
ARG APP_SHELL=/bin/sh
ARG CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL=""

# gcompat: snappy-java (on the classpath via kafka-clients) ships a glibc-linked .so and fails to
# load on musl without this ~300 KB shim; zstd-jni, lz4-java and JNA carry native musl builds.
# The JVM's cacerts is the JDK's own file here (not the OS bundle), hence the keytool import.
RUN set -eu; \
    if [ "${OS_UPGRADE}" = "true" ]; then apk upgrade --no-cache; fi; \
    PACKAGES="gcompat ${BASE_PACKAGES}"; \
    if [ "${INCLUDE_DEV_PACKAGES}" = "true" ]; then PACKAGES="${PACKAGES} ${DEV_PACKAGES}"; fi; \
    apk add --no-cache ${PACKAGES}; \
    addgroup -S -g "${APP_GID}" "${APP_GROUP}"; \
    adduser -S -D -u "${APP_UID}" -G "${APP_GROUP}" -h "${APP_HOME}" -s "${APP_SHELL}" "${APP_USER}"; \
    if [ -n "${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL}" ]; then \
        mkdir -p /usr/local/share/ca-certificates; \
        wget -q -O /usr/local/share/ca-certificates/custom-root-ca.crt "${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL}"; \
        update-ca-certificates; \
        keytool -importcert -noprompt -cacerts -storepass changeit -alias custom-root-ca \
            -file /usr/local/share/ca-certificates/custom-root-ca.crt; \
    fi

WORKDIR ${APP_HOME}

## ---------------------------------------------------------------------------
## Stage: base-corretto-al2023 — hardened runtime base, glibc (patched, non-root, minimal)
## ---------------------------------------------------------------------------
FROM ${CORRETTO_AL2023_IMAGE} AS base-corretto-al2023
ARG CORRETTO_AL2023_IMAGE
LABEL org.opencontainers.image.base.name="${CORRETTO_AL2023_IMAGE}"

ARG OS_UPGRADE=true
ARG CACHEBUST=1
ARG BASE_PACKAGES=""
# The same toolset in Amazon Linux package names.
ARG DEV_PACKAGES="less vim nano jq file findutils tar unzip procps-ng lsof strace iputils iproute nmap-ncat traceroute bind-utils tcpdump"
ARG INCLUDE_DEV_PACKAGES=false
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_UID=1000
ARG APP_GID=1000
ARG APP_HOME=/app
ARG APP_SHELL=/bin/sh
ARG CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL=""

# shadow-utils (groupadd/useradd) is tooling for this layer only — removed at the end. Corretto's
# cacerts is a symlink into the OS trust store, so update-ca-trust alone covers the JVM.
RUN set -eu; \
    DNF="dnf -y --setopt=install_weak_deps=False"; \
    if [ "${OS_UPGRADE}" = "true" ]; then DNF="${DNF} --releasever=latest"; ${DNF} upgrade; fi; \
    PACKAGES="${BASE_PACKAGES}"; \
    if [ "${INCLUDE_DEV_PACKAGES}" = "true" ]; then PACKAGES="${PACKAGES} ${DEV_PACKAGES}"; fi; \
    ${DNF} install shadow-utils ${PACKAGES}; \
    groupadd --system --gid "${APP_GID}" "${APP_GROUP}"; \
    useradd --uid "${APP_UID}" --gid "${APP_GID}" --no-user-group \
        --home-dir "${APP_HOME}" --create-home --shell "${APP_SHELL}" "${APP_USER}"; \
    dnf -y remove shadow-utils; \
    if [ -n "${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL}" ]; then \
        curl -fsS -o /etc/pki/ca-trust/source/anchors/custom-root-ca.crt "${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL}"; \
        update-ca-trust; \
    fi; \
    dnf -y clean all; \
    rm -rf /var/cache/dnf

WORKDIR ${APP_HOME}

## ---------------------------------------------------------------------------
## Stage: base — the selected flavor
## ---------------------------------------------------------------------------
FROM base-${JAVA_FLAVOR} AS base

## ---------------------------------------------------------------------------
## Stage: builder — build the jar on the flavor's JDK (only used by --target app).
## Throwaway stage: nothing of it ships, so it is not patched or de-rooted.
## ---------------------------------------------------------------------------
FROM ${TEMURIN_ALPINE_JDK_IMAGE} AS jdk-temurin-alpine
# busybox already provides the tar/gzip/wget the Maven wrapper needs.

FROM ${CORRETTO_AL2023_JDK_IMAGE} AS jdk-corretto-al2023
RUN dnf -y --setopt=install_weak_deps=False install tar gzip && dnf -y clean all && rm -rf /var/cache/dnf

FROM jdk-${JAVA_FLAVOR} AS builder
WORKDIR /build
# Exactly what `package -DskipTests` consumes: the wrapper + POM, the git history
# (the project version is derived from git), and the sources.
COPY .mvn .mvn
COPY mvnw pom.xml ./
COPY .git .git
COPY src src
# Tests already ran in CI (`mvnw verify -Dci-reports -Dci-gates`); the image build only packages.
RUN --mount=type=cache,target=/root/.m2 ./mvnw --batch-mode --no-transfer-progress package -DskipTests

## ---------------------------------------------------------------------------
## Stage: app-common — everything but the jar
## ---------------------------------------------------------------------------
FROM base AS app-common
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_UID=1000
ARG APP_GID=1000
ARG APP_HOME=/app

# OCI annotations — the pipeline passes these from git; empty on ad-hoc local builds.
ARG IMAGE_REVISION=""
ARG IMAGE_VERSION=""
ARG IMAGE_CREATED=""
LABEL org.opencontainers.image.title="reference-app" \
      org.opencontainers.image.revision="${IMAGE_REVISION}" \
      org.opencontainers.image.version="${IMAGE_VERSION}" \
      org.opencontainers.image.created="${IMAGE_CREATED}"

# 8080: application traffic; 6080: actuator (health/metrics/sbom) side port.
EXPOSE 8080 6080

# Local-run convenience only — Kubernetes ignores container HEALTHCHECKs (it uses probes).
# Alpine has busybox wget, Amazon Linux has curl: whichever exists does the probe.
HEALTHCHECK CMD wget -q --spider http://localhost:6080/actuator/health 2>/dev/null \
    || curl -fsS -o /dev/null http://localhost:6080/actuator/health

# entrypoint.sh reads APP_HOME to find the jar.
ENV APP_HOME=${APP_HOME}
COPY --chown=${APP_USER}:${APP_GROUP} --chmod=755 entrypoint.sh /entrypoint.sh
# NUMERIC user:group on purpose: Kubernetes' runAsNonRoot admission can only verify
# numeric UIDs — a name-based USER can get the pod rejected under strict PodSecurity.
USER ${APP_UID}:${APP_GID}

ENTRYPOINT ["/entrypoint.sh"]
CMD ["run"]

## ---------------------------------------------------------------------------
## Stage: jar — the ONE fat jar, from the build context (prebuilt) or the builder stage (build)
## ---------------------------------------------------------------------------
# prebuilt: with several jars in target/ COPY would silently pick one — fail loudly unless there
# is exactly one.
FROM base AS jar-prebuilt
COPY target/*.jar /jars/
RUN count=$(ls /jars/*.jar | wc -l) && \
    if [ "$count" -ne 1 ]; then \
        echo "ERROR: expected exactly 1 jar in target/, found $count — run './mvnw clean package' first" >&2; \
        exit 1; \
    fi && \
    mv /jars/*.jar /app.jar

FROM base AS jar-build
COPY --from=builder /build/target/*.jar /app.jar

FROM jar-${JAR_SOURCE} AS jar

## ---------------------------------------------------------------------------
## Stage: extracted — the fat jar unpacked into launcher + lib/ (throwaway, keeps the fat jar
## out of the final image's layers)
## ---------------------------------------------------------------------------
FROM base AS extracted
ARG APP_HOME=/app
COPY --from=jar /app.jar /tmp/app.jar
RUN java -Djarmode=tools -jar /tmp/app.jar extract --destination /extracted --application-filename app.jar

## ---------------------------------------------------------------------------
## Stage: app — the final image: extracted app + AOT cache from a training run
## ---------------------------------------------------------------------------
FROM app-common AS app
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_HOME=/app
ARG AOT_TRAINING_JVM_OPTS=""
ARG SPRING_AOT=false
# entrypoint.sh passes it on as -Dspring.aot.enabled; overridable per deployment.
ENV SPRING_AOT=${SPRING_AOT}
COPY --from=extracted --chown=${APP_USER}:${APP_GROUP} /extracted/ ${APP_HOME}/
# Training run as the runtime user on the runtime JVM: the cache is only valid for this exact JVM
# build and classpath. spring.context.exit=onRefresh stops the app right after the context is up;
# no profile is active, so nothing external is contacted. entrypoint.sh adds -XX:AOTCache.
RUN java ${AOT_TRAINING_JVM_OPTS} -Dspring.aot.enabled=${SPRING_AOT} \
        -XX:AOTCacheOutput=${APP_HOME}/app.aot -Dspring.context.exit=onRefresh \
        -jar ${APP_HOME}/app.jar > /dev/null && \
    test -s ${APP_HOME}/app.aot
