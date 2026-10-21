@echo off
REM Compila MicroFS Browser y genera MicroFSBrowser.jar
REM Requiere Java JDK 8 o superior

setlocal enabledelayedexpansion

set "JAVAC="
set "JAVA_EXE="
set "JAR_TOOL="

REM 1) Herramientas del JDK disponibles en PATH
where javac >nul 2>&1
if %ERRORLEVEL% == 0 (
    set "JAVAC=javac"
    set "JAVA_EXE=java"
    set "JAR_TOOL=jar"
    goto :found
)

REM 2) Buscar un JDK en ubicaciones habituales
for %%D in (
    "%ProgramFiles%\Java"
    "%ProgramFiles%\Eclipse Adoptium"
    "%ProgramFiles%\Microsoft"
    "%ProgramFiles%\Amazon Corretto"
    "%ProgramFiles%\Zulu"
    "%ProgramFiles%\BellSoft"
    "%ProgramFiles(x86)%\Java"
    "%LocalAppData%\Programs\Eclipse Adoptium"
) do (
    if exist "%%~D" (
        call :probe "%%~D"
        if defined JAVAC goto :found
    )
)

REM 3) Usar JAVA_HOME si apunta a un JDK completo
if defined JAVA_HOME (
    if exist "%JAVA_HOME%\bin\javac.exe" (
        set "JAVAC=%JAVA_HOME%\bin\javac.exe"
        set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
        set "JAR_TOOL=%JAVA_HOME%\bin\jar.exe"
        goto :found
    )
)

echo ============================================
echo ERROR: No se encontro Java JDK (javac)
echo ============================================
echo.
echo Nota: tener solo el JRE no basta, se necesita el JDK.
echo Instala Java JDK desde https://adoptium.net/ y reinicia la consola.
echo.
pause
exit /b 1

:probe
for /d %%J in ("%~1\*jdk*" "%~1\*jre*" ) do (
    if exist "%%~J\bin\javac.exe" (
        set "JAVAC=%%~J\bin\javac.exe"
        set "JAVA_EXE=%%~J\bin\java.exe"
        set "JAR_TOOL=%%~J\bin\jar.exe"
    )
)
exit /b 0

:found
if not exist "bin" mkdir bin

"%JAVAC%" -version
echo.
echo Compilando MicroFS Browser...
"%JAVAC%" -encoding UTF-8 -d bin -sourcepath src src\microfs\*.java
if not %ERRORLEVEL% == 0 (
    echo.
    echo Error de compilacion
    pause
    exit /b 1
)

echo Generando MicroFSBrowser.jar...
"%JAR_TOOL%" cfm MicroFSBrowser.jar MANIFEST.MF -C bin .
if not %ERRORLEVEL% == 0 (
    echo.
    echo Error generando el JAR
    pause
    exit /b 1
)

echo Compilacion exitosa!
echo.
echo Ejecutando MicroFS Browser...
echo NOTA: para abrir discos fisicos (no imagenes) usa MicroFSBrowser_Admin.bat
echo.
"%JAVA_EXE%" -jar MicroFSBrowser.jar

if not %ERRORLEVEL% == 0 (
    echo.
    echo La aplicacion termino con error ^(%ERRORLEVEL%^).
    pause
)
