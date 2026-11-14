@echo off
REM Publica una version nueva (ver release.ps1).
REM Uso: release.bat X.Y.Z ["notas de la version"]
if "%~1"=="" (
  echo Uso: release.bat X.Y.Z ["notas de la version"]
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0release.ps1" -Version "%~1" -Notes "%~2"
