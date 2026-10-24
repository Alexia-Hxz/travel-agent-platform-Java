# Publish travel-agent-platform-java to GitHub.
#
# Usage (from any directory):
#   powershell -ExecutionPolicy Bypass -File .\tools\publish-to-github.ps1
#
# Prerequisites:
#   1. Git installed (this machine: D:\Git\cmd\git.exe)
#   2. GitHub CLI logged in:  & "C:\Program Files\GitHub CLI\gh.exe" auth status
#      If not:                & "C:\Program Files\GitHub CLI\gh.exe" auth login
#
# The script initializes the repo if needed, refuses to stage .env, commits,
# creates Alexia-Hxz/travel-agent-platform-Java when missing, and pushes main.

$ErrorActionPreference = 'Stop'

$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

function Find-Exe([string]$preferred, [string]$fallback) {
    if (Test-Path $preferred) { return $preferred }
    return $fallback
}

$git = Find-Exe 'D:\Git\cmd\git.exe' 'git'
$gh = Find-Exe 'C:\Program Files\GitHub CLI\gh.exe' 'gh'
$owner = 'Alexia-Hxz'
$name = 'travel-agent-platform-Java'
$url = "https://github.com/$owner/$name"

Write-Host "Repo directory: $root" -ForegroundColor Cyan
Write-Host "git: $git"

if (-not (Test-Path (Join-Path $root '.git'))) {
    Write-Host 'Initializing git repository (main)...'
    & $git init -b main | Out-Null
}

# Fill in a local identity only when none is configured.
if (-not (& $git config user.name)) {
    & $git config user.name $owner
}
if (-not (& $git config user.email)) {
    & $git config user.email "$owner@users.noreply.github.com"
}

Write-Host 'Staging files...'
& $git add -A

# Safety gate: .env must never be committed.
$staged = & $git diff --cached --name-only
$secrets = $staged | Where-Object { $_ -match '(^|/)\.env$' -or $_ -match '(^|/)\.env\.(local|production|prod)$' }
if ($secrets) {
    Write-Host 'A secret file is staged; aborting:' -ForegroundColor Red
    $secrets | ForEach-Object { Write-Host "  $_" -ForegroundColor Red }
    Write-Host 'Make sure .gitignore contains .env and retry.'
    exit 1
}

$changed = & $git status --porcelain
if ($changed) {
    Write-Host 'Creating commit...'
    & $git commit -m 'Initial commit: Java 17 + Spring Boot 3 port of travel-agent-platform'
} else {
    Write-Host 'Nothing new to commit.'
}

$hasOrigin = $false
try {
    & $git remote get-url origin 2>$null | Out-Null
    if ($LASTEXITCODE -eq 0) { $hasOrigin = $true }
} catch {
    $hasOrigin = $false
}

if (-not $hasOrigin) {
    Write-Host 'Creating GitHub repository and pushing...'
    & $gh repo create "$owner/$name" --public `
        --description 'AI travel planner, Java 17 / Spring Boot 3 / MyBatis-Plus / Milvus port of travel-agent-platform' `
        --source $root --remote origin --push
    if ($LASTEXITCODE -ne 0) {
        Write-Host 'gh repo create failed. Create an empty repo on GitHub, then run:' -ForegroundColor Yellow
        Write-Host "  & '$git' remote add origin $url.git"
        Write-Host "  & '$git' push -u origin main"
        exit 1
    }
} else {
    Write-Host 'Pushing main...'
    & $git push -u origin main
}

Write-Host "Done: $url" -ForegroundColor Green
