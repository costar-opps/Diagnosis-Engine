# 启动 cls-mcp-server（MCP SSE，供 Diagnosis-Engine 查询 CLS）
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot "cls.env"

if (-not (Test-Path $EnvFile)) {
    Write-Host "未找到 cls.env，请先复制 cls.env.example 为 cls.env 并填写密钥" -ForegroundColor Red
    exit 1
}

Get-Content $EnvFile | ForEach-Object {
    if ($_ -match '^([^#=][^=]*)=(.*)$') {
        Set-Item -Path "Env:$($matches[1].Trim())" -Value $matches[2].Trim()
    }
}

Write-Host "正在启动 cls-mcp-server (http://localhost:3000/sse) ..." -ForegroundColor Cyan
Set-Location $ProjectRoot
npx -y cls-mcp-server@latest
