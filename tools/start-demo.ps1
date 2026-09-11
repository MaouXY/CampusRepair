<#
.SYNOPSIS
  校园报修系统「演示一键启动」脚本：拉起依赖容器 + 后端 + 前端，并打印演示前检查清单。

.DESCRIPTION
  幂等：已在运行的服务不会重复启动。适合演示/答辩现场使用。

.EXAMPLE
  pwsh tools/start-demo.ps1              # 启动全部并做检查
  pwsh tools/start-demo.ps1 -CheckOnly   # 只做检查，不启动
  pwsh tools/start-demo.ps1 -Stop        # 停止后端与前端（容器保留）
#>
param(
    [switch]$CheckOnly,
    [switch]$Stop,
    [string]$ProjectRoot = 'F:\javaWeb\毕设接单\CampusRepair',
    [string]$ApiUrl = 'http://127.0.0.1:8999',
    [string]$WebUrl = 'http://127.0.0.1:5173'
)

$ErrorActionPreference = 'Continue'
$apiDir = Join-Path $ProjectRoot 'CampusRepairApi'
$webDir = Join-Path $ProjectRoot 'CampusRepairWeb'

function Test-Port([int]$port) {
    return (Test-NetConnection -ComputerName 127.0.0.1 -Port $port -WarningAction SilentlyContinue).TcpTestSucceeded
}

function Stop-Listener([int]$port, [string]$name) {
    $conn = Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        Stop-Process -Id ($conn.OwningProcess | Select-Object -First 1) -Force
        Start-Sleep -Seconds 2
        Write-Host "  已停止 $name（端口 $port）" -ForegroundColor Yellow
    }
}

function Wait-Port([int]$port, [int]$timeoutSeconds, [string]$name) {
    for ($i = 0; $i -lt ($timeoutSeconds / 2); $i++) {
        Start-Sleep -Seconds 2
        if (Test-Port $port) { Write-Host "  $name 已就绪（端口 $port）" -ForegroundColor Green; return $true }
    }
    Write-Host "  $name 启动超时（端口 $port）" -ForegroundColor Red
    return $false
}

if ($Stop) {
    Write-Host '停止后端与前端 ...' -ForegroundColor Cyan
    Stop-Listener 8999 '后端 API'
    Stop-Listener 5173 '前端 Dev Server'
    Write-Host '依赖容器（MySQL/Milvus/RustFS）保持运行。' -ForegroundColor Green
    return
}

Write-Host '================ 校园报修系统 演示启动 ================' -ForegroundColor Cyan

# ① 依赖容器
Write-Host '[1/4] 检查依赖容器 ...' -ForegroundColor Cyan
$containers = @('milvus-standalone', 'milvus-etcd', 'milvus-minio', 'rustfs-server')
foreach ($name in $containers) {
    $state = docker inspect -f '{{.State.Status}}' $name 2>$null
    if ($state -ne 'running') {
        if (-not $CheckOnly) {
            Write-Host "  启动容器 $name ..." -ForegroundColor Yellow
            docker start $name | Out-Null
        } else {
            Write-Host "  [警告] 容器 $name 未运行" -ForegroundColor Yellow
        }
    }
}
if (-not $CheckOnly) { Start-Sleep -Seconds 20 }

Write-Host '[2/4] 检查端口 ...' -ForegroundColor Cyan
foreach ($item in @(@{ Port = 3306; Name = 'MySQL' }, @{ Port = 19530; Name = 'Milvus' }, @{ Port = 9990; Name = 'RustFS' })) {
    $ok = Test-Port $item.Port
    $color = if ($ok) { 'Green' } else { 'Red' }
    Write-Host ("  {0,-8} {1,-6} {2}" -f $item.Name, $item.Port, $(if ($ok) { 'OK' } else { '不可用' })) -ForegroundColor $color
}

if (-not $CheckOnly -and -not (Test-Port 8999)) {
    Write-Host '[3/4] 启动后端 API ...' -ForegroundColor Cyan
    Start-Process -FilePath 'mvn' -ArgumentList '-B', 'spring-boot:run' -WorkingDirectory $apiDir `
        -RedirectStandardOutput (Join-Path $apiDir 'target\demo-api.log') `
        -RedirectStandardError (Join-Path $apiDir 'target\demo-api.err.log') -WindowStyle Hidden
    Wait-Port 8999 180 '后端 API' | Out-Null
} else {
    Write-Host '[3/4] 后端 API 已在运行' -ForegroundColor Green
}

if (-not $CheckOnly -and -not (Test-Port 5173)) {
    Write-Host '[4/4] 启动前端 ...' -ForegroundColor Cyan
    Start-Process -FilePath 'npm.cmd' -ArgumentList 'run', 'dev' -WorkingDirectory $webDir `
        -RedirectStandardOutput (Join-Path $webDir 'dev-server.log') `
        -RedirectStandardError (Join-Path $webDir 'dev-server.err.log') -WindowStyle Hidden
    Wait-Port 5173 120 '前端 Dev Server' | Out-Null
} else {
    Write-Host '[4/4] 前端已在运行' -ForegroundColor Green
}

# 体检：登录 + 知识库 + AI 连通
Write-Host ''
Write-Host '================ 演示前体检 ================' -ForegroundColor Cyan
try {
    $login = Invoke-RestMethod -Uri "$ApiUrl/api/v1/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = 'admin01'; password = '123456' } | ConvertTo-Json) -TimeoutSec 30
    Write-Host '  管理员登录：OK' -ForegroundColor Green
    $headers = @{ Authorization = "Bearer $($login.data.token)" }

    $status = (Invoke-RestMethod -Uri "$ApiUrl/api/v1/admin/rag/vector-status" -Headers $headers -TimeoutSec 60).data
    Write-Host ("  知识库：{0} 文档 / {1} 切片，已同步 {2}，失败 {3}" -f `
        $status.documentCount, $status.chunkCount, $status.syncedChunkCount, $status.failedChunkCount) -ForegroundColor Green
    Write-Host ("  检索配置：hybrid={0} rrfK={1} 向量阈值={2} 重排={3} α={4}" -f `
        $status.hybridEnabled, $status.rrfK, $status.minVectorScore, $status.rerankEnabled, $status.rerankBlendWeight) -ForegroundColor Green

    $todo = (Invoke-RestMethod -Uri "$ApiUrl/api/v1/admin/tickets/todo-overview" -Headers $headers -TimeoutSec 60).data
    Write-Host ("  待办：待审核 {0} / 退回 {1} / 超时 {2} / 督办 {3} / 待确认 {4}" -f `
        $todo.pendingReview, $todo.returned, $todo.overdue, $todo.urged, $todo.waitingConfirm) -ForegroundColor Green

    $token = (Invoke-RestMethod -Uri "$ApiUrl/api/v1/admin/ai/token-usage" -Headers $headers -TimeoutSec 60).data
    Write-Host ("  AI token：今日已用 {0} / 预算 {1}（降级 {2} 次，当前 {3}）" -f `
        $token.usedTokens, $token.dailyBudget, $token.degradedCallCount, $token.currentLevel) -ForegroundColor Green
} catch {
    Write-Host "  体检失败：$($_.Exception.Message)" -ForegroundColor Red
}

Write-Host ''
Write-Host '================ 演示入口 ================' -ForegroundColor Green
Write-Host ("  前端界面：{0}    （浏览器打开，推荐 Chrome）" -f $WebUrl)
Write-Host ("  接口文档：{0}/doc.html （Knife4j）" -f $ApiUrl)
Write-Host '  账号：admin01 / worker01 / student01，密码均为 123456（另有 admin02、worker02、student02~04）'
Write-Host '  演示链路与话术见 doc/14_演示准备与答辩Runbook.md'
Write-Host ''
Write-Host '提示：演示时先做一次「学生提交带图报修 → 等待 20~30 秒 AI 预分析」，' -ForegroundColor DarkYellow
Write-Host '      再到管理端讲解 AI 派单建议；若网络抖动导致 AI 失败，页面会自动显示规则兜底结果（可讲降级设计）。' -ForegroundColor DarkYellow
