@echo off
rem JARVIS desktop launcher: double-click to start. Builds on first run (about a minute),
rem then starts in seconds. Uses Android Studio's bundled Java if JAVA_HOME isn't set.
rem Secrets come from %USERPROFILE%\.gradle\gradle.properties (see desktop\README.md).
setlocal
if "%JAVA_HOME%"=="" if exist "C:\Program Files\Android\Android Studio\jbr\bin\java.exe" set "JAVA_HOME=C:\Program Files\Android\Android Studio\jbr"
cd /d "%~dp0.."
call "%~dp0..\gradlew.bat" :desktop:run --quiet
if errorlevel 1 (
  echo.
  echo JARVIS could not start. See the messages above.
  pause
)
