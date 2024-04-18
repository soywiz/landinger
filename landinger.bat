@ECHO OFF
CALL %~dp0\gradlew.bat --project-dir=%~dp0 run --args="%* "
REM CALL %~dp0\gradlew.bat --project-dir=. run --args="%* "