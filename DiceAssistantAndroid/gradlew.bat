@echo off
set GRADLE_VERSION=8.13
where gradle >nul 2>nul
if %ERRORLEVEL% EQU 0 (
  gradle %*
  exit /b %ERRORLEVEL%
)
set DIST_DIR=%USERPROFILE%\.gradle\manual-dists\gradle-%GRADLE_VERSION%
set GRADLE_EXE=%DIST_DIR%\bin\gradle.bat
if not exist "%GRADLE_EXE%" (
  set ZIP_FILE=%TEMP%\gradle-%GRADLE_VERSION%-bin.zip
  echo Descargando Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%TEMP%\gradle-%GRADLE_VERSION%-bin.zip'; Expand-Archive -Force '%TEMP%\gradle-%GRADLE_VERSION%-bin.zip' '%USERPROFILE%\.gradle\manual-dists'"
  if not exist "%GRADLE_EXE%" exit /b 1
)
call "%GRADLE_EXE%" %*
