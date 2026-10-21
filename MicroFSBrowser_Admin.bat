@echo off
:: Ejecuta MicroFS Browser con privilegios de administrador.
:: Necesario para abrir discos fisicos (\\.\PhysicalDriveN). Con imagenes .img no hace falta.

>nul 2>&1 "%SYSTEMROOT%\system32\cacls.exe" "%SYSTEMROOT%\system32\config\system"

if '%errorlevel%' NEQ '0' (
    echo Solicitando permisos de administrador...
    goto UACPrompt
) else ( goto gotAdmin )

:UACPrompt
    echo Set UAC = CreateObject^("Shell.Application"^) > "%temp%\getadmin.vbs"
    echo UAC.ShellExecute "%~s0", "", "", "runas", 1 >> "%temp%\getadmin.vbs"
    "%temp%\getadmin.vbs"
    exit /B

:gotAdmin
    if exist "%temp%\getadmin.vbs" ( del "%temp%\getadmin.vbs" )
    pushd "%CD%"
    CD /D "%~dp0"

echo ============================================
echo   MicroFS Browser - Modo Administrador
echo ============================================
echo.

if not exist "MicroFSBrowser.jar" (
    echo ERROR: No se encontro MicroFSBrowser.jar.
    echo Ejecuta primero run.bat como usuario normal.
    echo.
    pause
    exit /b 1
)

java -jar MicroFSBrowser.jar

if not %ERRORLEVEL% == 0 (
    echo.
    echo La aplicacion termino con error ^(%ERRORLEVEL%^).
)
pause
