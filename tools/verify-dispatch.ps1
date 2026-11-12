<#
.SYNOPSIS
    第二轮提质「AI 辅助派单调度」接口复验脚本。

.DESCRIPTION
    对运行中的后端（默认 http://127.0.0.1:8999）做一轮只读为主、可选的写操作复验：
      1. 管理员登录
      2. 取一条待审核工单（或由 -TicketId 指定）
      3. GET  /api/v1/admin/tickets/{ticketId}/dispatch-suggestion   —— 派单建议（候选、评分、推荐理由）
      4. GET  /api/v1/admin/ai/token-usage                          —— token 实时监控
      5. GET  /api/v1/admin/rag/eval/datasets                       —— RAG 评测数据集
      6. 可选 -CheckDispatchToggle：关闭某维修员的 dispatch_enabled 后重取建议，
         断言其从候选中消失；随后恢复原值。用于回归「历史快照不得绕过当前画像」缺陷。

.PARAMETER BaseUrl
    后端地址，默认 http://127.0.0.1:8999

.EXAMPLE
    pwsh tools/verify-dispatch.ps1

.EXAMPLE
    pwsh tools/verify-dispatch.ps1 -CheckDispatchToggle -ToggleWorkerId 10002
#>
param(
    [string]$BaseUrl = 'http://127.0.0.1:8999',
    [string]$AdminUser = 'admin01',
    [string]$AdminPassword = '123456',
    [long]$TicketId = 0,
    [switch]$CheckDispatchToggle,
    [long]$ToggleWorkerId = 10002
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$script:passed = 0
$script:failed = 0

function Write-Step([string]$text) {
    Write-Host ""
    Write-Host "== $text" -ForegroundColor Cyan
}

function Assert-True([bool]$condition, [string]$message) {
    if ($condition) {
        $script:passed++
        Write-Host "  [PASS] $message" -ForegroundColor Green
    } else {
        $script:failed++
        Write-Host "  [FAIL] $message" -ForegroundColor Red
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [hashtable]$Headers = @{},
        $Body = $null
    )
    $args = @{
        Method      = $Method
        Uri         = "$BaseUrl$Path"
        Headers     = $Headers
        TimeoutSec  = 60
        ErrorAction = 'Stop'
    }
    if ($null -ne $Body) {
        $args.ContentType = 'application/json; charset=utf-8'
        $args.Body = [System.Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6))
    }
    return Invoke-RestMethod @args
}

function Format-Candidates($candidates) {
    $candidates | ForEach-Object {
        "    - {0}({1}) 总分 {2} | 技能 {3} 部门 {4} 负载 {5} 质量 {6} 扣分 {7} | 推荐={8}" -f `
            $_.workerName, $_.workerId, $_.totalScore, $_.skillScore, $_.departmentScore, `
            $_.workloadScore, $_.qualityScore, $_.penaltyScore, $_.aiRecommended
    }
}

Write-Step "1. 管理员登录（$AdminUser）"
$login = Invoke-Api -Method Post -Path '/api/v1/auth/login' -Body @{ username = $AdminUser; password = $AdminPassword }
Assert-True ($login.code -eq 0 -and $login.data.token) "登录成功，用户=$($login.data.displayName) 角色=$($login.data.role)"
$auth = @{ Authorization = "Bearer $($login.data.token)" }

Write-Step "2. 选取待审核工单"
if ($TicketId -le 0) {
    $page = Invoke-Api -Method Get -Path '/api/v1/admin/tickets?status=PENDING_REVIEW&page=1&size=1' -Headers $auth
    $TicketId = [long]$page.data.items[0].id
}
Assert-True ($TicketId -gt 0) "使用工单 $TicketId"

Write-Step "3. 派单建议 GET /api/v1/admin/tickets/$TicketId/dispatch-suggestion"
$suggestion = Invoke-Api -Method Get -Path "/api/v1/admin/tickets/$TicketId/dispatch-suggestion" -Headers $auth
$data = $suggestion.data
Write-Host "  分类=$($data.categoryName) 所需技能=$($data.requiredSkills -join '/') 目标部门=$($data.expectedDepartment)"
Write-Host "  推荐来源=$($data.recommendationSource) 推荐人=$($data.recommendedWorkerName)($($data.recommendedWorkerId)) 置信度=$($data.recommendedConfidence)"
Write-Host "  推荐理由=$($data.recommendedReason)"
Write-Host "  候选："
Format-Candidates $data.candidates | Write-Host
Assert-True ($suggestion.code -eq 0) "接口返回 code=0"
Assert-True ($data.candidates.Count -ge 1 -and $data.candidates.Count -le 3) "候选数量在 1~3 之间（Top3）"
Assert-True ([string]::IsNullOrWhiteSpace($data.recommendedReason) -eq $false) "推荐理由非空"
Assert-True ($null -ne $data.recommendedWorkerId) "返回可一键填入的 recommendedWorkerId"
$marked = @($data.candidates | Where-Object { $_.aiRecommended -eq $true })
Assert-True ($marked.Count -eq 1) "候选中有且只有 1 人被标记为推荐"

Write-Step "4. token 实时监控 GET /api/v1/admin/ai/token-usage"
$usage = Invoke-Api -Method Get -Path '/api/v1/admin/ai/token-usage' -Headers $auth
$u = $usage.data
Write-Host "  预算=$($u.dailyBudget) 已用=$($u.usedTokens) 剩余=$($u.remainingTokens) 占比=$($u.usedRatio) 当前等级=$($u.currentLevel) 说明=$($u.message)"
Assert-True ($usage.code -eq 0) "token 接口返回 code=0"
Assert-True ([double]$u.dailyBudget -gt 0) "日预算已配置"

Write-Step "5. RAG 评测数据集 GET /api/v1/admin/rag/eval/datasets"
$datasets = Invoke-Api -Method Get -Path '/api/v1/admin/rag/eval/datasets' -Headers $auth
foreach ($dataset in $datasets.data) {
    Write-Host "  - $($dataset.datasetName) 用例数=$($dataset.caseCount)（可答 $($dataset.answerableCaseCount) / 不可答 $($dataset.unanswerableCaseCount)）"
}
Assert-True ($datasets.data.Count -ge 1) "至少存在 1 个评测数据集"

if ($CheckDispatchToggle) {
    Write-Step "6. 回归：关闭维修员 $ToggleWorkerId 的派单开关后，候选应立即剔除"
    $workers = Invoke-Api -Method Get -Path '/api/v1/admin/workers?page=1&size=50' -Headers $auth
    $worker = $workers.data.items | Where-Object { [long]$_.id -eq $ToggleWorkerId } | Select-Object -First 1
    Assert-True ($null -ne $worker) "找到维修员 $ToggleWorkerId（$($worker.realName)）"
    if ($null -ne $worker) {
        $original = [int]$worker.dispatchEnabled
        $body = @{
            username         = $worker.username
            realName         = $worker.realName
            phone            = $worker.phone
            enabled          = $worker.enabled
            departmentName   = $worker.departmentName
            skillTags        = @($worker.skillTags)
            dispatchEnabled  = 0
            maxActiveOrders  = $worker.maxActiveOrders
        }
        $updated = Invoke-Api -Method Put -Path "/api/v1/admin/workers/$ToggleWorkerId" -Headers $auth -Body $body
        Assert-True ($updated.code -eq 0 -and [int]$updated.data.dispatchEnabled -eq 0) "已关闭 $($worker.realName) 的派单开关"

        $after = (Invoke-Api -Method Get -Path "/api/v1/admin/tickets/$TicketId/dispatch-suggestion" -Headers $auth).data
        Format-Candidates $after.candidates | Write-Host
        $stillIn = @($after.candidates | Where-Object { [long]$_.workerId -eq $ToggleWorkerId })
        Assert-True ($stillIn.Count -eq 0) "关闭派单后该维修员不再出现在候选中（历史快照被当前画像过滤）"

        $body.dispatchEnabled = $original
        $restored = Invoke-Api -Method Put -Path "/api/v1/admin/workers/$ToggleWorkerId" -Headers $auth -Body $body
        Assert-True ($restored.code -eq 0 -and [int]$restored.data.dispatchEnabled -eq $original) "已恢复原派单开关（$original）"
    }
}

Write-Step "复验结果"
Write-Host ("  通过 {0} 项，失败 {1} 项" -f $script:passed, $script:failed) -ForegroundColor $(if ($script:failed -eq 0) { 'Green' } else { 'Red' })
if ($script:failed -gt 0) { exit 1 }
exit 0
