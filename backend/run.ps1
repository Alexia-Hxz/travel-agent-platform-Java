# 启动后端：读取同目录 .env，逐项设为「本进程」环境变量，再运行打包好的 jar。
#
# 用法：  .\run.ps1
# 前置：  JDK 17+（本机 JAVA_HOME 若是 JDK 11，脚本会尝试 IntelliJ 自带的 JBR 21）

$ErrorActionPreference = 'Stop'
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$envFile = Join-Path $here '.env'

if (-not (Test-Path $envFile)) {
    Write-Host "缺少 $envFile。请复制 .env.example 为 .env 并填入 DASHSCOPE_API_KEY / DB_PASSWORD。" -ForegroundColor Yellow
    exit 1
}

Get-Content $envFile | ForEach-Object {
    if ($_ -match '^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*)$') {
        [Environment]::SetEnvironmentVariable($matches[1], $matches[2].Trim(), 'Process')
    }
}

function Get-JavaMajor($exe) {
    try {
        $raw = & $exe -version 2>&1 | Select-Object -First 1
        if ($raw -match '"(\d+)') { return [int]$matches[1] }
    } catch { }
    return 0
}

$java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin\java.exe' } else { 'java' }
if ((Get-JavaMajor $java) -lt 17) {
    $fallback = 'D:\IntelliJ IDEA 2025.2.4\jbr\bin\java.exe'
    if ((Test-Path $fallback) -and ((Get-JavaMajor $fallback) -ge 17)) {
        Write-Host "JAVA_HOME 指向的 JDK 低于 17，改用 IntelliJ 的 JBR。" -ForegroundColor Yellow
        $java = $fallback
    } else {
        Write-Host "需要 JDK 17+，请安装后把 JAVA_HOME 指过去。" -ForegroundColor Red
        exit 1
    }
}

$jar = Join-Path $here 'target\travel-agent-backend-0.1.0.jar'
if (-not (Test-Path $jar)) {
    Write-Host "找不到 $jar，先执行： mvn -DskipTests package" -ForegroundColor Yellow
    exit 1
}

Write-Host "启动：$jar" -ForegroundColor Green
& $java -jar $jar
