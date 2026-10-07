@echo off
setlocal
where java >nul 2>nul
if errorlevel 1 (
  echo Java 21 or later is required. Install a JDK and try again.
  pause
  exit /b 1
)
java -version 2>&1 | findstr /R /C:"version \"21\." >nul
if errorlevel 1 (
  echo This app is built for Java 21. Check your installed Java version.
  pause
  exit /b 1
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
