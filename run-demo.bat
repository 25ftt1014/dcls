@echo off
setlocal
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 or later is required. Install a JDK and try again.
  pause
  exit /b 1
)
java -version 2>&1 | findstr /R /C:"version \"2[1-9]\." >nul
if errorlevel 1 (
  echo This app needs Java 21 or later. Check your installed Java version.
  pause
  exit /b 1
)
rem Prefer a JDK 21+ for Maven builds. Probes well-known installs even when
rem JAVA_HOME is set but stale (points at an older JDK), newest wins.
if exist "C:\Program Files\Java\jdk-21" set "JAVA_HOME=C:\Program Files\Java\jdk-21"
if exist "C:\Program Files\Java\jdk-26" set "JAVA_HOME=C:\Program Files\Java\jdk-26"
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
