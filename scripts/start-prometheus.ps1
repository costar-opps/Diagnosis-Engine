# 启动 Prometheus
# Docker 镜像拉取失败时，自动改用 Windows 本机二进制
$ErrorActionPreference = "Stop"
$ProjectRoot = Split-Path -Parent $PSScriptRoot

Write-Host "尝试使用 Windows 本机方式启动 Prometheus ..." -ForegroundColor Cyan
& (Join-Path $ProjectRoot "scripts\start-prometheus-native.ps1")
