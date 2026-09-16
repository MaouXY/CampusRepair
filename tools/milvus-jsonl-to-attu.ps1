<#
.SYNOPSIS
  把 milvus-export.ps1 / milvus_migrate.py 导出的 NDJSON 转成 Attu「导入数据」可直接使用的 JSON 数组文件。

.DESCRIPTION
  Attu（v2.4+）的「导入数据」对话框要求：
    * 文件类型为 CSV 或 JSON；
    * JSON 必须是**数组**（[{...},{...}]），不是每行一个对象的 NDJSON；
    * 列名/字段名必须与集合 Schema 完全一致（本项目：id / text / metadata / vector）；
    * 单文件 < 150MB、行数 < 100000。
  本脚本会丢掉导出文件首行的 __schema 元信息，并把其余实体拼成一个 JSON 数组，
  以 UTF-8 无 BOM 写出（BOM 会让前端 JSON.parse 失败）。

.EXAMPLE
  pwsh tools/milvus-jsonl-to-attu.ps1
  pwsh tools/milvus-jsonl-to-attu.ps1 -Source backup\milvus-campus_repair_knowledge.jsonl -Verify
#>
param(
    [string]$Source = '',
    [string]$Output = '',
    [switch]$Verify
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
if (-not $Source) { $Source = Join-Path $root 'backup\milvus-campus_repair_knowledge.jsonl' }
if (-not (Test-Path $Source)) { throw "找不到导出文件：$Source（先用 tools/milvus-export.ps1 导出）" }
if (-not $Output) {
    $name = [System.IO.Path]::GetFileNameWithoutExtension($Source)
    $Output = Join-Path (Split-Path -Parent $Source) "attu-$name.json"
}

$lines = Get-Content $Source -Encoding UTF8
if ($lines.Count -lt 2) { throw "导出文件内容为空：$Source" }

$schema = ($lines[0] | ConvertFrom-Json).__schema
$entities = @($lines[1..($lines.Count - 1)] | Where-Object { $_.Trim() })
Write-Host ("源文件：{0} 条实体，集合 {1}，维度 {2}" -f $entities.Count, $schema.collectionName, $schema.dimension) -ForegroundColor Cyan

# 直接拼接原始实体文本，避免逐条反序列化（191 条 × 2560 维，反序列化很慢）
$builder = New-Object System.Text.StringBuilder
[void]$builder.Append('[')
for ($i = 0; $i -lt $entities.Count; $i++) {
    if ($i -gt 0) { [void]$builder.Append(',') }
    [void]$builder.Append($entities[$i])
}
[void]$builder.Append(']')

[System.IO.File]::WriteAllText($Output, $builder.ToString(), (New-Object System.Text.UTF8Encoding($false)))
$sizeMb = [math]::Round((Get-Item $Output).Length / 1MB, 2)
Write-Host ("已生成 Attu 可导入文件：{0}（{1} MB，单文件上限 150MB ✓）" -f $Output, $sizeMb) -ForegroundColor Green
Write-Host '在 Attu 里：集合 → 导入数据 → 选择该 JSON 文件 → 下一步' -ForegroundColor Yellow

if ($Verify) {
    Write-Host '校验生成结果 ...' -ForegroundColor Cyan
    $parsed = Get-Content $Output -Raw -Encoding UTF8 | ConvertFrom-Json
    $first = $parsed[0]
    "  实体数={0}  首条字段={1}" -f @($parsed).Count, (($first.PSObject.Properties.Name) -join ', ')
    "  向量维度={0}  与 schema 一致={1}" -f @($first.vector).Count, (@($first.vector).Count -eq [int]$schema.dimension)
    $bom = [System.IO.File]::ReadAllBytes($Output)[0..2] -join ','
    "  前 3 字节={0}（应为 91,123,34 即 '['{1}）" -f $bom, $(if ($bom -eq '91,123,34') { '' } else { '，异常：可能带了 BOM' })
}
