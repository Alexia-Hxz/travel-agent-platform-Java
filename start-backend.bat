@echo off
title 旅迹AI - 后端服务 8001
setlocal
cd /d "%~dp0backend"

echo ============================================================
echo   旅迹AI - 后端服务 (Java)
echo.
echo   接口地址： http://127.0.0.1:8001
echo   健康检查： http://127.0.0.1:8001/api/v1/health
echo.
echo   启动需要十几秒，看到 Started TravelAgentApplication 就是好了。
echo   关闭这个窗口即可停止后端。
echo ============================================================
echo.

netstat -ano | findstr ":8001" | findstr "LISTENING" >nul
if not errorlevel 1 (
    echo [提示] 8001 端口已经被别的程序占用。
    echo        如果页面数据不对，很可能连的是旧服务，请先运行【停止旅迹AI】再启动。
    echo.
)
set "JAR=target\travel-agent-backend-0.1.0.jar"
set "JAVAEXE="
set "JDK_HOME="

REM ---------- 找 JDK 17+（JAVA_HOME → IntelliJ JBR → 常见安装位置） ----------
REM 注意：%~dp0 是本 bat 所在的“项目根”，两个 ps1 在 backend 子目录里。
for /f "usebackq delims=" %%v in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend\find-java.ps1"`) do set "JAVAEXE=%%v"

if defined JAVAEXE if not exist "%JAVAEXE%" set "JAVAEXE="

if not defined JAVAEXE (
    echo [错误] 需要 JDK 17 或更高版本来运行这个后端。
    echo.
    echo        当前 JAVA_HOME = %JAVA_HOME%
    echo        请安装 JDK 17 后把 JAVA_HOME 指过去；或者用记事本打开
    echo        backend\find-java.ps1，把你的 JDK 17 路径加到候选列表里。
    echo.
    pause
    exit /b 1
)

for %%d in ("%JAVAEXE%\..\..") do set "JDK_HOME=%%~fd"
echo 使用 Java：%JAVAEXE%
echo.

REM ---------- 读 .env（跳过 # 注释行；值里的 & 和 | 在引号内是安全的） ----------
if exist ".env" (
    for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env") do (
        if not "%%~A"=="" set "%%A=%%B"
    )
) else (
    echo [提示] 没有找到 backend\.env，将使用 application.yml 的默认值。
    echo        模型相关功能需要 DASHSCOPE_API_KEY，请复制 .env.example 为 .env 后填写。
    echo.
)

REM ---------- jar 不存在、或源码比 jar 新时自动打包 ----------
set "NEEDBUILD=0"
for /f "usebackq delims=" %%r in (`powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0backend\needs-build.ps1" -Jar "%JAR%" -Source "src"`) do set "NEEDBUILD=%%r"

if "%NEEDBUILD%"=="1" (
    call :build
    if errorlevel 1 (
        pause
        exit /b 1
    )
)

echo 启动中 ...
echo.
"%JAVAEXE%" -jar "%JAR%"

echo.
echo 后端已停止。
pause
exit /b 0

REM ============================================================
REM  :build  用 Maven 打包（先确保 JAVA_HOME 指向 17+）
REM ============================================================
:build
echo 正在用 Maven 打包（首次约 1 分钟）...
echo.
set "MAVEN=D:\maven\bin\mvn.cmd"
if not exist "%MAVEN%" set "MAVEN=mvn"
set "JAVA_HOME=%JDK_HOME%"
call "%MAVEN%" -q -B -DskipTests package
if errorlevel 1 (
    echo.
    echo [错误] 打包失败，请查看上面的 Maven 输出。
    exit /b 1
)
echo.
exit /b 0
