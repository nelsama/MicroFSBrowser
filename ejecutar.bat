@echo off
REM Ejecuta MicroFS Browser SIN compilar, usando MicroFSBrowser.jar.

setlocal enabledelayedexpansion

set "JAVA_EXE="

where java >nul 2>&1
if %ERRORLEVEL% == 0 (
    set "JAVA_EXE=java"
) else if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\java.exe" set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
)

if not defined JAVA_EXE (
    echo ERROR: No se encontro Java en el PATH ni en JAVA_HOME.
    echo Instala Java 8 o superior desde https://adoptium.net/
    pause
    exit /b 1
)

if not exist "MicroFSBrowser.jar" (
    echo ERROR: No se encontro MicroFSBrowser.jar.
    echo Ejecuta una vez:  run.bat
    pause
    exit /b 1
)

"%JAVA_EXE%" -jar MicroFSBrowser.jar

if not %ERRORLEVEL% == 0 (
    echo.
    echo La aplicacion termino con error ^(%ERRORLEVEL%^).
    pause
)
