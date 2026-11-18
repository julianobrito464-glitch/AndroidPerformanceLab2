@echo off
setlocal
set "JAR=%~dp0gradle\wrapper\gradle-wrapper.jar"
if not exist "%JAR%" (
  echo gradle-wrapper.jar ausente; baixando...
  powershell -NoProfile -Command "[Net.ServicePointManager]::SecurityProtocol='Tls12'; Invoke-WebRequest -UseBasicParsing 'https://raw.githubusercontent.com/gradle/gradle/v8.11.1/gradle/wrapper/gradle-wrapper.jar' -OutFile '%JAR%'"
  if errorlevel 1 ( echo Falha no download. Rode: gradle wrapper --gradle-version 8.11.1 & exit /b 1 )
)
if defined JAVA_HOME ( set "JAVACMD=%JAVA_HOME%\bin\java.exe" ) else ( set "JAVACMD=java" )
"%JAVACMD%" -Xmx64m -Dorg.gradle.appname=gradlew -classpath "%JAR%" org.gradle.wrapper.GradleWrapperMain %*
exit /b %ERRORLEVEL%
