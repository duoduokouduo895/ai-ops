[CmdletBinding()]
param(
    [ValidateSet('frontend', 'check', 'backend', 'all')]
    [string]$Target = 'frontend',
    [switch]$ConfirmDeploy,
    [switch]$PreflightOnly,
    [string]$ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Deploy.Common.ps1')

$resolvedConfigPath = Resolve-DeployConfigPath $ConfigPath

if ($Target -eq 'check') {
    & (Join-Path $PSScriptRoot 'check.ps1') -ConfigPath $resolvedConfigPath
    return
}

if ($Target -in @('backend', 'all')) {
    throw "当前 deploy/ 是 frontend-console 专用入口，不会发布后端；不支持 Target=$Target。"
}

$config = Read-DeployConfig $resolvedConfigPath
$settings = Get-FrontendReleaseSettings $config $resolvedConfigPath

if ($PreflightOnly) {
    if ($ConfirmDeploy) {
        throw 'PreflightOnly 与 ConfirmDeploy 不能同时使用。'
    }
    $paths = Get-FrontendPaths $settings
    Assert-BuildOutput $paths.OutputRoot $settings.ApiBaseUrl
    $wrangler = Assert-WranglerAuthenticated $paths.FrontendRoot
    Invoke-CheckedProcess $wrangler @('pages', 'deployment', 'list', '--project-name', $settings.PagesProjectName) $paths.FrontendRoot 'wrangler'
    Write-Host "PREFLIGHT_READY target=frontend project=$($settings.PagesProjectName) (未执行 Cloudflare 写入)"
    return
}

if (-not $ConfirmDeploy) {
    Write-Host 'MODE dry-run'
}

$paths = Invoke-FrontendBuild $settings

if (-not $ConfirmDeploy) {
    Write-Host "DRY_RUN_READY target=frontend project=$($settings.PagesProjectName) publicUrl=$($settings.PublicUrl)"
    Write-Host "DRY_RUN_WOULD_RUN wrangler pages deploy $($paths.OutputRoot) --project-name $($settings.PagesProjectName)"
    Write-Host '未执行 Cloudflare 写入；如需正式发布，请在配置完成后显式追加 -ConfirmDeploy。'
    return
}

$wrangler = Assert-WranglerAuthenticated $paths.FrontendRoot
$deployArguments = @(
    'pages',
    'deploy',
    $paths.OutputRoot,
    '--project-name',
    $settings.PagesProjectName,
    '--commit-message',
    'frontend-console release to ops.dduo.me'
)
Invoke-CheckedProcess $wrangler $deployArguments $paths.FrontendRoot 'wrangler'
Write-Host "DEPLOY_COMPLETED target=frontend project=$($settings.PagesProjectName) url=$($settings.PublicUrl)"
