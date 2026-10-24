@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo  Publish travel-agent-platform-Java
echo ============================================
echo.

where powershell >nul 2>nul
if errorlevel 1 (
  echo [ERROR] Windows PowerShell not found.
  pause
  exit /b 1
)

powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0tools\publish-to-github.ps1"
set "CODE=%ERRORLEVEL%"

echo.
if not "%CODE%"=="0" (
  echo [FAILED] exit code %CODE%
  echo Check that GitHub CLI is logged in:
  echo   "C:\Program Files\GitHub CLI\gh.exe" auth status
) else (
  echo [DONE] https://github.com/Alexia-Hxz/travel-agent-platform-Java
)
echo.
pause
exit /b %CODE%
