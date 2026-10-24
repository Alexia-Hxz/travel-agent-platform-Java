@echo off
title 旅迹AI - 停止服务
setlocal

echo 正在停止前端 5173 ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":5173" ^| findstr "LISTENING"') do taskkill /f /pid %%a >nul 2>&1

echo 正在停止后端 8001 ...
for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":8001" ^| findstr "LISTENING"') do taskkill /f /pid %%a >nul 2>&1

echo 正在清理残留的 Java 后端进程 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'java.exe' -and $_.CommandLine -match 'travel-agent-backend' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

echo 正在清理占用 8001 的 Python 后端（如果有） ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'python.exe' -and ($_.CommandLine -match 'uvicorn' -or $_.CommandLine -match 'app.main') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
echo 正在清理前端的 node 进程 ...
powershell -NoProfile -ExecutionPolicy Bypass -Command "Get-CimInstance Win32_Process | Where-Object { $_.Name -eq 'node.exe' -and ($_.CommandLine -match 'travel-agent-platform-java') } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"

echo.
echo 完成。
echo.
echo 提示：直接关闭那两个黑窗口也能停止服务。
pause
