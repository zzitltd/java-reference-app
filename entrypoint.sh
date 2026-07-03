#!/bin/bash
# Container entrypoint for reference-app.
#
# JVM configuration comes from the environment, in two complementary ways:
#   * JAVA_TOOL_OPTIONS — picked up by the JVM automatically (we don't touch it here).
#     This is where the deployment (e.g. the Helm chart) puts the standard flags:
#     -XX:MaxRAMPercentage, the GC choice (G1 vs ZGC), GC logging, etc.
#   * JVM_OPTS / JAVA_OPTS — appended explicitly below, for ad-hoc additions on top.
# Word-splitting of $JVM_OPTS/$JAVA_OPTS is intentional (they hold multiple flags).
#
#   run (default)  start the application
#   <anything else> executed as-is (e.g. `bash` for debugging)
set -euo pipefail

if [ "${1:-run}" = "run" ]; then
    # shellcheck disable=SC2086
    exec java ${JVM_OPTS:-} ${JAVA_OPTS:-} -jar "${APP_HOME:-/app}/app.jar"
fi
exec "$@"
