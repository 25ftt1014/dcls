@echo off
setlocal
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 or later is required. Install a JDK and try again.
  pause
  exit /b 1
)
for /f "tokens=2 delims==" %%V in ('java -XshowSettings:properties -version 2^>^&1 ^| findstr /C:"java.specification.version"') do set "JAVA_VERSION=%%V"
if not defined JAVA_VERSION (
  echo Could not determine the Java version. Check your installed JDK.
  pause
  exit /b 1
)
for /f "tokens=1 delims=." %%V in ("%JAVA_VERSION%") do set /a JAVA_MAJOR=%%V
if %JAVA_MAJOR% LSS 21 (
  echo This app needs Java 21 or later. Check your installed Java version.
  pause
  exit /b 1
)
rem The project targets Java 21; use the JDK selected by JAVA_HOME/PATH.
rem Demo mode by default (resettable in-memory data). To use MySQL instead,
rem set DCLS_DB_URL/DCLS_DB_USER/DCLS_DB_PASSWORD, or set DCLS_DEMO_MODE=false.
if "%DCLS_DB_URL%"=="" (
  if /I not "%DCLS_DEMO_MODE%"=="false" set "DCLS_DEMO_MODE=true"
)
if exist "%~dp0target\distribution\delivery-logistics-simulator-1.0.0.jar" (
  pushd "%~dp0target\distribution"
  java -jar delivery-logistics-simulator-1.0.0.jar
  popd
) else (
  where mvn >nul 2>nul
  if errorlevel 1 (
    echo Build the app first with Maven, or run mvn javafx:run from the project folder.
    pause
    exit /b 1
  )
  pushd "%~dp0"
  call mvn javafx:run
  popd
)
if errorlevel 1 pause
endlocal
