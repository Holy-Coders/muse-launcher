#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
PUCK_JAVA_HOME="${JAVA_HOME:-$(/usr/libexec/java_home -v 21)}"
if [ -d /opt/homebrew/opt/expat/lib ]; then
    export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib${DYLD_LIBRARY_PATH:+:$DYLD_LIBRARY_PATH}"
fi
# Invoke the same pinned Gradle wrapper directly: macOS strips DYLD variables
# when traversing the system shell used by gradlew, breaking current Brew Python.
exec "$PUCK_JAVA_HOME/bin/java" -Xmx64m -Dorg.gradle.appname=gradlew \
    -classpath gradle/wrapper/gradle-wrapper.jar org.gradle.wrapper.GradleWrapperMain "$@"
