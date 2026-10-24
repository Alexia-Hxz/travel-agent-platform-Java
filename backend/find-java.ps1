# 找出一个能运行本项目的 Java 17+，把它的绝对路径打印到 stdout。
#
# 由 start-backend.bat 调用：
#   for /f "usebackq delims=" %%v in (`powershell -File find-java.ps1`) do set "JAVAEXE=%%v"
#
# 顺序：JAVA_HOME → IntelliJ 自带 JBR → 常见 JDK 17 安装位置。
# 找不到时返回退出码 1，bat 会给出可读的提示。
#
# 注意：不要设置 $ErrorActionPreference='SilentlyContinue'。java 的 -XshowSettings
# 把属性写到 stderr，一旦把错误流静默掉，`2>&1` 也拿不到内容，版本就永远读不到。

$candidates = @()
if ($env:JAVA_HOME) {
    $candidates += (Join-Path $env:JAVA_HOME 'bin\java.exe')
}
$candidates += 'D:\IntelliJ IDEA 2025.2.4\jbr\bin\java.exe'
$candidates += 'C:\Program Files\Java\jdk-17\bin\java.exe'
$candidates += 'C:\Program Files\Eclipse Adoptium\jdk-17\bin\java.exe'

foreach ($candidate in $candidates) {
    if (-not (Test-Path -LiteralPath $candidate)) { continue }

    $line = (& $candidate -XshowSettings:properties -version 2>&1 |
        Select-String 'java.specification.version' | Select-Object -First 1)
    if (-not $line) { continue }

    $spec = ($line.ToString() -split '=', 2)[1].Trim()
    if ($spec -match '^\d+$' -and [int]$spec -ge 17) {
        Write-Output $candidate
        exit 0
    }
}

exit 1
