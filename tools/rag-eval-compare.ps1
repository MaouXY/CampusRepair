<#
.SYNOPSIS
  RAG 检索配置对比实验脚本：同一评测集上依次跑「纯关键词 / 混合检索 / 混合检索+重排」，输出指标对比表。

.DESCRIPTION
  每次实验都会重启后端（通过环境变量覆盖配置），因此请确保：
    1. MySQL、Milvus 已启动，且知识切片已同步（可用 /api/v1/admin/rag/vector-status 核查）；
    2. 评测集已导入（corpus/*.jsonl 通过 /api/v1/admin/rag/eval/cases/import 导入）；
    3. 本脚本不修改任何配置文件，只通过进程级环境变量覆盖，跑完后自动以默认配置重启。

.EXAMPLE
  pwsh tools/rag-eval-compare.ps1 -Dataset campus-repair-hybrid-v2 -TopK 5
  pwsh tools/rag-eval-compare.ps1 -Dataset campus-repair-hybrid-v2 -Only C1,C2
#>
param(
    [string]$Dataset = 'campus-repair-hybrid-v2',
    [int]$TopK = 5,
    [string]$ApiBase = 'http://127.0.0.1:8999',
    [string]$ApiDir = 'F:\javaWeb\毕设接单\CampusRepair\CampusRepairApi',
    [string]$AdminUser = 'admin01',
    [string]$AdminPassword = '123456',
    # 留空表示跑全部配置；也可写成 C1,C3 只跑指定项（按配置序号）
    [string[]]$Only = @()
)

$configs = [ordered]@{
    # 注意：hybrid.enabled=false 并不等于「纯关键词」——那会退化为「向量优先、关键词兜底」的旧行为。
    # 真正的「无向量」基线必须关闭 Milvus。
    'K0 纯关键词（关闭向量）'   = @{ APP_RAG_MILVUS_ENABLED = 'false' }
    'V1 向量优先+重排（旧行为）' = @{ APP_RAG_HYBRID_ENABLED = 'false' }
    'K2 混合RRF（无重排）'      = @{ APP_RAG_HYBRID_ENABLED = 'true'; APP_AGENT_RERANK_ENABLED = 'false' }
    'K3 混合RRF+重排（默认）'   = @{ APP_RAG_HYBRID_ENABLED = 'true' }
}

function Wait-ApiReady([int]$TimeoutSeconds = 180) {
    for ($i = 0; $i -lt ($TimeoutSeconds / 3); $i++) {
        Start-Sleep -Seconds 3
        if ((Test-NetConnection -ComputerName 127.0.0.1 -Port 8999 -WarningAction SilentlyContinue).TcpTestSucceeded) {
            Start-Sleep -Seconds 10
            return $true
        }
    }
    return $false
}

function Stop-Api {
    $conn = Get-NetTCPConnection -LocalPort 8999 -State Listen -ErrorAction SilentlyContinue
    if ($conn) {
        Stop-Process -Id ($conn.OwningProcess | Select-Object -First 1) -Force
        Start-Sleep -Seconds 3
    }
}

function Start-Api([hashtable]$env, [string]$tag) {
    Write-Host "[$tag] 以 $($env | ConvertTo-Json -Compress) 启动后端 ..." -ForegroundColor Cyan
    $processEnv = @{}
    foreach ($key in $env.Keys) { $processEnv[$key] = $env[$key] }
    $logFile = Join-Path 'target' "app-eval-$tag.log"
    Start-Process -FilePath 'mvn' -ArgumentList '-B', 'spring-boot:run' -WorkingDirectory $ApiDir `
        -RedirectStandardOutput (Join-Path $ApiDir $logFile) `
        -RedirectStandardError (Join-Path $ApiDir "$logFile.err") -WindowStyle Hidden
    if (-not (Wait-ApiReady)) { throw "后端启动超时（$tag）" }
}

function Invoke-Eval([string]$envName, [string]$token) {
    $headers = @{ Authorization = "Bearer $token" }
    $body = @{ datasetName = $Dataset; topK = $TopK } | ConvertTo-Json
    $run = Invoke-RestMethod -Uri "$ApiBase/api/v1/admin/rag/eval/runs" -Method Post -Headers $headers `
        -ContentType 'application/json' -Body ([System.Text.Encoding]::UTF8.GetBytes($body)) -TimeoutSec 1800
    $misses = $run.data.results | Where-Object { -not $_.hit }
    return [pscustomobject]@{
        Config    = $envName
        HitRate   = $run.data.run.metrics.hitRate
        Recall    = $run.data.run.metrics.recallAtK
        Precision = $run.data.run.metrics.precisionAtK
        MRR       = $run.data.run.metrics.mrr
        nDCG      = $run.data.run.metrics.ndcgAtK
        Refusal   = $run.data.run.metrics.refusalAccuracy
        LatencyMs = $run.data.run.metrics.avgLatencyMs
        Misses    = ($misses | ForEach-Object { $_.question }) -join ' | '
    }
}

$results = @()
$index = 0
foreach ($name in $configs.Keys) {
    $index++
    if ($Only.Count -gt 0 -and $Only -notcontains "C$index") { continue }
    Stop-Api
    $env = $configs[$name]

    # 环境变量需在启动进程前设置；Start-Process 会继承当前会话环境
    # 注意：恢复时必须真正「删除」变量而不是赋 $null —— 赋 $null 会留下空字符串，
    # 而空字符串绑定到 boolean 配置（如 app.agent.rag.hybrid.enabled）会导致启动失败。
    $previous = @{}
    foreach ($key in $env.Keys) {
        $previous[$key] = [Environment]::GetEnvironmentVariable($key, 'Process')
        [Environment]::SetEnvironmentVariable($key, $env[$key], 'Process')
    }
    try {
        Start-Api $env "c$index"
    } finally {
        foreach ($key in $env.Keys) {
            if ($null -eq $previous[$key]) {
                Remove-Item -Path "Env:\$key" -ErrorAction SilentlyContinue
            } else {
                [Environment]::SetEnvironmentVariable($key, $previous[$key], 'Process')
            }
        }
    }

    $login = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $AdminUser; password = $AdminPassword } | ConvertTo-Json)
    $results += Invoke-Eval $name $login.data.token
}

Write-Host ''
Write-Host "==== 评测集 $Dataset（topK=$TopK）====" -ForegroundColor Green
$results | Format-Table Config, HitRate, Recall, Precision, MRR, nDCG, Refusal, LatencyMs -AutoSize
foreach ($result in $results) {
    if ($result.Misses) { Write-Host "未命中（$($result.Config)）：$($result.Misses)" -ForegroundColor DarkYellow }
}

# 恢复默认配置
Stop-Api
Start-Api @{} 'default'
Write-Host '已恢复默认配置运行。' -ForegroundColor Green
