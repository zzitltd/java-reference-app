#!/bin/sh
# Container entrypoint for reference-app. POSIX sh: the Alpine flavor has no bash.
#
# JVM configuration comes from the environment, in two complementary ways:
#   * JAVA_TOOL_OPTIONS — picked up by the JVM automatically (we don't touch it here).
#     This is where the deployment (e.g. the Helm chart) puts the standard flags:
#     -XX:MaxRAMPercentage, the GC choice (G1 vs ZGC), GC logging, etc.
#   * JVM_OPTS / JAVA_OPTS — appended explicitly below, for ad-hoc additions on top.
# Word-splitting of $JVM_OPTS/$JAVA_OPTS is intentional (they hold multiple flags).
#
# The JDK AOT cache (app.aot, produced by the image build's training run) is passed when present.
# A cache the JVM cannot use (different GC / pointer mode than the training run) is skipped with
# a warning and the app starts normally, just slower. SPRING_AOT (image default false) selects
# Spring's build-time-generated bean registrations; see the Dockerfile header for the constraints.
#
#   run (default)  start the application
#   <anything else> executed as-is (e.g. `sh` for debugging)
set -eu

APP_HOME="${APP_HOME:-/app}"
if [ "${1:-run}" = "run" ]; then
    AOT_CACHE=""
    if [ -s "${APP_HOME}/app.aot" ]; then AOT_CACHE="-XX:AOTCache=${APP_HOME}/app.aot"; fi
    # shellcheck disable=SC2086
    exec java ${AOT_CACHE} -Dspring.aot.enabled="${SPRING_AOT:-false}" ${JVM_OPTS:-} ${JAVA_OPTS:-} -jar "${APP_HOME}/app.jar"
fi
exec "$@"
