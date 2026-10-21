@echo off
REM Ejecuta MicroFS Browser (sin elevacion) desde MicroFSBrowser.jar
cd /d "%~dp0"

if not exist "MicroFSBrowser.jar" (
    echo ERROR: No se encontro MicroFSBrowser.jar.
    echo Ejecuta una vez:  run.bat
    pause
    exit /b 1
)

java -jar MicroFSBrowser.jar

if not %ERRORLEVEL% == 0 (
    echo.
    echo La aplicacion termino con error ^(%ERRORLEVEL%^).
    pause
)
