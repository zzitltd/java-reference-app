# syntax=docker/dockerfile:1
#
# reference-app container image. Multi-stage with TWO selectable final targets:
#
#   docker build --target app-prebuilt -t reference-app .   # package a host-built jar (CI: mvn verify ran already)
#   docker build --target app          -t reference-app .   # full in-image build (no local JDK/Maven needed)
#
# Build args (all optional — defaults produce the production flavor):
#   JAVA_VERSION                              JDK/base line (default 25)
#   BASE_IMAGE                                the base to build on (default amazoncorretto:${JAVA_VERSION}).
#                                             RELEASE POSTURE: pass a digest-pinned reference
#                                             (amazoncorretto@sha256:...) together with
#                                             OS_UPGRADE=false for a REPRODUCIBLE image — patching
#                                             then happens by bumping the digest deliberately.
#   OS_UPGRADE                                true (default): dnf upgrade to the latest releasever;
#                                             false: keep the base image's package versions
#   CACHEBUST                                 pass e.g. the build timestamp to re-run the upgrade
#                                             past Docker's layer cache (rebuilds pick up OS patches)
#   BASE_PACKAGES                             extra packages ALWAYS installed (default: none — the
#                                             production set is deliberately minimal)
#   DEV_PACKAGES                              the developer toolset (see default below)
#   INCLUDE_DEV_PACKAGES                      true: also install DEV_PACKAGES — build a debug/dev
#                                             flavor of ANY target, including the app images, for
#                                             troubleshooting environments (default false)
#   APP_USER / APP_GROUP / APP_UID / APP_GID  runtime identity (default javauser/javagroup, 1000/1000)
#   APP_HOME                                  the user's home + workdir + jar location (default /app)
#   APP_SHELL                                 the user's shell (default /bin/bash)
#   IMAGE_REVISION / IMAGE_VERSION / IMAGE_CREATED
#                                             OCI annotations, supplied by the pipeline from git:
#                                             rev-parse HEAD / the git-derived version / the commit
#                                             timestamp — they tie the image to the SBOM and
#                                             /actuator/info
#   CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL    optional extra trusted root CA, fetched at build time
#
# In a fleet setup the `base` stage is typically maintained as a SEPARATE, shared, hardened base
# image — published in two flavors from the same file (e.g. java-base:25-latest and, with
# INCLUDE_DEV_PACKAGES=true, java-base:25-dev-latest); it is inlined here so the reference is
# self-contained. The runtime JVM is configured via env vars, not baked in — see entrypoint.sh
# and the README's environment-variable table (JAVA_TOOL_OPTIONS / JVM_OPTS, GC selection).

ARG JAVA_VERSION=25
ARG BASE_IMAGE=amazoncorretto:${JAVA_VERSION}

## ---------------------------------------------------------------------------
## Stage: base — hardened runtime base (patched, non-root, minimal by default)
## ---------------------------------------------------------------------------
FROM ${BASE_IMAGE} AS base

ARG OS_UPGRADE=true
# Changing CACHEBUST invalidates this layer so the dnf upgrade actually runs on rebuilds.
ARG CACHEBUST=1
ARG BASE_PACKAGES=""
# The debug toolset: viewers/editors, file tools, process/system and network diagnostics.
ARG DEV_PACKAGES="less vim nano jq file findutils tar unzip procps-ng lsof strace iputils iproute nmap-ncat traceroute bind-utils tcpdump"
ARG INCLUDE_DEV_PACKAGES=false
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_UID=1000
ARG APP_GID=1000
ARG APP_HOME=/app
ARG APP_SHELL=/bin/bash

# shadow-utils (groupadd/useradd) is tooling for this layer only — removed at the end.
RUN set -eu; \
    DNF="dnf -y --setopt=install_weak_deps=False"; \
    if [ "${OS_UPGRADE}" = "true" ]; then DNF="${DNF} --releasever=latest"; ${DNF} upgrade; fi; \
    PACKAGES="${BASE_PACKAGES}"; \
    if [ "${INCLUDE_DEV_PACKAGES}" = "true" ]; then PACKAGES="${PACKAGES} ${DEV_PACKAGES}"; fi; \
    ${DNF} install shadow-utils ${PACKAGES}; \
    groupadd --system --gid "${APP_GID}" "${APP_GROUP}"; \
    useradd --uid "${APP_UID}" --gid "${APP_GID}" --no-user-group \
        --home-dir "${APP_HOME}" --create-home --shell "${APP_SHELL}" "${APP_USER}"; \
    chown -R "${APP_USER}:${APP_GROUP}" "${APP_HOME}"; \
    dnf -y remove shadow-utils; \
    dnf -y clean all; \
    rm -rf /var/cache/dnf

WORKDIR ${APP_HOME}

## ---------------------------------------------------------------------------
## Stage: builder — build the jar inside the image (only used by --target app).
## ---------------------------------------------------------------------------
FROM base AS builder
# The Maven wrapper needs tar to unpack the pinned Maven distribution.
RUN dnf -y --setopt=install_weak_deps=False install tar gzip && dnf -y clean all && rm -rf /var/cache/dnf
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

# Optionally trust a custom root CA (e.g. a corporate TLS-inspection or internal CA).
ARG CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL
RUN if [ -n "${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL}" ]; then \
        curl "${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL}" -o "/etc/pki/ca-trust/source/anchors/${CUSTOM_TRUSTED_ROOT_CA_CERTIFICATE_URL##*/}" && \
        update-ca-trust ; \
    fi

# 8080: application traffic; 6080: actuator (health/metrics/sbom) side port.
EXPOSE 8080 6080

# Local-run convenience only — Kubernetes ignores container HEALTHCHECKs (it uses probes).
HEALTHCHECK CMD curl --fail http://localhost:6080/actuator/health || exit 1

# entrypoint.sh reads APP_HOME to find the jar.
ENV APP_HOME=${APP_HOME}
COPY --chown=${APP_USER}:${APP_GROUP} --chmod=755 entrypoint.sh /entrypoint.sh
# NUMERIC user:group on purpose: Kubernetes' runAsNonRoot admission can only verify
# numeric UIDs — a name-based USER can get the pod rejected under strict PodSecurity.
USER ${APP_UID}:${APP_GID}

ENTRYPOINT ["/entrypoint.sh"]
CMD ["run"]

## ---------------------------------------------------------------------------
## Final targets — pick one with --target
## ---------------------------------------------------------------------------
# app: jar built in the `builder` stage above
FROM app-common AS app
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_HOME=/app
COPY --from=builder --chown=${APP_USER}:${APP_GROUP} /build/target/*.jar ${APP_HOME}/app.jar

# app-prebuilt: jar built on the host / in CI (`./mvnw package` first). With several
# jars in target/ COPY would silently pick one — fail loudly unless there is exactly one.
FROM app-common AS app-prebuilt
ARG APP_USER=javauser
ARG APP_GROUP=javagroup
ARG APP_HOME=/app
COPY --chown=${APP_USER}:${APP_GROUP} target/*.jar ${APP_HOME}/jars/
RUN count=$(ls ${APP_HOME}/jars/*.jar | wc -l) && \
    if [ "$count" -ne 1 ]; then \
        echo "ERROR: expected exactly 1 jar in target/, found $count — run './mvnw clean package' first" >&2; \
        exit 1; \
    fi && \
    mv ${APP_HOME}/jars/*.jar ${APP_HOME}/app.jar && rmdir ${APP_HOME}/jars
