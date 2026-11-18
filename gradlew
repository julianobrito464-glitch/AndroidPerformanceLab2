#!/bin/sh
# Launcher do Gradle Wrapper. Se gradle-wrapper.jar não existir, baixa a versão oficial 8.11.1 do repositório do Gradle.
APP_HOME=$(cd "$(dirname "$0")" && pwd -P)
JAR="$APP_HOME/gradle/wrapper/gradle-wrapper.jar"
URL="https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar"
if [ ! -s "$JAR" ]; then
  echo "gradle-wrapper.jar ausente; baixando de $URL" >&2
  mkdir -p "$(dirname "$JAR")"
  if command -v curl >/dev/null 2>&1; then curl -fsSL "$URL" -o "$JAR"
  elif command -v wget >/dev/null 2>&1; then wget -q "$URL" -O "$JAR"
  else echo "Instale curl ou wget, ou rode: gradle wrapper --gradle-version 8.11.1" >&2; exit 1; fi \
    || { rm -f "$JAR"; echo "Falha no download. Rode: gradle wrapper --gradle-version 8.11.1" >&2; exit 1; }
fi
if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then JAVACMD="$JAVA_HOME/bin/java"; else JAVACMD=java; fi
exec "$JAVACMD" -Xmx64m -Xms64m -Dorg.gradle.appname=gradlew -classpath "$JAR" org.gradle.wrapper.GradleWrapperMain "$@"
