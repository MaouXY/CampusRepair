<#
.SYNOPSIS
  用 Milvus REST v2 接口把一个集合（含向量）导出为 JSONL，不需要安装 pymilvus 等任何依赖。

.DESCRIPTION
  Milvus 2.4 在 19530 端口同时提供 gRPC 与 REST v2 接口（9091 只有 /healthz），本脚本：
    1. describe 集合，拿到字段定义（用于导入端重建同构集合）；
    2. 分页 query 全量实体（含 vector 字段）；
    3. 写出 JSONL：每行 {id, text, metadata, vector, ...}，并在首行写入 __schema 元信息。

.EXAMPLE
  pwsh tools/milvus-export.ps1
  pwsh tools/milvus-export.ps1 -Collection book_semantic_db -Output backup\book.jsonl
#>
param(
    [string]$MilvusUrl = 'http://127.0.0.1:19530',
    [string]$Collection = 'campus_repair_knowledge',
    [string]$Output = '',
    [int]$PageSize = 500
)

$ErrorActionPreference = 'Stop'
if (-not $Output) {
    $root = Split-Path -Parent $PSScriptRoot
    $Output = Join-Path $root "backup\milvus-$Collection.jsonl"
}
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Output) | Out-Null

function Invoke-Milvus([string]$path, $payload) {
    $json = if ($payload) { $payload | ConvertTo-Json -Depth 10 -Compress } else { '{}' }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($json)
    try {
        return Invoke-RestMethod -Uri "$MilvusUrl$path" -Method Post -ContentType 'application/json' -Body $bytes -TimeoutSec 300
    } catch {
        Write-Host "  [请求失败] POST $path" -ForegroundColor Red
        Write-Host "  请求体: $json" -ForegroundColor Red
        Write-Host "  响应: $($_.ErrorDetails.Message)" -ForegroundColor Red
        throw
    }
}

Write-Host "导出集合 $Collection （$MilvusUrl）" -ForegroundColor Cyan

$describe = Invoke-Milvus '/v2/vectordb/collections/describe' @{ collectionName = $Collection }
if ($describe.code -ne 0) { throw "describe 失败：$($describe | ConvertTo-Json -Compress)" }
$fields = @($describe.data.fields)
$fieldNames = $fields | ForEach-Object { $_.name }
$vectorField = ($fields | Where-Object { $_.type -eq 'FloatVector' } | Select-Object -First 1)
# describe 返回的 params 是 [{key,value}] 数组，不是对象
$dimension = [int](($vectorField.params | Where-Object { $_.key -eq 'dim' } | Select-Object -First 1).value)
Write-Host ("  字段：{0}   向量维度：{1}" -f ($fieldNames -join ', '), $dimension)

$rows = @()
$offset = 0
while ($true) {
    $response = Invoke-Milvus '/v2/vectordb/entities/query' @{
        collectionName = $Collection
        filter         = ''
        outputFields   = $fieldNames
        limit          = $PageSize
        offset         = $offset
    }
    if ($response.code -ne 0) { throw "query 失败：$($response | ConvertTo-Json -Compress)" }
    $page = @($response.data | Where-Object { $_ -ne $null })
    if ($page.Count -eq 0) { break }
    $rows += $page
    Write-Host ("  已读取 {0} 条 ..." -f $rows.Count)
    if ($page.Count -lt $PageSize) { break }
    $offset += $PageSize
    if ($offset -ge 16384) { Write-Host '  已达 query offset 上限 16384，如需更大数据量请改用 pymilvus 的 query_iterator' -ForegroundColor Yellow; break }
}

$header = @{
    __schema = @{
        collectionName = $Collection
        dimension      = $dimension
        fields         = $fields
        exportedAt     = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
        entityCount    = $rows.Count
    }
}

$lines = New-Object System.Collections.Generic.List[string]
$lines.Add(($header | ConvertTo-Json -Depth 10 -Compress))
foreach ($row in $rows) { $lines.Add(($row | ConvertTo-Json -Depth 10 -Compress)) }
Set-Content -Path $Output -Value $lines -Encoding UTF8

Write-Host ("导出完成：{0} 条 -> {1}（{2} MB）" -f $rows.Count, $Output, [math]::Round((Get-Item $Output).Length / 1MB, 2)) -ForegroundColor Green
