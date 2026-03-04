#!/usr/bin/env sh

# Minimal Gradle wrapper script (Linux/macOS).
# Uses bundled wrapper jars to pin Gradle version for CI stability.

set -eu

APP_HOME=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

CLASSPATH="$APP_HOME/gradle/wrapper/gradle-wrapper.jar:$APP_HOME/gradle/wrapper/gradle-wrapper-shared.jar"

JAVA_CMD=${JAVA_HOME:-}/bin/java
if [ ! -x "$JAVA_CMD" ]; then
  JAVA_CMD=java
fi

exec "$JAVA_CMD" -classpath "$CLASSPATH" org.gradle.wrapper.GradleWrapperMain "$@"
