<#
.SYNOPSIS
  批量生成演示工单（用于让列表分页、统计图表更丰富），图片资源复用 test-img。

.DESCRIPTION
  规格文件：tools/demo-tickets-bulk.json（可直接编辑增删）。
  每条规格支持字段：
    desc          报修描述（必填）
    loc / cat     地点ID / 分类ID
    prio          优先级 LOW / MEDIUM / HIGH
    flow          目标状态：PENDING / ASSIGNED / PROCESSING / WAITING_CONFIRM / COMPLETED / RETURNED / REJECTED
    worker        维修员ID（非 PENDING 必填）：10002 李师傅 / 12004 赵师傅 / 12005 周师傅
    student       提交人账号：student01~student04
    daysAgo       提交时间前移天数（用于把数据铺开到近一个月，并制造 SLA 超时样本）
    image         test-img 中的分组关键字（填了就会上传一组维修前照片作为报修图）
    withResultImage  是否需要额外上传维修后照片作为结果图
    result / remark  处理结果（WAITING_CONFIRM / COMPLETED 需要）
    evalScore / evalContent  学生评价（COMPLETED 需要）
    returnReason / rejectReason  退回 / 驳回原因

.EXAMPLE
  pwsh tools/seed-demo-bulk.ps1
  pwsh tools/seed-demo-bulk.ps1 -SkipAi -NoBackdate
#>
param(
    [string]$ApiBase = 'http://127.0.0.1:8999',
    [string]$ProjectRoot = 'F:\javaWeb\毕设接单\CampusRepair',
    [string]$Password = '123456',
    [switch]$SkipAi,
    [switch]$NoBackdate,
    # 指定规格文件（默认 tools/demo-tickets-bulk.json）；可用它单独补几条数据
    [string]$SpecFile = ''
)

$ErrorActionPreference = 'Stop'
$imageRoot = Join-Path $ProjectRoot 'test-img'
$specFile = if ($SpecFile) { $SpecFile } else { Join-Path $ProjectRoot 'tools\demo-tickets-bulk.json' }
$tempDir = Join-Path $env:TEMP 'demo-img-bulk'
$mysql = 'C:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe'

$script:studentTokens = @{}
$script:workerTokens = @{}

function Login([string]$user) {
    $response = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $user; password = $Password } | ConvertTo-Json)
    return $response.data.token
}

function Get-StudentHeader([string]$user) {
    if (-not $script:studentTokens.ContainsKey($user)) { $script:studentTokens[$user] = @{ Authorization = "Bearer $(Login $user)" } }
    return $script:studentTokens[$user]
}

function Get-WorkerHeader([long]$workerId) {
    if (-not $script:workerTokens.ContainsKey($workerId)) {
        $account = switch ($workerId) {
            10002 { 'worker01' }
            12004 { 'worker02' }
            12005 { 'worker03' }
            default { throw "未知维修员账号：$workerId" }
        }
        $script:workerTokens[$workerId] = @{ Authorization = "Bearer $(Login $account)" }
    }
    return $script:workerTokens[$workerId]
}

function Post-Json([string]$uri, [hashtable]$headers, $payload) {
    $json = if ($payload -is [string]) { $payload } else { $payload | ConvertTo-Json -Depth 6 }
    try {
        return Invoke-RestMethod -Uri "$ApiBase$uri" -Method Post -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 300
    } catch {
        Write-Host "      [请求失败] POST $uri`n      请求体: $json`n      响应: $($_.ErrorDetails.Message)" -ForegroundColor Red
        throw
    }
}

function Upload-Image([hashtable]$headers, [string]$folderKeyword, [string]$kind) {
    $folder = Get-ChildItem $imageRoot -Directory | Where-Object { $_.Name -like "*$folderKeyword*" } | Select-Object -First 1
    if (-not $folder) { return $null }
    $file = Get-ChildItem $folder.FullName -File | Where-Object { $_.Name -like "*$kind*" } | Select-Object -First 1
    if (-not $file) { return $null }
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $ext = $file.Extension.ToLower()
    $dest = Join-Path $tempDir ("{0}-{1}{2}" -f [guid]::NewGuid().ToString('N').Substring(0, 8), $kind, $ext)
    Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
    $mime = if ($ext -eq '.png') { 'image/png' } else { 'image/jpeg' }
    $raw = & curl.exe -s -X POST "$ApiBase/api/v1/files/upload" -H "Authorization: $($headers['Authorization'])" -F "file=@$dest;type=$mime"
    $parsed = $raw | ConvertFrom-Json
    if ($parsed.code -ne 0) { throw "图片上传失败：$($file.Name) -> $raw" }
    return [string]$parsed.data.id
}

$specs = Get-Content $specFile -Raw -Encoding UTF8 | ConvertFrom-Json
$admin = @{ Authorization = "Bearer $(Login 'admin01')" }

Write-Host "读取规格 $(@($specs).Count) 条：$specFile" -ForegroundColor Cyan
$created = @()

foreach ($spec in $specs) {
    $student = Get-StudentHeader $spec.student
    $reportFileIds = [System.Collections.Generic.List[string]]::new()
    if ($spec.image) {
        $fileId = Upload-Image $student $spec.image '维修前'
        if ($fileId) { $reportFileIds.Add($fileId) } else { Write-Host "      [警告] 未找到图片分组：$($spec.image)" -ForegroundColor Yellow }
    }

    $body = @{
        locationId         = [string]$spec.loc
        categoryId         = [string]$spec.cat
        description        = $spec.desc
        contactPhone       = '13800000001'
        reportImageFileIds = $reportFileIds
    }
    $ticket = (Post-Json '/api/v1/student/tickets' $student $body).data
    $id = $ticket.id
    if (-not $id) { Write-Host "      [跳过] 创建失败：$($spec.desc)" -ForegroundColor Red; continue }

    # 驳回由待审核直接发起，不需要派单，因此单独分支处理
    if ($spec.flow -eq 'REJECTED') {
        $null = Post-Json "/api/v1/admin/tickets/$id/reject" $admin @{ reason = $spec.rejectReason }
    } elseif ($spec.flow -ne 'PENDING') {
        $summary = $spec.desc.Substring(0, [Math]::Min(40, $spec.desc.Length))
        $null = Post-Json "/api/v1/admin/tickets/$id/assign" $admin @{
            categoryId = [string]$spec.cat; priority = $spec.prio; summary = $summary
            workerId   = [string]$spec.worker; remark = '按演示流程派单'
        }
        $worker = Get-WorkerHeader ([long]$spec.worker)

        if ($spec.flow -in @('PROCESSING', 'WAITING_CONFIRM', 'COMPLETED')) {
            $null = Post-Json "/api/v1/worker/tickets/$id/accept" $worker @{}
        }
        # 结果图必须在「处理中」状态绑定（状态机要求 PROCESSING）
        if ($spec.withResultImage -and $spec.flow -in @('WAITING_CONFIRM', 'COMPLETED')) {
            $afterId = Upload-Image $worker $spec.image '维修后'
            if ($afterId) {
                $resultFileIds = [System.Collections.Generic.List[string]]::new()
                $resultFileIds.Add($afterId)
                $null = Post-Json "/api/v1/files/tickets/$id/result-images" $worker @{ fileIds = $resultFileIds }
            }
        }
        if ($spec.flow -in @('WAITING_CONFIRM', 'COMPLETED')) {
            $null = Post-Json "/api/v1/worker/tickets/$id/result" $worker @{ result = $spec.result; remark = $spec.remark }
        }
        if ($spec.flow -eq 'COMPLETED') {
            $null = Post-Json "/api/v1/student/tickets/$id/evaluation" $student @{
                score = $spec.evalScore; content = $spec.evalContent
            }
        }
        if ($spec.flow -eq 'RETURNED') {
            $null = Post-Json "/api/v1/worker/tickets/$id/return" $worker @{ reason = $spec.returnReason }
        }
    }

    $created += [pscustomobject]@{ id = $id; flow = $spec.flow; prio = $spec.prio; daysAgo = [int]$spec.daysAgo; desc = $spec.desc }
    Write-Host ("      [{0,-15}] {1}" -f $spec.flow, $spec.desc.Substring(0, [Math]::Min(24, $spec.desc.Length)))
}

# ---------- 时间铺开（把 created_at / SLA / 流转记录整体前移，制造历史数据与超时样本） ----------
if (-not $NoBackdate) {
    Write-Host '把提交时间铺开到近一个月 ...' -ForegroundColor Cyan
    $statements = foreach ($item in $created) {
        if ($item.daysAgo -le 0) { continue }
        $d = $item.daysAgo
        @"
UPDATE repair_ticket SET
  created_at = DATE_SUB(created_at, INTERVAL $d DAY),
  updated_at = DATE_SUB(updated_at, INTERVAL $d DAY),
  sla_deadline_at = DATE_SUB(sla_deadline_at, INTERVAL $d DAY),
  assigned_at = IF(assigned_at IS NULL, NULL, DATE_SUB(assigned_at, INTERVAL $d DAY)),
  processed_at = IF(processed_at IS NULL, NULL, DATE_SUB(processed_at, INTERVAL $d DAY)),
  urged_at = IF(urged_at IS NULL, NULL, DATE_SUB(urged_at, INTERVAL $d DAY))
WHERE id = $($item.id);
UPDATE repair_ticket_flow SET created_at = DATE_SUB(created_at, INTERVAL $d DAY) WHERE ticket_id = $($item.id);
"@
    }
    $sqlText = "USE campus_repair;`n" + ($statements -join "`n")
    $sqlPath = Join-Path $tempDir 'backdate.sql'
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    Set-Content -Path $sqlPath -Value $sqlText -Encoding UTF8
    & cmd /c "`"$mysql`" --host=127.0.0.1 --port=3306 --user=root --password=1829002 --default-character-set=utf8mb4 < `"$sqlPath`"" 2>&1 | Where-Object { $_ -notmatch 'password on the command line' }
}

# ---------- AI 预分析（仅待审核，可选） ----------
if (-not $SkipAi) {
    $pending = $created | Where-Object { $_.flow -eq 'PENDING' }
    if ($pending) {
        Write-Host "对待审核工单执行 AI 预分析（$($pending.Count) 条）..." -ForegroundColor Cyan
        foreach ($item in $pending) {
            try {
                $a = (Post-Json "/api/v1/admin/tickets/$($item.id)/ai/analysis" $admin @{}).data
                Write-Host ("      {0} 状态={1} 优先级={2} 建议维修员={3}" -f $item.id, $a.status, $a.suggestedPriority, $a.suggestedWorkerId)
            } catch {
                Write-Host ("      {0} AI 分析失败：{1}" -f $item.id, $_.Exception.Message) -ForegroundColor Yellow
            }
        }
    }
}

# ---------- 汇总 ----------
Write-Host ''
Write-Host '本次新增工单状态分布：' -ForegroundColor Cyan
$created | Group-Object flow | ForEach-Object { "  {0,-16} {1} 条" -f $_.Name, $_.Count }
Write-Host ''
$overview = (Invoke-RestMethod -Uri "$ApiBase/api/v1/admin/tickets/todo-overview" -Headers $admin).data
Write-Host ("管理端待办：待审核={0} 退回={1} 超时={2} 督办={3} 待确认={4}" -f `
    $overview.pendingReview, $overview.returned, $overview.overdue, $overview.urged, $overview.waitingConfirm)
$stats = (Invoke-RestMethod -Uri "$ApiBase/api/v1/admin/stats/overview" -Headers $admin).data
Write-Host ("统计总览：今日报修={0} 待派单={1} 处理中={2} 已完成={3} 平均评分={4}" -f `
    $stats.todayTickets, $stats.pendingReview, $stats.processing, $stats.completed, $stats.averageScore)
Write-Host '批量演示数据生成完成。' -ForegroundColor Green
