# Windows 本机启动 Prometheus（不依赖 Docker）
$ErrorActionPreference = "Stop"

$ProjectRoot = Split-Path -Parent $PSScriptRoot
$PromDir = Join-Path $ProjectRoot "monitoring\prometheus"
$BinDir = Join-Path $PromDir "bin"
$DataDir = Join-Path $PromDir "data"
$ConfigFile = Join-Path $PromDir "prometheus-native.yml"
$Version = "2.55.1"
$ArchiveName = "prometheus-$Version.windows-amd64"
$ZipPath = Join-Path $BinDir "$ArchiveName.zip"
$ExePath = Join-Path $BinDir "$ArchiveName\prometheus.exe"
$PidFile = Join-Path $PromDir "prometheus.pid"
$LogFile = Join-Path $PromDir "prometheus.log"

function Test-PrometheusHealthy {
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:9090/-/healthy" -UseBasicParsing -TimeoutSec 3
        return $response.StatusCode -eq 200
    } catch {
        return $false
    }
}

if (Test-PrometheusHealthy) {
    Write-Host "Prometheus 已在运行: http://localhost:9090" -ForegroundColor Green
    exit 0
}

if (-not (Test-Path $ConfigFile)) {
    throw "未找到配置文件: $ConfigFile"
}

New-Item -ItemType Directory -Force -Path $BinDir, $DataDir | Out-Null

if (-not (Test-Path $ExePath)) {
    Write-Host "正在下载 Prometheus $Version ..." -ForegroundColor Cyan
    $Url = "https://github.com/prometheus/prometheus/releases/download/v$Version/$ArchiveName.zip"
    Invoke-WebRequest -Uri $Url -OutFile $ZipPath
    Expand-Archive -Path $ZipPath -DestinationPath $BinDir -Force
    Write-Host "Prometheus 下载完成" -ForegroundColor Green
}

if (Test-Path $PidFile) {
    $oldPid = Get-Content $PidFile -ErrorAction SilentlyContinue
    if ($oldPid -and (Get-Process -Id $oldPid -ErrorAction SilentlyContinue)) {
        Write-Host "Prometheus 进程已存在 (PID=$oldPid)" -ForegroundColor Yellow
        exit 0
    }
}

Write-Host "正在启动 Prometheus (http://localhost:9090) ..." -ForegroundColor Cyan

$process = Start-Process `
    -FilePath $ExePath `
    -ArgumentList @(
        "--config.file=$ConfigFile",
        "--web.listen-address=:9090",
        "--storage.tsdb.path=$DataDir"
    ) `
    -WorkingDirectory $PromDir `
    -RedirectStandardOutput $LogFile `
    -PassThru `
    -WindowStyle Hidden

$process.Id | Set-Content $PidFile

Start-Sleep -Seconds 3

if (Test-PrometheusHealthy) {
    Write-Host ""
    Write-Host "Prometheus 启动成功" -ForegroundColor Green
    Write-Host "  Targets : http://localhost:9090/targets"
    Write-Host "  Alerts  : http://localhost:9090/alerts"
    Write-Host "  日志文件: $LogFile"
    Write-Host ""
    Write-Host "提示: 内存超过 85% 持续 1 分钟后，应出现 HighMemoryUsage 告警" -ForegroundColor Yellow
} else {
    Write-Host "Prometheus 启动失败，请查看日志: $LogFile" -ForegroundColor Red
    if (Test-Path $LogFile) {
        Get-Content $LogFile -Tail 20
    }
    exit 1
}
