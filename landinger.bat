@echo off

REM @TODO: Upload to Maven Central

SET LANDINGER_VERSION=v0.1
SET LANDINGER_SHA1=e7e016b9b171ab9b0bee02e6c61e39c8adeb893e
set HOME=%HOMEDRIVE%%HOMEPATH%
set LANDINGER_FOLDER=%HOME%\.landinger
set LANDINGER_JAR=%LANDINGER_FOLDER%\landinger-%LANDINGER_VERSION%.jar
MKDIR "%LANDINGER_FOLDER%" > NUL 2> NUL
if not exist "%LANDINGER_JAR%" (
    powershell -Command "(New-Object Net.WebClient).DownloadFile('https://github.com/soywiz/landinger/releases/download/%LANDINGER_VERSION%/landinger.jar', '%LANDINGER_JAR%.temp')"
    CertUtil -hashfile "%LANDINGER_JAR%.temp" SHA1 | find /i /v "sha1" | find /i /v "certutil" > "%LANDINGER_JAR%.temp.sha1"
    FOR /F "tokens=*" %%g IN ('type %LANDINGER_JAR%.temp.sha1') do (SET SHA1=%%g)
    if "%SHA1%"=="%LANDINGER_SHA1%" (
        COPY /Y "%LANDINGER_JAR%.temp" "%LANDINGER_JAR%"
        echo DONE
    ) else (
        echo "Error downloading file expected %LANDINGER_SHA1% but found %SHA1%"
        exit /b
    )
) else (
    rem file exists
)

java -jar "%LANDINGER_JAR%" %*
