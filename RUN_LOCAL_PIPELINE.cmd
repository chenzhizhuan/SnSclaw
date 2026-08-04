@ECHO OFF
REM Windows desktop installer build entry point.
REM Arguments are forwarded to scripts\build-desktop.ps1, e.g.
REM   RUN_LOCAL_PIPELINE.cmd -Fast
REM   RUN_LOCAL_PIPELINE.cmd -From installer
REM See scripts\README.md for the full switch reference.
REM
REM NODE_OPTIONS / BUILD_MODE are deliberately NOT set here -- the script owns
REM all environment setup. Two sources of truth is how BUILD_MODE drifts.
TITLE SnSclaw - Windows Desktop Installer Build
PUSHD "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\build-desktop.ps1" %*
SET RC=%ERRORLEVEL%
POPD
PAUSE
EXIT /B %RC%
