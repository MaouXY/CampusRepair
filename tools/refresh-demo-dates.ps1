<#
.SYNOPSIS
  把演示数据的日期整体平移到「今天」，让首页/大屏的"今日报修、近 30 天、公告有效期"都好看。

.DESCRIPTION
  演示数据是某一天造的，过了几天再演示就会出现「今日报修 0」「公告已过期」的尴尬。
  本脚本按「最新一条工单距今多少天」计算偏移量，把所有工单、流转、AI 分析、评价、知识草稿、
  公告有效期等时间列整体平移同样的天数，**保持数据之间的相对时间关系不变**，幂等可重复执行。

.EXAMPLE
  pwsh tools/refresh-demo-dates.ps1            # 平移到今天
  pwsh tools/refresh-demo-dates.ps1 -DryRun    # 只看会平移几天，不改数据
#>
param(
    [switch]$DryRun,
    [string]$Database = 'campus_repair',
    [string]$User = 'root',
    [string]$Password = '1829002'
)

$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'
$baseArgs = @('--host=127.0.0.1', '--port=3306', "--user=$User", "--password=$Password",
    '--default-character-set=utf8mb4', $Database)

function Invoke-Sql([string]$sql, [switch]$Raw) {
    $tmp = Join-Path $env:TEMP ("demo-date-" + [guid]::NewGuid().ToString('N').Substring(0, 8) + ".sql")
    Set-Content -Path $tmp -Value $sql -Encoding UTF8
    # -Raw：去掉表格边框并跳过列名，便于脚本解析数值
    $extra = if ($Raw) { '--skip-column-names --batch' } else { '--table' }
    $output = & cmd /c "`"$mysql`" --host=127.0.0.1 --port=3306 --user=$User --password=$Password --default-character-set=utf8mb4 $extra $Database < `"$tmp`"" 2>&1 |
        Where-Object { $_ -notmatch 'password on the command line' }
    Remove-Item $tmp -ErrorAction SilentlyContinue
    return $output
}

$delta = [int](Invoke-Sql "SELECT GREATEST(0, DATEDIFF(CURDATE(), COALESCE(MAX(DATE(created_at)), CURDATE()))) FROM repair_ticket WHERE deleted = 0;" -Raw |
    Where-Object { $_ -match '^\s*\d+\s*$' } | Select-Object -First 1)

Write-Host ("最新工单日期与今天的差值：{0} 天" -f $delta) -ForegroundColor Cyan
if ($delta -eq 0) {
    Write-Host '数据已经在今天，无需平移。' -ForegroundColor Green
    return
}
if ($DryRun) {
    Write-Host '（-DryRun）将把以下时间列整体 +' $delta '天：工单、流转、AI 分析、ai 任务、评价、知识草稿、公告有效期' -ForegroundColor Yellow
    return
}

$sql = @"
USE $Database;
UPDATE repair_ticket SET
  created_at = created_at + INTERVAL $delta DAY,
  updated_at = updated_at + INTERVAL $delta DAY,
  sla_deadline_at = sla_deadline_at + INTERVAL $delta DAY,
  assigned_at = IF(assigned_at IS NULL, NULL, assigned_at + INTERVAL $delta DAY),
  processed_at = IF(processed_at IS NULL, NULL, processed_at + INTERVAL $delta DAY),
  urged_at = IF(urged_at IS NULL, NULL, urged_at + INTERVAL $delta DAY);

UPDATE repair_ticket_flow SET created_at = created_at + INTERVAL $delta DAY;
UPDATE repair_assignment SET assigned_at = assigned_at + INTERVAL $delta DAY;
UPDATE repair_ai_analysis SET created_at = created_at + INTERVAL $delta DAY,
                              updated_at = updated_at + INTERVAL $delta DAY;
UPDATE ai_task_record SET created_at = created_at + INTERVAL $delta DAY,
                          updated_at = updated_at + INTERVAL $delta DAY;
UPDATE repair_evaluation SET created_at = created_at + INTERVAL $delta DAY;
UPDATE rag_knowledge_draft SET created_at = created_at + INTERVAL $delta DAY,
                               updated_at = updated_at + INTERVAL $delta DAY;
UPDATE repair_notice SET
  created_at = created_at + INTERVAL $delta DAY,
  updated_at = updated_at + INTERVAL $delta DAY,
  -- 生效时间平移后可能落到未来（当时是按"当天 23 点"造的），会被判为"未生效"而不下发，
  -- 这里统一拉回到 1 小时前，保证公告处于"有效中"状态；过期时间保持平移结果。
  effective_at = CASE
      WHEN effective_at IS NULL THEN NULL
      WHEN effective_at + INTERVAL $delta DAY > NOW() THEN DATE_SUB(NOW(), INTERVAL 1 HOUR)
      ELSE effective_at + INTERVAL $delta DAY
  END,
  expire_at = IF(expire_at IS NULL, NULL, expire_at + INTERVAL $delta DAY);

SELECT COUNT(*) AS 工单总数,
       SUM(DATE(created_at) = CURDATE()) AS 今日报修,
       SUM(DATE(created_at) >= DATE_SUB(CURDATE(), INTERVAL 30 DAY)) AS 近30天,
       DATE_FORMAT(MIN(created_at), '%m-%d') AS 最早,
       DATE_FORMAT(MAX(created_at), '%m-%d') AS 最新
FROM repair_ticket WHERE deleted = 0;

SELECT title AS 公告, DATE_FORMAT(expire_at, '%m-%d %H:%i') AS 过期时间
FROM repair_notice WHERE deleted = 0 AND published = 1 ORDER BY sort_order;
"@

Invoke-Sql $sql | ForEach-Object { $_ }
Write-Host ("演示数据日期已平移到今天（+{0} 天）。" -f $delta) -ForegroundColor Green
