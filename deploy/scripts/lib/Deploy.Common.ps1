Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-DeployRepoRoot {
    $deployRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
    return [System.IO.Path]::GetFullPath((Split-Path -Parent $deployRoot))
}

function Resolve-DeployConfigPath {
    param(
        [AllowNull()]
        [string]$Path
    )

    $repoRoot = Get-DeployRepoRoot
    if ([string]::IsNullOrWhiteSpace($Path)) {
        $Path = Join-Path $repoRoot 'deploy/deploy.config.json'
    } elseif (-not [System.IO.Path]::IsPathRooted($Path)) {
        $Path = Join-Path (Get-Location) $Path
    }

    return [System.IO.Path]::GetFullPath($Path)
}

function Resolve-DeployPath {
    param(
        [Parameter(Mandatory)]
        [string]$Path,
        [Parameter(Mandatory)]
        [string]$RepoRoot
    )

    if ([System.IO.Path]::IsPathRooted($Path)) {
        throw "部署配置中的本地路径必须相对于项目根目录：$Path"
    }

    $repoFull = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\')
    $candidate = [System.IO.Path]::GetFullPath((Join-Path $repoFull $Path))
    $rootPrefix = "$repoFull\"
    $insideRepo = $candidate.Equals($repoFull, [System.StringComparison]::OrdinalIgnoreCase) -or
        $candidate.StartsWith($rootPrefix, [System.StringComparison]::OrdinalIgnoreCase)
    if (-not $insideRepo) {
        throw "部署配置中的路径越过项目根目录：$Path"
    }

    return $candidate
}

function Get-RequiredProperty {
    param(
        [AllowNull()]
        [object]$Object,
        [Parameter(Mandatory)]
        [string]$Name,
        [Parameter(Mandatory)]
        [string]$ConfigPath
    )

    if ($null -eq $Object) {
        throw "部署配置缺少对象：$ConfigPath"
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property -or $null -eq $property.Value) {
        throw "部署配置缺少字段：$ConfigPath.$Name"
    }
    return $property.Value
}

function Get-OptionalProperty {
    param(
        [AllowNull()]
        [object]$Object,
        [Parameter(Mandatory)]
        [string]$Name,
        [AllowNull()]
        [object]$DefaultValue
    )

    if ($null -eq $Object) {
        return $DefaultValue
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $DefaultValue
    }
    return $property.Value
}

function Read-DeployConfig {
    param(
        [Parameter(Mandatory)]
        [string]$ConfigPath
    )

    if (-not (Test-Path -LiteralPath $ConfigPath -PathType Leaf)) {
        $example = Join-Path (Get-DeployRepoRoot) 'deploy/deploy.config.example.jsonc'
        throw "未找到真实部署配置：$ConfigPath。请复制 $example，删除注释，并填写现有 Cloudflare Pages 项目名后保存为 deploy/deploy.config.json。"
    }

    try {
        $config = Get-Content -LiteralPath $ConfigPath -Raw | ConvertFrom-Json
    } catch {
        throw "部署配置不是有效的严格 JSON：$ConfigPath。请不要把 JSONC 注释复制到真实配置中。原始错误：$($_.Exception.Message)"
    }

    $schemaVersion = Get-RequiredProperty $config 'schemaVersion' $ConfigPath
    if ($schemaVersion -ne '1.0.0') {
        throw "不支持的部署配置 schemaVersion：$schemaVersion；当前只支持 1.0.0。"
    }
    return $config
}

function Get-CloudflareProjectName {
    param(
        [Parameter(Mandatory)]
        [object]$Config,
        [Parameter(Mandatory)]
        [string]$ConfigPath
    )

    $platform = Get-RequiredProperty $Config 'platform' $ConfigPath
    $cloudflare = Get-RequiredProperty $platform 'cloudflare' "$ConfigPath.platform"
    $name = [string](Get-RequiredProperty $cloudflare 'pagesProjectName' "$ConfigPath.platform.cloudflare")
    if ([string]::IsNullOrWhiteSpace($name) -or $name -match 'REPLACE_WITH|[<>\s]') {
        throw "请在真实部署配置中填写现有 Cloudflare Pages 项目名；不要创建新项目或填写占位符。"
    }
    if ($name -notmatch '^[A-Za-z0-9][A-Za-z0-9_-]{0,62}$') {
        throw "Cloudflare Pages 项目名包含不安全字符：$name"
    }
    return $name
}

function Get-FrontendReleaseSettings {
    param(
        [Parameter(Mandatory)]
        [object]$Config,
        [Parameter(Mandatory)]
        [string]$ConfigPath
    )

    $frontend = Get-RequiredProperty $Config 'frontend' $ConfigPath
    $enabled = [bool](Get-RequiredProperty $frontend 'enabled' "$ConfigPath.frontend")
    if (-not $enabled) {
        throw 'frontend.enabled 必须为 true；这套入口专门服务 frontend-console。'
    }

    $deployType = [string](Get-RequiredProperty $frontend 'deployType' "$ConfigPath.frontend")
    if ($deployType -ne 'cloudflare-pages') {
        throw "这套入口只支持 cloudflare-pages，实际配置为：$deployType"
    }

    $publicUrl = [string](Get-RequiredProperty $frontend 'publicUrl' "$ConfigPath.frontend")
    $apiBaseUrl = [string](Get-RequiredProperty $frontend 'apiBaseUrl' "$ConfigPath.frontend")
    if ($publicUrl.TrimEnd('/') -ne 'https://ops.dduo.me') {
        throw "这套入口固定发布到 https://ops.dduo.me，实际 publicUrl 为：$publicUrl"
    }
    if ($apiBaseUrl.TrimEnd('/') -notin @('/api/v1/console', 'https://ops-api.dduo.me/api/v1/console')) {
        throw "Access 使用 /api/v1/console；账号密码回滚模式使用 https://ops-api.dduo.me/api/v1/console，实际为：$apiBaseUrl"
    }

    $installStrategy = [string](Get-RequiredProperty $frontend 'installStrategy' "$ConfigPath.frontend")
    if ($installStrategy -notin @('npm-ci', 'none')) {
        throw "不支持的 frontend.installStrategy：$installStrategy"
    }

    $buildCommand = [string](Get-RequiredProperty $frontend 'buildCommand' "$ConfigPath.frontend")
    if ($buildCommand -ne 'npm run build') {
        throw "这套入口只允许 frontend-console 的固定构建命令 npm run build，实际为：$buildCommand"
    }

    $localPath = [string](Get-RequiredProperty $frontend 'localPath' "$ConfigPath.frontend")
    $outputPath = [string](Get-RequiredProperty $frontend 'buildOutputDir' "$ConfigPath.frontend")
    $healthCheckPaths = @(Get-RequiredProperty $frontend 'healthCheckPaths' "$ConfigPath.frontend")
    $acceptableStatusCodes = @(Get-RequiredProperty $frontend 'acceptableStatusCodes' "$ConfigPath.frontend")

    return [pscustomobject]@{
        PagesProjectName = Get-CloudflareProjectName $Config $ConfigPath
        PublicUrl = $publicUrl.TrimEnd('/')
        ApiBaseUrl = $apiBaseUrl.TrimEnd('/')
        AuthMode = $(if ($apiBaseUrl.TrimEnd('/') -eq '/api/v1/console') { 'access' } else { 'session' })
        LocalPath = $localPath
        OutputPath = $outputPath
        InstallStrategy = $installStrategy
        BuildCommand = $buildCommand
        HealthCheckPaths = $healthCheckPaths
        AcceptableStatusCodes = $acceptableStatusCodes
    }
}

function Get-ExecutablePath {
    param(
        [Parameter(Mandatory)]
        [string]$Name
    )

    $command = Get-Command $Name -ErrorAction SilentlyContinue
    if ($null -eq $command) {
        throw "找不到可执行文件：$Name"
    }
    if (-not [string]::IsNullOrWhiteSpace($command.Source)) {
        return $command.Source
    }
    return $command.Path
}

function Assert-NodeRuntime {
    $node = Get-ExecutablePath 'node.exe'
    $versionText = (& $node --version 2>&1 | Out-String).Trim()
    if ($LASTEXITCODE -ne 0 -or $versionText -notmatch '^v(?<version>\d+\.\d+\.\d+)') {
        throw "无法读取 Node.js 版本；Wrangler 4.86.0 需要 Node.js >= 20.3.0。"
    }
    $version = [Version]$Matches.version
    if ($version -lt [Version]'20.3.0') {
        throw "Node.js 版本过低：$versionText；Wrangler 4.86.0 需要 Node.js >= 20.3.0。"
    }
    Write-Host "RUNTIME node=$versionText"
}

function Invoke-CheckedProcess {
    param(
        [Parameter(Mandatory)]
        [string]$FilePath,
        [Parameter(Mandatory)]
        [string[]]$ArgumentList,
        [Parameter(Mandatory)]
        [string]$WorkingDirectory,
        [Parameter(Mandatory)]
        [string]$DisplayName
    )

    Write-Host ("RUN {0} {1}" -f $DisplayName, ($ArgumentList -join ' '))
    Push-Location $WorkingDirectory
    try {
        $processOutput = & $FilePath @ArgumentList 2>&1
        $processOutput | Out-Host
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            throw "$DisplayName 失败，退出码：$exitCode"
        }
    } finally {
        Pop-Location
    }
}

function Get-FrontendPaths {
    param(
        [Parameter(Mandatory)]
        [pscustomobject]$Settings
    )

    $repoRoot = Get-DeployRepoRoot
    $frontendRoot = Resolve-DeployPath $Settings.LocalPath $repoRoot
    if (-not (Test-Path -LiteralPath $frontendRoot -PathType Container)) {
        throw "前端目录不存在：$frontendRoot"
    }
    $outputRoot = Resolve-DeployPath $Settings.OutputPath $repoRoot
    return [pscustomobject]@{
        RepoRoot = $repoRoot
        FrontendRoot = $frontendRoot
        OutputRoot = $outputRoot
    }
}

function Assert-BuildOutput {
    param(
        [Parameter(Mandatory)]
        [string]$OutputRoot,
        [Parameter(Mandatory)]
        [string]$ExpectedApiBaseUrl
    )

    if (-not (Test-Path -LiteralPath $OutputRoot -PathType Container)) {
        throw "构建产物目录不存在：$OutputRoot"
    }
    foreach ($requiredFile in @('index.html', '_headers', '_redirects')) {
        if (-not (Test-Path -LiteralPath (Join-Path $OutputRoot $requiredFile) -PathType Leaf)) {
            throw "构建产物缺少 Pages 必需文件：$requiredFile"
        }
    }
    if ($ExpectedApiBaseUrl -eq '/api/v1/console') {
        foreach ($requiredFile in @('_routes.json', '_worker.js')) {
            if (-not (Test-Path -LiteralPath (Join-Path $OutputRoot $requiredFile))) {
                throw "Access 构建缺少同域 API 转发产物：$requiredFile"
            }
        }
    }

    $jsFiles = @(Get-ChildItem -LiteralPath $OutputRoot -Recurse -File -Filter '*.js')
    if ($jsFiles.Count -eq 0) {
        throw "构建产物没有 JavaScript 文件：$OutputRoot"
    }

    $apiBaseFound = $false
    foreach ($jsFile in $jsFiles) {
        $content = Get-Content -LiteralPath $jsFile.FullName -Raw
        if ($content.Contains($ExpectedApiBaseUrl)) {
            $apiBaseFound = $true
            break
        }
    }
    if (-not $apiBaseFound) {
        throw "构建产物没有包含预期的 AIDiag API 地址：$ExpectedApiBaseUrl；拒绝继续发布。"
    }

    $indexBytes = (Get-Item -LiteralPath (Join-Path $OutputRoot 'index.html')).Length
    Write-Host "BUILD_OUTPUT path=$OutputRoot indexBytes=$indexBytes jsFiles=$($jsFiles.Count) apiBase=verified"
}

function Invoke-FrontendBuild {
    param(
        [Parameter(Mandatory)]
        [pscustomobject]$Settings
    )

    $paths = Get-FrontendPaths $Settings
    Assert-NodeRuntime
    $npm = Get-ExecutablePath 'npm.cmd'

    if ($Settings.InstallStrategy -eq 'npm-ci') {
        Invoke-CheckedProcess $npm @('ci', '--no-audit', '--no-fund') $paths.FrontendRoot 'npm'
    }

    $previousApiBase = [Environment]::GetEnvironmentVariable('VITE_AIDIAG_API_BASE_URL', 'Process')
    $previousAuthMode = [Environment]::GetEnvironmentVariable('VITE_CONSOLE_AUTH_MODE', 'Process')
    try {
        $env:VITE_AIDIAG_API_BASE_URL = $Settings.ApiBaseUrl
        $env:VITE_CONSOLE_AUTH_MODE = $Settings.AuthMode
        Invoke-CheckedProcess $npm @('run', 'build') $paths.FrontendRoot 'npm'
    } finally {
        if ($null -eq $previousApiBase) {
            Remove-Item Env:VITE_AIDIAG_API_BASE_URL -ErrorAction SilentlyContinue
        } else {
            $env:VITE_AIDIAG_API_BASE_URL = $previousApiBase
        }
        if ($null -eq $previousAuthMode) {
            Remove-Item Env:VITE_CONSOLE_AUTH_MODE -ErrorAction SilentlyContinue
        } else {
            $env:VITE_CONSOLE_AUTH_MODE = $previousAuthMode
        }
    }

    Assert-BuildOutput $paths.OutputRoot $Settings.ApiBaseUrl
    return $paths
}

function Get-LocalWranglerPath {
    param(
        [Parameter(Mandatory)]
        [string]$FrontendRoot
    )

    $candidate = Join-Path $FrontendRoot 'node_modules/.bin/wrangler.cmd'
    if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw "找不到项目锁定的 Wrangler：$candidate。请先在 frontend-console 执行 npm ci；不会自动使用未锁定的全局 Wrangler。"
    }
    return [System.IO.Path]::GetFullPath($candidate)
}

function Assert-WranglerAuthenticated {
    param(
        [Parameter(Mandatory)]
        [string]$FrontendRoot
    )

    $wrangler = Get-LocalWranglerPath $FrontendRoot
    Invoke-CheckedProcess $wrangler @('whoami') $FrontendRoot 'wrangler'
    return $wrangler
}
