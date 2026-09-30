[CmdletBinding()]
param(
    [string]$ConfigPath
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'lib/Deploy.Common.ps1')

function Invoke-HttpProbe {
    param(
        [Parameter(Mandatory)]
        [Uri]$Uri,
        [ValidateSet('GET', 'OPTIONS')]
        [string]$Method = 'GET',
        [hashtable]$Headers = @{}
    )

    $handler = [System.Net.Http.HttpClientHandler]::new()
    $handler.AllowAutoRedirect = $false
    $handler.AutomaticDecompression = [System.Net.DecompressionMethods]::All
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(15)
    $request = [System.Net.Http.HttpRequestMessage]::new(
        [System.Net.Http.HttpMethod]::new($Method),
        $Uri
    )

    try {
        foreach ($headerName in $Headers.Keys) {
            $request.Headers.TryAddWithoutValidation($headerName, [string]$Headers[$headerName]) | Out-Null
        }
        $response = $client.SendAsync(
            $request,
            [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead
        ).GetAwaiter().GetResult()

        $contentLength = $response.Content.Headers.ContentLength
        if ($null -ne $contentLength -and $contentLength -gt 5MB) {
            throw "响应体超过 5 MiB，停止读取：$Uri"
        }
        $body = $response.Content.ReadAsByteArrayAsync().GetAwaiter().GetResult()
        $contentType = ''
        if ($null -ne $response.Content.Headers.ContentType) {
            $contentType = [string]$response.Content.Headers.ContentType.MediaType
        }

        $allowOrigin = ''
        $allowMethods = ''
        $allowHeaders = ''
        $values = $null
        if ($response.Headers.TryGetValues('Access-Control-Allow-Origin', [ref]$values)) {
            $allowOrigin = ($values -join ',')
        }
        $values = $null
        if ($response.Headers.TryGetValues('Access-Control-Allow-Methods', [ref]$values)) {
            $allowMethods = ($values -join ',')
        }
        $values = $null
        if ($response.Headers.TryGetValues('Access-Control-Allow-Headers', [ref]$values)) {
            $allowHeaders = ($values -join ',')
        }

        return [pscustomobject]@{
            StatusCode = [int]$response.StatusCode
            ContentType = $contentType
            Bytes = $body.Length
            BodyText = [System.Text.Encoding]::UTF8.GetString($body)
            AllowOrigin = $allowOrigin
            AllowMethods = $allowMethods
            AllowHeaders = $allowHeaders
        }
    } finally {
        $request.Dispose()
        $client.Dispose()
        $handler.Dispose()
    }
}

function Join-PublicUrl {
    param(
        [Parameter(Mandatory)]
        [string]$BaseUrl,
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not $Path.StartsWith('/')) {
        $Path = "/$Path"
    }
    return "$($BaseUrl.TrimEnd('/'))$Path"
}

function Assert-LiveConsoleBundle {
    param(
        [Parameter(Mandatory)]
        [pscustomobject]$RootProbe,
        [Parameter(Mandatory)]
        [pscustomobject]$Settings
    )

    $matches = [Regex]::Matches(
        $RootProbe.BodyText,
        'src="(?<src>/[^" ]+\.js(?:\?[^" ]*)?)"',
        [System.Text.RegularExpressions.RegexOptions]::IgnoreCase
    )
    if ($matches.Count -eq 0) {
        throw '线上首页没有发现 JavaScript module 引用，无法验证当前 console bundle。'
    }
    if ($matches.Count -gt 20) {
        throw "线上首页引用了过多 JavaScript 文件：$($matches.Count)"
    }

    foreach ($match in $matches) {
        $sourcePath = $match.Groups['src'].Value
        if ($sourcePath.StartsWith('//')) {
            continue
        }
        $assetProbe = Invoke-HttpProbe ([Uri](Join-PublicUrl $Settings.PublicUrl $sourcePath))
        if ($assetProbe.StatusCode -ne 200) {
            continue
        }
        if ($assetProbe.BodyText.Contains($Settings.ApiBaseUrl)) {
            Write-Host "PASS live-asset path=$sourcePath status=$($assetProbe.StatusCode) apiBase=verified"
            return
        }
    }

    throw "线上 HTML 引用的 JavaScript bundle 均未包含预期 API 地址：$($Settings.ApiBaseUrl)"
}

$resolvedConfigPath = Resolve-DeployConfigPath $ConfigPath
$config = Read-DeployConfig $resolvedConfigPath
$settings = Get-FrontendReleaseSettings $config $resolvedConfigPath
$checks = Get-RequiredProperty $config 'checks' $resolvedConfigPath
$failures = [System.Collections.Generic.List[string]]::new()

if ([bool](Get-OptionalProperty $checks 'enableFrontendCheck' $true)) {
    foreach ($path in $settings.HealthCheckPaths) {
        try {
            $url = Join-PublicUrl $settings.PublicUrl ([string]$path)
            $probe = Invoke-HttpProbe ([Uri]$url)
            if ($probe.StatusCode -notin $settings.AcceptableStatusCodes) {
                throw "状态码 $($probe.StatusCode)，期望 $($settings.AcceptableStatusCodes -join ',')"
            }
            Write-Host "PASS frontend path=$path status=$($probe.StatusCode) type=$($probe.ContentType) bytes=$($probe.Bytes)"
            if ([string]$path -eq '/') {
                Assert-LiveConsoleBundle $probe $settings
            }
        } catch {
            $failures.Add("frontend $path：$($_.Exception.Message)")
        }
    }
}

if ([bool](Get-OptionalProperty $checks 'enableCorsCheck' $true)) {
    try {
        $corsOrigin = [string](Get-RequiredProperty $checks 'corsOrigin' "$resolvedConfigPath.checks")
        $corsUrl = [Uri]([string](Get-RequiredProperty $checks 'corsLoginUrl' "$resolvedConfigPath.checks"))
        $requestedMethod = [string](Get-RequiredProperty $checks 'corsRequestMethod' "$resolvedConfigPath.checks")
        $requestedHeaders = [string](Get-RequiredProperty $checks 'corsRequestHeaders' "$resolvedConfigPath.checks")
        $probe = Invoke-HttpProbe $corsUrl 'OPTIONS' @{
            Origin = $corsOrigin
            'Access-Control-Request-Method' = $requestedMethod
            'Access-Control-Request-Headers' = $requestedHeaders
        }
        if ($probe.StatusCode -notin @(200, 204)) {
            throw "CORS preflight 状态码 $($probe.StatusCode)，期望 200 或 204"
        }
        if ($probe.AllowOrigin -ne $corsOrigin) {
            throw "Access-Control-Allow-Origin 为 '$($probe.AllowOrigin)'，期望 '$corsOrigin'"
        }
        if ($probe.AllowMethods -and $probe.AllowMethods -notmatch "(?i)(^|,)\s*$([Regex]::Escape($requestedMethod))\s*(,|$)") {
            throw "Access-Control-Allow-Methods 不包含 $requestedMethod"
        }
        Write-Host "PASS cors url=$corsUrl status=$($probe.StatusCode) allowOrigin=$($probe.AllowOrigin)"
    } catch {
        $failures.Add("cors：$($_.Exception.Message)")
    }
}

$extraRouteChecks = @(Get-OptionalProperty $checks 'extraRouteChecks' @())
foreach ($route in $extraRouteChecks) {
    try {
        $path = [string](Get-RequiredProperty $route 'path' "$resolvedConfigPath.checks.extraRouteChecks")
        $expectedCodes = @(Get-RequiredProperty $route 'expectedStatusCodes' "$resolvedConfigPath.checks.extraRouteChecks")
        $probe = Invoke-HttpProbe ([Uri](Join-PublicUrl $settings.PublicUrl $path))
        if ($probe.StatusCode -notin $expectedCodes) {
            throw "状态码 $($probe.StatusCode)，期望 $($expectedCodes -join ',')"
        }
        $expectedType = [string](Get-OptionalProperty $route 'expectedContentType' '')
        if ($expectedType -and $probe.ContentType -notlike "$expectedType*") {
            throw "Content-Type 为 '$($probe.ContentType)'，期望以 '$expectedType' 开头"
        }
        $minBytes = [int](Get-OptionalProperty $route 'minBytes' 0)
        if ($probe.Bytes -lt $minBytes) {
            throw "响应体 $($probe.Bytes) bytes，小于最小值 $minBytes"
        }
        Write-Host "PASS extra path=$path status=$($probe.StatusCode) type=$($probe.ContentType) bytes=$($probe.Bytes)"
    } catch {
        $failures.Add("extra route：$($_.Exception.Message)")
    }
}

if ($failures.Count -gt 0) {
    Write-Host "CHECK_FAILED count=$($failures.Count)"
    foreach ($failure in $failures) {
        Write-Host "FAIL $failure"
    }
    throw '线上检查未通过。'
}

Write-Host "CHECK_PASSED url=$($settings.PublicUrl)"
