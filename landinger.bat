@echo off

REM @TODO: Upload to Maven Central

SET LANDINGER_VERSION=v0.1
set HOME=%HOMEDRIVE%%HOMEPATH%
set LANDINGER_FOLDER=%HOME%\.landinger
set LANDINGER_JAR=%LANDINGER_FOLDER%\landinger-%LANDINGER_VERSION%.jar
MKDIR "%LANDINGER_FOLDER%" > NUL 2> NUL
REM echo %LANDINGER_JAR%
if not exist "%LANDINGER_JAR%" (
    powershell -Command "(New-Object Net.WebClient).DownloadFile('https://github.com/soywiz/landinger/releases/download/%LANDINGER_VERSION%/landinger.jar', '%LANDINGER_JAR%')"
) else (
    rem file exists
)
java -jar "%LANDINGER_JAR%" %*
