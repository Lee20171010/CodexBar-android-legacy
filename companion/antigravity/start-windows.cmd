@echo off
setlocal
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 goto :missing_node
where npm >nul 2>nul
if errorlevel 1 goto :missing_node

for /f %%V in ('node -p "process.versions.node.split('.')[0]"') do set "NODE_MAJOR=%%V"
if %NODE_MAJOR% LSS 20 goto :missing_node

node -e "require.resolve('qrcode-terminal')" >nul 2>nul
if errorlevel 1 (
  echo Installing the pinned companion dependencies...
  call npm ci --omit=dev
  if errorlevel 1 goto :failed
)

call npm start -- %*
if errorlevel 1 goto :failed
exit /b 0

:missing_node
echo CodexBar Antigravity companion requires Node.js 20 or newer.
echo Install the current Node.js LTS release, then run this file again.
goto :failed

:failed
echo.
echo The Antigravity companion could not start. Review the message above.
pause
exit /b 1
