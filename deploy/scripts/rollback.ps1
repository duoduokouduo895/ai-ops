[CmdletBinding()]
param(
    [string]$ConfigPath,
    [string]$DeploymentId,
    [switch]$ConfirmRollback
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Deploy.Common.ps1')

$resolvedConfigPath = Resolve-DeployConfigPath $ConfigPath
$config = Read-DeployConfig $resolvedConfigPath
$settings = Get-FrontendReleaseSettings $config $resolvedConfigPath
$rollback = Get-RequiredProperty $config 'rollback' $resolvedConfigPath
if (-not [bool](Get-RequiredProperty $rollback 'enabled' "$resolvedConfigPath.rollback")) {
    throw 'rollback.enabled 未启用。'
}

if ([string]::IsNullOrWhiteSpace($DeploymentId)) {
    Write-Host 'DRY_RUN rollback target is required'
    Write-Host "请在 Cloudflare Pages 项目的 Deployments 页面选择成功的生产 deployment，并将其 ID 传给 -DeploymentId。"
    Write-Host 'Pages preview deployment 不能作为生产回滚目标。'
    return
}
if ($DeploymentId -notmatch '^[A-Za-z0-9_-]{1,128}$') {
    throw "deployment ID 包含不安全字符：$DeploymentId"
}

if (-not $ConfirmRollback) {
    Write-Host 'MODE dry-run'
    Write-Host "DRY_RUN_WOULD_ROLLBACK project=$($settings.PagesProjectName) deployment=$DeploymentId"
    Write-Host '未执行 Cloudflare 写入；如需正式回滚，请显式追加 -ConfirmRollback。'
    return
}

$accountId = [Environment]::GetEnvironmentVariable('CLOUDFLARE_ACCOUNT_ID', 'Process')
$apiToken = [Environment]::GetEnvironmentVariable('CLOUDFLARE_API_TOKEN', 'Process')
if ($accountId -notmatch '^[a-fA-F0-9]{32}$') {
    throw '正式 Pages 回滚需要当前进程中的 CLOUDFLARE_ACCOUNT_ID（32 位 account ID）；值不会写入仓库。'
}
if ([string]::IsNullOrWhiteSpace($apiToken)) {
    throw '正式 Pages 回滚需要当前进程中的 CLOUDFLARE_API_TOKEN（至少 Pages Write）；值不会写入仓库。'
}

$escapedAccountId = [Uri]::EscapeDataString($accountId)
$escapedProjectName = [Uri]::EscapeDataString($settings.PagesProjectName)
$escapedDeploymentId = [Uri]::EscapeDataString($DeploymentId)
$uri = "https://api.cloudflare.com/client/v4/accounts/$escapedAccountId/pages/projects/$escapedProjectName/deployments/$escapedDeploymentId/rollback"
$response = Invoke-RestMethod -Method Post -Uri $uri -Headers @{ Authorization = "Bearer $apiToken" } -ContentType 'application/json' -Body '{}'
if (-not [bool]$response.success) {
    throw 'Cloudflare Pages 回滚接口返回失败；请到 Deployments 页面确认当前生产 deployment。'
}

Write-Host "ROLLBACK_COMPLETED project=$($settings.PagesProjectName) deployment=$DeploymentId url=$($settings.PublicUrl)"
