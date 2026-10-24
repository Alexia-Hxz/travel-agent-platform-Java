@echo off
title 旅迹AI - 前端服务 5173
cd /d "%~dp0frontend"

if not exist "node_modules" (
    echo.
    echo [错误] 找不到前端依赖：frontend\node_modules
    echo        请先在 frontend 文件夹里执行一次：
    echo            npm install
    echo.
    pause
    exit /b 1
)

if exist "D:\nodejs\node.exe" set "PATH=D:\nodejs;%PATH%"

echo ============================================================
echo   旅迹AI - 前端服务
echo.
echo   界面地址： http://127.0.0.1:5173
echo.
echo   关闭这个窗口即可停止前端。
echo ============================================================
echo.

call npm run dev

echo.
echo 前端已停止。
pause
