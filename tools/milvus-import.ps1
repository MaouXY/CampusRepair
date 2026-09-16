<#
.SYNOPSIS
  把 milvus-export.ps1 导出的 JSONL 导入到另一个 Milvus 实例（可自动按原集合结构建集合、建索引、加载）。

.DESCRIPTION
  仅用 Milvus REST v2 接口（19530），不需要 pymilvus 等依赖。
  导入时用 JSONL 里的原始实体文本直接拼 data 数组，避免 PowerShell「单元素数组被解包」导致请求体变形。

.EXAMPLE
  # 导到另一个实例的另一个集合名（推荐：跨实例迁移时换个集合名，避免撞名）
  pwsh tools/milvus-import.ps1 -MilvusUrl http://192.168.1.50:19530 `
       -Source backup\milvus-campus_repair_knowledge.jsonl `
       -Collection campus_repair_knowledge -CreateCollection

.EXAMPLE
  # 先演练（不写库，只检查源文件与目标连通性）
  pwsh tools/milvus-import.ps1 -Source backup\milvus-campus_repair_knowledge.jsonl -DryRun
#>
param(
    [string]$MilvusUrl = 'http://127.0.0.1:19530',
    [string]$Source = '',
    [string]$Collection = '',
    [switch]$CreateCollection,
    [int]$BatchSize = 100,
    [string]$MetricType = 'COSINE',
    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'
if (-not $Source) {
    $root = Split-Path -Parent $PSScriptRoot
    $Source = Join-Path $root 'backup\milvus-campus_repair_knowledge.jsonl'
}
if (-not (Test-Path $Source)) { throw "找不到导出文件：$Source" }

function Invoke-Milvus([string]$path, [string]$jsonBody) {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)
    try {
        return Invoke-RestMethod -Uri "$MilvusUrl$path" -Method Post -ContentType 'application/json' -Body $bytes -TimeoutSec 600
    } catch {
        Write-Host "  [请求失败] POST $path" -ForegroundColor Red
        Write-Host "  响应: $($_.ErrorDetails.Message)" -ForegroundColor Red
        throw
    }
}

# ---------- 读源文件 ----------
$lines = Get-Content $Source -Encoding UTF8
if ($lines.Count -lt 2) { throw "导出文件内容为空：$Source" }
$schema = ($lines[0] | ConvertFrom-Json).__schema
$entities = $lines[1..($lines.Count - 1)] | Where-Object { $_.Trim() }
if (-not $Collection) { $Collection = $schema.collectionName }
if ($Collection -eq $schema.collectionName) {
    Write-Host ("提示：目标集合名与源相同（{0}）。若目标实例已存在同名集合会导入重复数据，建议 -Collection 换名。" -f $Collection) -ForegroundColor Yellow
}
Write-Host ("导入 {0} 条实体 -> {1} 的集合 {2}（维度 {3}）" -f $entities.Count, $MilvusUrl, $Collection, $schema.dimension) -ForegroundColor Cyan

if ($DryRun) {
    Write-Host '（-DryRun）仅检查，不写入。源文件结构：' -ForegroundColor Yellow
    $schema.fields | ForEach-Object {
        $extra = ($_.params | Where-Object { $_.key -in @('dim', 'max_length') } | ForEach-Object { "$($_.key)=$($_.value)" }) -join ' '
        "    {0,-10} {1,-12} {2}" -f $_.name, $_.type, $extra
    }
    return
}

# ---------- 按需建集合 ----------
$existing = (Invoke-Milvus '/v2/vectordb/collections/list' '{}').data
if ($existing -contains $Collection) {
    Write-Host "  目标集合已存在，直接追加数据" -ForegroundColor Yellow
} else {
    if (-not $CreateCollection) { throw "目标实例没有集合 $Collection，请加 -CreateCollection 让脚本按源结构创建" }
    $fieldDefs = foreach ($field in $schema.fields) {
        $element = @{}
        foreach ($param in @($field.params)) { if ($param.key) { $element[$param.key] = [string]$param.value } }
        $def = [ordered]@{ fieldName = $field.name; dataType = $field.type }
        if ($field.primaryKey) { $def.isPrimary = $true }
        if ($element.Count -gt 0) { $def.elementTypeParams = $element }
        $def
    }
    $createBody = [ordered]@{
        collectionName = $Collection
        schema         = [ordered]@{ autoId = $false; enableDynamicField = $false; fields = @($fieldDefs) }
        indexParams    = @(@{
            metricType = $MetricType
            fieldName  = ($schema.fields | Where-Object { $_.type -eq 'FloatVector' } | Select-Object -First 1).name
            indexName  = 'vector_index'
            params     = @{ index_type = 'AUTOINDEX' }
        })
    } | ConvertTo-Json -Depth 12 -Compress
    $created = Invoke-Milvus '/v2/vectordb/collections/create' $createBody
    if ($created.code -ne 0) { throw "建集合失败：$($created | ConvertTo-Json -Compress)" }
    Write-Host "  已创建集合 $Collection（含向量索引，metric=$MetricType）" -ForegroundColor Green
}

# ---------- 分批插入（实体文本直接拼 JSON，避免数组被解包） ----------
$total = 0
for ($i = 0; $i -lt $entities.Count; $i += $BatchSize) {
    $end = [Math]::Min($i + $BatchSize - 1, $entities.Count - 1)
    $batch = $entities[$i..$end]
    $body = '{"collectionName":"' + $Collection + '","data":[' + ($batch -join ',') + ']}'
    $result = Invoke-Milvus '/v2/vectordb/entities/insert' $body
    if ($result.code -ne 0) { throw "插入失败（第 $($i + 1) 条起）：$($result | ConvertTo-Json -Compress)" }
    $total += $result.data.insertCount
    Write-Host ("  已插入 {0}/{1}" -f $total, $entities.Count)
}

# ---------- 加载并校验 ----------
$null = Invoke-Milvus '/v2/vectordb/collections/load' ('{"collectionName":"' + $Collection + '"}')
Start-Sleep -Seconds 2
$count = (Invoke-Milvus '/v2/vectordb/entities/query' ('{"collectionName":"' + $Collection + '","filter":"","outputFields":["count(*)"]}')).data
Write-Host ("导入完成：写入 {0} 条，目标集合当前实体数 {1}" -f $total, ($count | ConvertTo-Json -Compress)) -ForegroundColor Green
