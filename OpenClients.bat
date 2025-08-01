@echo off
setlocal EnableDelayedExpansion

REM === Configuration ===
set "CREDENTIALS_ROOT=C:\Games\Credentials and mails"
set "RUNELITE_CONFIG_FOLDER=%USERPROFILE%\.runelite"
set "JAR_DIR=C:\Games\MicrobotFork\runelite-client\target"
set "JAR_FILE="

REM === Find first valid microbot-*.jar file ===
for %%J in ("%JAR_DIR%\microbot-*.jar") do (
    if not defined JAR_FILE set "JAR_FILE=%%~fJ"
)
if not defined JAR_FILE (
    echo ❌ No matching microbot-*.jar file found in %JAR_DIR%.
    goto :eof
)
echo ✅ Found JAR: %JAR_FILE%
echo.

REM === Only search credentials.properties inside subfolders ===
set count=0
for /d %%D in ("%CREDENTIALS_ROOT%\*") do (
    if exist "%%D\credentials.properties" (
        set /a count+=1
        set "CRED[!count!]=%%D\credentials.properties"
        echo Found credentials !count!: %%D\credentials.properties
    )
)

if !count! lss 1 (
    echo ❌ No credentials.properties files found in subfolders.
    goto :eof
)

echo.
echo 🚀 Launching clients...

REM === Launch each client with delay ===
for /L %%I in (1,1,!count!) do (
    call set "CURRENT_CRED=%%CRED[%%I]%%"
    echo ------------------------------------------------------------
    echo 🔄 Client %%I: !CURRENT_CRED!
    copy /Y "!CURRENT_CRED!" "%RUNELITE_CONFIG_FOLDER%\credentials.properties" >nul
    start "" java -jar "!JAR_FILE!"
    echo ⏳ Waiting 5 seconds before launching the next...
    timeout /t 5 /nobreak >nul
)

echo ------------------------------------------------------------
echo ✅ All clients launched: !count!
pause
endlocal
