# 判断是否需要重新打包：jar 不存在、或 src 下有文件比 jar 新 → 输出 1，否则输出 0。
#
# 由 start-backend.bat 调用：
#   for /f "usebackq delims=" %%r in (`powershell -File needs-build.ps1`) do set "NEEDBUILD=%%r"

param(
    [string]$Jar = 'target\travel-agent-backend-0.1.0.jar',
    [string]$Source = 'src'
)

if (-not (Test-Path -LiteralPath $Jar)) {
    Write-Output '1'
    exit 0
}

$jarItem = Get-Item -LiteralPath $Jar
$newest = Get-ChildItem -LiteralPath $Source -Recurse -File |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($newest -and $newest.LastWriteTime -gt $jarItem.LastWriteTime) {
    Write-Output '1'
} else {
    Write-Output '0'
}
