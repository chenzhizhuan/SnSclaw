@ECHO OFF
TITLE SnSclaw - Local Installer Pipeline
PUSHD "%~dp0"
SET NODE_OPTIONS=--max-old-space-size=8192
SET BUILD_MODE=local
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0pipeline_local_main.ps1"
POPD
PAUSE
