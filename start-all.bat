@echo off
title 旅迹AI - 一键启动
setlocal
cd /d "%~dp0"

echo ============================================================
echo   旅迹AI (Java)  一键启动
echo   后端 8001      前端 5173
echo ============================================================
echo.

if not exist "backend\pom.xml" (
    echo [错误] 找不到后端工程 backend\pom.xml
    echo        请确认本项目文件夹没有被移动过。
    echo.
    pause
    exit /b 1
)

if not exist "frontend\node_modules" (
    echo [错误] 找不到前端依赖 frontend\node_modules
    echo        请先在 frontend 文件夹里执行一次 npm install
    echo.
    pause
    exit /b 1
)

netstat -ano | findstr ":8001" | findstr "LISTENING" >nul
if not errorlevel 1 (
    echo [提示] 8001 端口已经有服务在跑。
    echo        如果是上次没关掉的旧进程，请先运行【停止旅迹AI】再启动。
    echo.
)

echo 正在打开后端窗口 ...
start "旅迹AI - 后端 8001" cmd /k start-backend.bat

echo 正在打开前端窗口 ...
start "旅迹AI - 前端 5173" cmd /k start-frontend.bat

echo.
echo 等待后端真正就绪（首次启动要打包，最多等 180 秒）...
set n=0
:waitbe
set /a n+=1
REM 不是"端口在听"就算好，而是健康检查真的返回 200，避免浏览器先开、登录报 Failed to fetch
powershell -NoProfile -ExecutionPolicy Bypass -Command "try{$r=Invoke-WebRequest 'http://127.0.0.1:8001/api/v1/health' -UseBasicParsing -TimeoutSec 2; if($r.StatusCode -eq 200){exit 0}else{exit 1}}catch{exit 1}" >nul 2>&1
if not errorlevel 1 goto be_ready
if %n% geq 90 goto be_fail
ping -n 2 127.0.0.1 >nul
goto waitbe

:be_fail
echo.
echo [失败] 等待 180 秒后，后端健康检查仍未通过。
echo        请按顺序检查：
echo          1. MySQL 服务是否已启动（Windows 服务里的 MySQL）
echo          2. 后端窗口里有没有红色报错（数据库连不上、端口被占用）
echo          3. 手动打开 http://127.0.0.1:8001/api/v1/health 看返回什么
echo.
pause
exit /b 1

:be_ready
echo 后端已就绪，继续等待前端 ...
set n=0
:waitfe
set /a n+=1
netstat -ano | findstr ":5173" | findstr "LISTENING" >nul
if not errorlevel 1 goto fe_ready
if %n% geq 60 goto fe_timeout
ping -n 2 127.0.0.1 >nul
goto waitfe

:fe_timeout
echo.
echo [提示] 前端 60 秒内还没起来，仍然为你打开浏览器。
goto open

:fe_ready
echo 前端已就绪。
echo.

:open
start "" http://127.0.0.1:5173/

echo 浏览器已打开 http://127.0.0.1:5173
echo 首次使用请先注册，或用原 Python 版的老账号登录。
echo.
echo 这个窗口可以关闭，两个服务会继续运行。
echo 要停止服务，请运行【停止旅迹AI】或直接关掉那两个黑窗口。
pause
exit /b 0
