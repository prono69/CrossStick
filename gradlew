#!/bin/sh
# Minimal gradle wrapper starter
# Finds the JVM and runs Gradle with the wrapper properties
#
# Determine the Java command to use to start the JVM.
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
else
    JAVACMD="java"
fi

DIRNAME=`dirname "$0"`
APP_BASE_NAME=`basename "$0"`
CLASSPATH=$DIRNAME/gradle/wrapper/gradle-wrapper.jar

exec "$JAVACMD" \
    -classpath "$CLASSPATH" \
    org.gradle.wrapper.GradleWrapperMain \
    "$@"
