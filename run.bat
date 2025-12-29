@echo off
REM Script para compilar y ejecutar MicroFS Browser
REM Requiere JDK 8 o superior

setlocal enabledelayedexpansion

REM Buscar Java JDK
set "JAVA_FOUND="

REM Verificar si javac ya esta en PATH
where javac >nul 2>&1
if %ERRORLEVEL% == 0 (
    set "JAVA_FOUND=1"
    set "JAVAC=javac"
    set "JAVA_EXE=java"
    goto :found
)

REM Buscar en ubicaciones comunes
for %%D in (
    "C:\Program Files\Java"
    "C:\Program Files\Eclipse Adoptium"
    "C:\Program Files\Microsoft"
    "C:\Program Files\Amazon Corretto"
    "C:\Program Files\Zulu"
) do (
    if exist %%D (
        for /d %%J in (%%D\*jdk*) do (
            if exist "%%J\bin\javac.exe" (
                set "JAVA_HOME=%%J"
                set "JAVAC=%%J\bin\javac.exe"
                set "JAVA_EXE=%%J\bin\java.exe"
                set "JAVA_FOUND=1"
                goto :found
            )
        )
    )
)

REM Buscar en Program Files (x86)
for %%D in (
    "C:\Program Files (x86)\Java"
) do (
    if exist %%D (
        for /d %%J in (%%D\*jdk*) do (
            if exist "%%J\bin\javac.exe" (
                set "JAVA_HOME=%%J"
                set "JAVAC=%%J\bin\javac.exe"
                set "JAVA_EXE=%%J\bin\java.exe"
                set "JAVA_FOUND=1"
                goto :found
            )
        )
    )
)

:found
if not defined JAVA_FOUND (
    echo ============================================
    echo ERROR: No se encontro Java JDK instalado
    echo ============================================
    echo.
    echo Por favor, instala Java JDK desde:
    echo   - https://adoptium.net/ (Eclipse Temurin - Recomendado)
    echo   - https://www.oracle.com/java/technologies/downloads/
    echo.
    echo Despues de instalar, reinicia esta ventana y ejecuta de nuevo.
    echo.
    pause
    exit /b 1
)

echo Usando Java: %JAVA_HOME%
echo.
echo Compilando MicroFS Browser...

if not exist "bin" mkdir bin

"%JAVAC%" -encoding UTF-8 -d bin -sourcepath src src\microfs\MicroFSBrowser.java

if %ERRORLEVEL% == 0 (
    echo Compilacion exitosa!
    echo.
    echo Ejecutando MicroFS Browser...
    echo NOTA: Para acceder a dispositivos fisicos, ejecuta como Administrador
    echo.
    "%JAVA_EXE%" -cp bin microfs.MicroFSBrowser
) else (
    echo.
    echo Error de compilacion
    pause
)
