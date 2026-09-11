<#
.SYNOPSIS
  生成校园报修系统的演示数据：用 test-img 里的「维修前/后」图片造工单，并跑到不同状态。

.DESCRIPTION
  1. 若 backup/demo-images-map.json 不存在，则先把 test-img 下各组「维修前/维修后」图片上传到 RustFS；
  2. 创建 7 条工单（覆盖水电/公共设施/空调照明/网络设备四个分类、五个地点），
     并按需推进状态：待审核 / 待接单(ASSIGNED) / 处理中(PROCESSING) / 待确认(WAITING_CONFIRM) / 已完成(COMPLETED)；
  3. 「已完成」的工单会带维修后结果图 + 学生 5 分评价，从而自动触发知识库草稿沉淀；
  4. 对「待审核」的工单执行一次 AI 预分析（真实多模态调用），便于演示 AI 派单建议；
  5. 新增 2 条带有效期的公告，演示公告有效期功能。

.EXAMPLE
  pwsh tools/seed-demo-data.ps1
  pwsh tools/seed-demo-data.ps1 -SkipAi        # 跳过 AI 调用（省额度）
  pwsh tools/seed-demo-data.ps1 -ResetImages   # 强制重新上传图片
#>
param(
    [string]$ApiBase = 'http://127.0.0.1:8999',
    [string]$ProjectRoot = 'F:\javaWeb\毕设接单\CampusRepair',
    [string]$Password = '123456',
    [switch]$SkipAi,
    [switch]$ResetImages
)

$ErrorActionPreference = 'Stop'
$imageRoot = Join-Path $ProjectRoot 'test-img'
$mapFile = Join-Path $ProjectRoot 'backup\demo-images-map.json'
$tempDir = Join-Path $env:TEMP 'demo-img'

function Login([string]$user) {
    $response = Invoke-RestMethod -Uri "$ApiBase/api/v1/auth/login" -Method Post -ContentType 'application/json' `
        -Body (@{ username = $user; password = $Password } | ConvertTo-Json)
    return $response.data.token
}

function Post-Json([string]$uri, [hashtable]$headers, $payload) {
    $json = if ($payload -is [string]) { $payload } else { $payload | ConvertTo-Json -Depth 6 }
    try {
        return Invoke-RestMethod -Uri "$ApiBase$uri" -Method Post -Headers $headers `
            -ContentType 'application/json; charset=utf-8' -Body ([System.Text.Encoding]::UTF8.GetBytes($json)) -TimeoutSec 300
    } catch {
        Write-Host "      [请求失败] POST $uri" -ForegroundColor Red
        Write-Host "      请求体: $json" -ForegroundColor Red
        Write-Host "      响应: $($_.ErrorDetails.Message)" -ForegroundColor Red
        throw
    }
}

function New-AuthHeader([string]$token) { return @{ Authorization = "Bearer $token" } }

# ---------- ① 图片：读取映射或重新上传 ----------
if ($ResetImages -or -not (Test-Path $mapFile)) {
    Write-Host '[1/5] 上传 test-img 图片到 RustFS ...' -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    $studentToken = Login 'student01'
    $map = @()
    $index = 0
    foreach ($folder in (Get-ChildItem $imageRoot -Directory | Sort-Object Name)) {
        $index++
        foreach ($kind in '维修前', '维修后') {
            $file = Get-ChildItem $folder.FullName -File | Where-Object { $_.Name -like "*$kind*" } | Select-Object -First 1
            if (-not $file) { continue }
            $ext = $file.Extension.ToLower()
            $dest = Join-Path $tempDir ("img-{0}-{1}{2}" -f $index, $(if ($kind -eq '维修前') { 'before' } else { 'after' }), $ext)
            Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
            $mime = if ($ext -eq '.png') { 'image/png' } else { 'image/jpeg' }
            $raw = & curl.exe -s -X POST "$ApiBase/api/v1/files/upload" -H "Authorization: Bearer $studentToken" -F "file=@$dest;type=$mime"
            $parsed = $raw | ConvertFrom-Json
            if ($parsed.code -ne 0) { throw "图片上传失败：$($file.Name) -> $raw" }
            $map += [pscustomobject]@{
                index  = $index
                folder = $folder.Name
                kind   = $(if ($kind -eq '维修前') { 'before' } else { 'after' })
                name   = $file.Name
                fileId = $parsed.data.id
                publicUrl = $parsed.data.publicUrl
            }
            Write-Host ("      {0} {1}" -f $file.Name, $parsed.data.id)
        }
    }
    $map | ConvertTo-Json -Depth 3 | Set-Content $mapFile -Encoding UTF8
} else {
    Write-Host "[1/5] 复用已上传图片映射：$mapFile" -ForegroundColor Cyan
    $map = Get-Content $mapFile -Raw -Encoding UTF8 | ConvertFrom-Json
}

function Get-ImageId([string]$folderKeyword, [string]$kind) {
    $hit = $map | Where-Object { $_.folder -like "*$folderKeyword*" -and $_.kind -eq $kind } | Select-Object -First 1
    if (-not $hit) { return $null }
    return [string]$hit.fileId
}

# 结果图必须由维修员上传：报修图是学生上传的，维修员无权把它绑定为结果图（file access denied）
function Publish-Image([string]$token, [string]$folderKeyword, [string]$kind) {
    $folder = Get-ChildItem $imageRoot -Directory | Where-Object { $_.Name -like "*$folderKeyword*" } | Select-Object -First 1
    if (-not $folder) { return $null }
    $file = Get-ChildItem $folder.FullName -File | Where-Object { $_.Name -like "*$kind*" } | Select-Object -First 1
    if (-not $file) { return $null }
    $ext = $file.Extension.ToLower()
    $dest = Join-Path $tempDir ("result-{0}-{1}{2}" -f [guid]::NewGuid().ToString('N').Substring(0, 8), $kind, $ext)
    New-Item -ItemType Directory -Force -Path $tempDir | Out-Null
    Copy-Item -LiteralPath $file.FullName -Destination $dest -Force
    $mime = if ($ext -eq '.png') { 'image/png' } else { 'image/jpeg' }
    $raw = & curl.exe -s -X POST "$ApiBase/api/v1/files/upload" -H "Authorization: Bearer $token" -F "file=@$dest;type=$mime"
    $parsed = $raw | ConvertFrom-Json
    if ($parsed.code -ne 0) { throw "结果图上传失败：$($file.Name) -> $raw" }
    return [string]$parsed.data.id
}

# ---------- ② 工单定义 ----------
$tickets = @(
    @{ key='pipe'; loc=30005; cat=20001; prio='HIGH'; flow='PENDING'; folder='管道发霉漏水'
       desc='宿舍卫生间下水管道接口渗水，墙面已经发霉长斑，地面一直潮湿，麻烦师傅尽快来看一下。' },
    @{ key='net'; loc=30007; cat=20003; prio='HIGH'; flow='PENDING'; folder=$null
       desc='图书馆自习区网络面板插上网线一会儿通一会儿断，影响同学查资料和提交作业。' },
    @{ key='wall'; loc=30005; cat=20005; prio='MEDIUM'; flow='ASSIGNED'; worker=10002; folder='墙皮脱落'
       desc='宿舍靠床的墙面局部墙皮脱落，水泥底层都露出来了，怕蹭到衣服和皮肤。' },
    @{ key='class'; loc=30002; cat=20005; prio='LOW'; flow='PROCESSING'; worker=10002; folder='墙壁污渍清理'
       desc='教室墙面被大面积笔迹和污渍覆盖，普通擦拭擦不掉，影响教室整体观感。' },
    @{ key='lamp'; loc=30003; cat=20004; prio='LOW'; flow='PROCESSING'; worker=12005; folder=$null
       desc='教学楼公共走廊的吸顶灯一直闪烁，晚上通行看着晃眼，担心影响视力。' },
    @{ key='ac'; loc=30007; cat=20004; prio='MEDIUM'; flow='WAITING_CONFIRM'; worker=12005; folder=$null
       desc='图书馆自习区两台空调开了很久还是不凉，出风口有异味，同学们反映比较闷。'
       result='已清洗滤网与接水盘，补充冷媒并复查出风温度，异味消除。'; remark='建议每季度清洗一次滤网。' },
    @{ key='brick'; loc=30001; cat=20005; prio='MEDIUM'; flow='COMPLETED'; worker=10002; folder='地砖小坑修补'
       desc='教学楼前广场地砖多处出现坑洞缺损，晚上骑车经过差点摔倒，存在安全隐患。'
       result='已清理坑洞内碎石，用同色地砖与砂浆修补找平，周边松动地砖一并加固。'; remark='现场已清理，建议一周内复查一次。'
       evalScore=5; evalContent='处理得很快，路面平整了，晚上骑车放心多了。' }
)

$student = New-AuthHeader (Login 'student01')
$admin = New-AuthHeader (Login 'admin01')
# 10002 = 李师傅（worker01）、12005 = 周师傅（worker03）
$workerTokens = @{ 10002 = (New-AuthHeader (Login 'worker01')); 12005 = (New-AuthHeader (Login 'worker03')) }

Write-Host '[2/5] 创建工单并推进状态 ...' -ForegroundColor Cyan
$created = @()
foreach ($spec in $tickets) {
    # 注意：不能用 `$x = if (...) { @($id) } else { @() }`——PowerShell 会把单元素数组解包成标量，
    # 导致 ConvertTo-Json 输出字符串而不是数组，后端报 400 请求体格式错误。
    $reportFileIds = [System.Collections.Generic.List[string]]::new()
    if ($spec.folder) {
        $beforeId = Get-ImageId $spec.folder 'before'
        if ($beforeId) { $reportFileIds.Add([string]$beforeId) }
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
    $line = "      [{0,-7}] {1}" -f $spec.flow, $spec.desc.Substring(0, [Math]::Min(20, $spec.desc.Length))

    if ($spec.flow -ne 'PENDING') {
        $assignBody = @{
            categoryId = [string]$spec.cat
            priority   = $spec.prio
            summary    = $spec.desc.Substring(0, [Math]::Min(30, $spec.desc.Length))
            workerId   = [string]$spec.worker
            remark     = '按演示流程派单'
        }
        $null = Post-Json "/api/v1/admin/tickets/$id/assign" $admin $assignBody
        $workerHeader = $workerTokens[[int]$spec.worker]

        # 只有需要进入"处理中"及之后的状态才调用接单
        if ($spec.flow -in @('PROCESSING', 'WAITING_CONFIRM', 'COMPLETED')) {
            $null = Post-Json "/api/v1/worker/tickets/$id/accept" $workerHeader @{}
        }
        if ($spec.flow -in @('WAITING_CONFIRM', 'COMPLETED') -and $spec.result) {
            # 注意顺序：结果图必须在「处理中」状态绑定（状态机要求 PROCESSING），
            # 先提交结果会进入 WAITING_CONFIRM，再绑图会被拒绝：requiredStatus=PROCESSING。
            if ($spec.folder) {
                $afterFileId = Publish-Image $workerHeader['Authorization'].Replace('Bearer ', '') $spec.folder '维修后'
                if ($afterFileId) {
                    $resultFileIds = [System.Collections.Generic.List[string]]::new()
                    $resultFileIds.Add($afterFileId)
                    $null = Post-Json "/api/v1/files/tickets/$id/result-images" $workerHeader @{ fileIds = $resultFileIds }
                }
            }
            $null = Post-Json "/api/v1/worker/tickets/$id/result" $workerHeader @{ result = $spec.result; remark = $spec.remark }
            if ($spec.flow -eq 'COMPLETED' -and $spec.evalScore) {
                $null = Post-Json "/api/v1/student/tickets/$id/evaluation" $student @{ score = $spec.evalScore; content = $spec.evalContent }
            }
        }
    }
    Write-Host "$line  id=$id"
    $created += [pscustomobject]@{ id = $id; flow = $spec.flow; desc = $spec.desc }
}

# ---------- ③ AI 预分析（待审核工单） ----------
if (-not $SkipAi) {
    Write-Host '[3/5] 对待审核工单执行 AI 预分析（多模态）...' -ForegroundColor Cyan
    foreach ($item in ($created | Where-Object { $_.flow -eq 'PENDING' })) {
        try {
            $analysis = (Post-Json "/api/v1/admin/tickets/$($item.id)/ai/analysis" $admin @{}).data
            Write-Host ("      {0} 状态={1} 优先级={2} 建议维修员={3} 置信度={4}" -f `
                $item.id, $analysis.status, $analysis.suggestedPriority, $analysis.suggestedWorkerId, $analysis.confidence)
        } catch {
            Write-Host ("      {0} AI 分析失败：{1}" -f $item.id, $_.Exception.Message) -ForegroundColor Yellow
        }
    }
} else {
    Write-Host '[3/5] 已跳过 AI 预分析（-SkipAi）' -ForegroundColor Yellow
}

# ---------- ④ 带有效期的公告 ----------
Write-Host '[4/5] 新增带有效期的公告 ...' -ForegroundColor Cyan
$now = Get-Date
$notices = @(
    @{ title = '本周宿舍区水管检修通知'; targetRole = 'ALL'; sortOrder = 5
       content = '后勤处将于本周六 8:00-12:00 对学生宿舍A区给水立管进行检修，期间可能短时停水，请同学们提前储备用水。'
       effectiveAt = $now.ToString('yyyy-MM-ddTHH:mm:ss'); expireAt = $now.AddDays(3).ToString('yyyy-MM-ddTHH:mm:ss') },
    @{ title = '九月维修服务满意度调查'; targetRole = 'STUDENT'; sortOrder = 6
       content = '请同学们在完成维修后及时在工单中确认并评价，评价结果将用于维修员绩效考核与服务质量改进。'
       effectiveAt = $now.ToString('yyyy-MM-ddTHH:mm:ss'); expireAt = $now.AddDays(10).ToString('yyyy-MM-ddTHH:mm:ss') }
)
foreach ($notice in $notices) {
    $result = (Post-Json '/api/v1/admin/notices' $admin ($notice + @{ published = 1 })).data
    Write-Host ("      [{0}] {1}  有效期至 {2}" -f $result.status, $result.title, $result.expireAt)
}

# ---------- ⑤ 汇总 ----------
Write-Host '[5/5] 汇总 ...' -ForegroundColor Cyan
$overview = (Invoke-RestMethod -Uri "$ApiBase/api/v1/admin/tickets/todo-overview" -Headers $admin).data
Write-Host ("      待审核={0} 退回={1} 超时={2} 督办={3} 待确认={4}" -f `
    $overview.pendingReview, $overview.returned, $overview.overdue, $overview.urged, $overview.waitingConfirm)
$drafts = (Invoke-RestMethod -Uri "$ApiBase/api/v1/admin/rag/drafts?page=1&size=5" -Headers $admin).data
Write-Host ("      知识草稿（评价闭环后自动沉淀）：{0} 条" -f $drafts.total)
Write-Host ''
Write-Host '演示数据生成完成。' -ForegroundColor Green
