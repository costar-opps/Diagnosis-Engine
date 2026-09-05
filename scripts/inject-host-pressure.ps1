# 通过诊断引擎的可撤销注入端点模拟主机压力，不在本机真实打满内存/磁盘。
param(
    [ValidateSet("memory", "disk", "revoke")]
    [string]$Mode = "memory"
)

$ErrorActionPreference = "Stop"
$Base = "http://localhost:9900/api/faults"

switch ($Mode) {
    "memory" { Invoke-RestMethod -Method POST -Uri "$Base/platform-memory-pressure/inject" | ConvertTo-Json -Depth 5 }
    "disk"   { Invoke-RestMethod -Method POST -Uri "$Base/platform-disk-pressure/inject" | ConvertTo-Json -Depth 5 }
    "revoke" {
        Invoke-RestMethod -Method POST -Uri "$Base/platform-memory-pressure/revoke" | Out-Null
        Invoke-RestMethod -Method POST -Uri "$Base/platform-disk-pressure/revoke" | ConvertTo-Json -Depth 5
    }
}
