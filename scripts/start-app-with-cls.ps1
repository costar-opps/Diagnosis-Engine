# 加载 cls.env 并启动 Diagnosis-Engine（含 DashScope / CLS 环境变量）
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot
$EnvFile = Join-Path $ProjectRoot "cls.env"

if (Test-Path $EnvFile) {
    Get-Content $EnvFile | ForEach-Object {
        if ($_ -match '^([^#=][^=]*)=(.*)$') {
            Set-Item -Path "Env:$($matches[1].Trim())" -Value $matches[2].Trim()
        }
    }
}

if (-not $env:CLS_LOG_SHIPPING_ENABLED) {
    $env:CLS_LOG_SHIPPING_ENABLED = "true"
}

Write-Host "环境变量:" -ForegroundColor Cyan
Write-Host "  DASHSCOPE_API_KEY      = $(if ($env:DASHSCOPE_API_KEY) { '已设置' } else { '未设置（请在 cls.env 中配置）' })"
Write-Host "  TENCENTCLOUD_SECRET_ID = $(if ($env:TENCENTCLOUD_SECRET_ID) { '已设置' } else { '未设置' })"
Write-Host "  CLS_TOPIC_ID           = $(if ($env:CLS_TOPIC_ID) { $env:CLS_TOPIC_ID } else { '未设置（可选，无日志证据时可不填）' })"
Write-Host "  CLS_LOG_SHIPPING_ENABLED = $env:CLS_LOG_SHIPPING_ENABLED"
Write-Host ""

Set-Location $ProjectRoot
mvn spring-boot:run
