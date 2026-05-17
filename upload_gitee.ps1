<#
最稳版 Gitee Release 脚本
用法：
  powershell -ExecutionPolicy Bypass -File .\upload_gitee_stable.ps1
  powershell -ExecutionPolicy Bypass -File .\upload_gitee_stable.ps1 -NotesFile .\CHANGELOG.md
  powershell -ExecutionPolicy Bypass -File .\upload_gitee_stable.ps1 -Notes "本次更新说明"

约定：
  1. Gitee token 优先读环境变量 GITEE_TOKEN，其次读脚本同目录 .gitee_token
  2. 默认仓库为 windcloudjet/YamiboReaderPro-Releases（公开分发库）
  3. 默认从 app/build.gradle.kts 的 versionName 生成 tag，例如 v1.11.0
  4. 本地 tag 不存在时会自动创建；tag 指向旧 commit 时默认自动移动到当前 HEAD
  5. 远端 tag 已存在但需要更新时会先尝试 force 推送；如果 Gitee 不接受，会自动删除远端 tag 后重推；Release 已存在时直接复用，不重复创建
#>

[CmdletBinding()]
param(
    [string]$GiteeRepo = "windcloudjet/YamiboReaderPro-Releases",
    [string]$TokenFile,
    [string]$NotesFile,
    [string]$Notes,
    [string]$ApkPath,
    [string]$TargetCommitish,
    [string]$RemoteName = "gitee",
    [switch]$SkipTagPush,
    [switch]$ForceUpload,
    [ValidateSet("Move", "Fail")]
    [string]$TagConflictMode = "Move"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# 在 param(...) 默认值里使用 $PSScriptRoot 不够稳；这里进入脚本正文后再计算脚本目录。
$ScriptDir = $null
try {
    if ($PSCommandPath) {
        $ScriptDir = Split-Path -Parent $PSCommandPath
    }
} catch { }
if (-not $ScriptDir) {
    try {
        if ($MyInvocation.MyCommand.Path) {
            $ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
        }
    } catch { }
}
if (-not $ScriptDir) {
    $ScriptDir = (Get-Location).Path
}
if (-not $TokenFile) {
    $TokenFile = Join-Path $ScriptDir ".gitee_token"
}

# 当本地 tag 被自动移动时，后续远端 tag 也必须 force 更新，否则 Gitee 仍指向旧 commit。
$Script:TagWasMoved = $false

# 老 Windows PowerShell / 老系统上避免 TLS 协议太旧导致 HTTPS 失败
try {
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
} catch { }

function Write-Ok([string]$Message)   { Write-Host "✅ $Message" }
function Write-Warn([string]$Message) { Write-Host "⚠️  $Message" }
function Write-Info([string]$Message) { Write-Host "ℹ️  $Message" }
function Fail([string]$Message)       { Write-Host "❌ $Message"; exit 1 }

function UrlEncode([string]$Value) {
    return [Uri]::EscapeDataString($Value)
}

function Mask-Secret([string]$Text) {
    if (-not $Text) { return $Text }
    $masked = $Text
    try {
        if ($Script:GiteeToken) {
            $masked = $masked -replace [Regex]::Escape($Script:GiteeToken), "***"
            $masked = $masked -replace [Regex]::Escape((UrlEncode $Script:GiteeToken)), "***"
        }
    } catch { }
    return $masked
}

function Read-Token {
    if ($env:GITEE_TOKEN -and $env:GITEE_TOKEN.Trim()) {
        return $env:GITEE_TOKEN.Trim()
    }

    if (Test-Path -LiteralPath $TokenFile) {
        $token = (Get-Content -LiteralPath $TokenFile -Raw -Encoding UTF8).Trim()
        if ($token) { return $token }
    }

    Fail "未找到 Gitee token。请设置环境变量 GITEE_TOKEN，或在脚本同目录创建 .gitee_token。"
}

function Get-HttpErrorText($ErrorRecord) {
    $message = $ErrorRecord.Exception.Message

    if ($ErrorRecord.PSObject.Properties.Name -contains "ErrorDetails") {
        if ($null -ne $ErrorRecord.ErrorDetails -and $ErrorRecord.ErrorDetails.Message) {
            return "$message`n$($ErrorRecord.ErrorDetails.Message)"
        }
    }

    try {
        $response = $ErrorRecord.Exception.Response
        if ($null -ne $response) {
            $stream = $response.GetResponseStream()
            if ($null -ne $stream) {
                $reader = New-Object System.IO.StreamReader($stream)
                $body = $reader.ReadToEnd()
                if ($body) { return "$message`n$body" }
            }
        }
    } catch { }

    return $message
}

function Get-ObjectPropertyValue {
    param(
        [object]$Object,
        [Parameter(Mandatory=$true)][string]$Name
    )

    if ($null -eq $Object) { return $null }

    foreach ($property in $Object.PSObject.Properties) {
        if ($property.Name -eq $Name) {
            return $property.Value
        }
    }

    return $null
}

function ConvertTo-SafeDebugJson {
    param([object]$Object)

    if ($null -eq $Object) { return "<null>" }

    try {
        $text = $Object | ConvertTo-Json -Depth 20 -Compress
        if ($text) { return $text }
    } catch { }

    try {
        return ($Object | Out-String).Trim()
    } catch { }

    return "<无法显示对象>"
}

function Test-GiteeReleaseObject {
    param(
        [object]$Object,
        [string]$ExpectedTag
    )

    if ($null -eq $Object) { return $false }

    $id = Get-ObjectPropertyValue -Object $Object -Name "id"
    if ([string]::IsNullOrWhiteSpace([string]$id)) { return $false }

    $tagName = Get-ObjectPropertyValue -Object $Object -Name "tag_name"
    if (-not [string]::IsNullOrWhiteSpace([string]$tagName)) {
        if ([string]$tagName -ne $ExpectedTag) { return $false }
    }

    return $true
}

function Invoke-GiteeJson {
    param(
        [Parameter(Mandatory=$true)][ValidateSet("GET", "POST", "PATCH", "DELETE")][string]$Method,
        [Parameter(Mandatory=$true)][string]$Path,
        [hashtable]$Body,
        [switch]$Allow404
    )

    $separator = if ($Path.Contains("?")) { "&" } else { "?" }
    $uri = "https://gitee.com/api/v5/$Path${separator}access_token=$Script:EncodedToken"

    $params = @{
        Uri         = $uri
        Method      = $Method
        ErrorAction = "Stop"
        Headers     = @{ Accept = "application/json" }
    }

    if ($PSBoundParameters.ContainsKey("Body") -and $null -ne $Body) {
        # ConvertTo-Json 输出的 \uXXXX 是合法 JSON；Gitee 会按 UTF-8/JSON 正常解析成中文。
        $json = $Body | ConvertTo-Json -Depth 20 -Compress
        $params.Body = [System.Text.Encoding]::UTF8.GetBytes($json)
        $params.ContentType = "application/json; charset=utf-8"
    }

    try {
        return Invoke-RestMethod @params
    } catch {
        $statusCode = $null
        try { $statusCode = [int]$_.Exception.Response.StatusCode } catch { }

        if ($Allow404 -and $statusCode -eq 404) {
            return $null
        }

        $detail = Get-HttpErrorText $_
        throw "$Method https://gitee.com/api/v5/$Path 失败：`n$detail"
    }
}

function Invoke-GiteeFileUpload {
    param(
        [Parameter(Mandatory=$true)][string]$ReleaseId,
        [Parameter(Mandatory=$true)][string]$FilePath
    )

    Add-Type -AssemblyName System.Net.Http

    $fileFullPath = (Resolve-Path -LiteralPath $FilePath).Path
    $fileName = [System.IO.Path]::GetFileName($fileFullPath)
    $uri = "https://gitee.com/api/v5/repos/$Script:OwnerEnc/$Script:RepoEnc/releases/$ReleaseId/attach_files?access_token=$Script:EncodedToken"

    $client = New-Object System.Net.Http.HttpClient
    $content = New-Object System.Net.Http.MultipartFormDataContent
    $fileStream = $null

    try {
        $fileStream = [System.IO.File]::OpenRead($fileFullPath)
        $fileContent = New-Object System.Net.Http.StreamContent($fileStream)
        $fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("application/vnd.android.package-archive")
        $content.Add($fileContent, "file", $fileName)

        $response = $client.PostAsync($uri, $content).GetAwaiter().GetResult()
        $text = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()

        if (-not $response.IsSuccessStatusCode) {
            throw "HTTP $([int]$response.StatusCode) $($response.ReasonPhrase)`n$text"
        }

        if ($text) {
            return $text | ConvertFrom-Json
        }

        return $null
    } finally {
        if ($null -ne $fileStream) { $fileStream.Dispose() }
        $content.Dispose()
        $client.Dispose()
    }
}

function Require-GitRepo {
    & git rev-parse --show-toplevel *> $null
    if ($LASTEXITCODE -ne 0) {
        Fail "当前目录不是 Git 仓库。请在项目根目录运行脚本。"
    }
}

function Run-GitOrFail([string[]]$Arguments, [string]$What) {
    $output = & git @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        Fail "$What 失败：`n$($output | Out-String)"
    }
    return $output
}

function Get-VersionName {
    $gradleFile = "app\build.gradle.kts"
    if (-not (Test-Path -LiteralPath $gradleFile)) {
        Fail "找不到 $gradleFile，无法读取 versionName。"
    }

    $match = Select-String -Path $gradleFile -Pattern 'versionName\s*=\s*"([^"]+)"' | Select-Object -First 1
    if (-not $match) {
        Fail "在 $gradleFile 中找不到 versionName = \"...\"。"
    }

    return $match.Matches[0].Groups[1].Value.Trim()
}

function Resolve-ApkPath {
    if ($ApkPath) {
        if (-not (Test-Path -LiteralPath $ApkPath)) {
            Fail "指定的 APK 不存在：$ApkPath"
        }
        return (Resolve-Path -LiteralPath $ApkPath).Path
    }

    $candidates = @(
        "app\release\app-release.apk",
        "app\build\outputs\apk\release\app-release.apk"
    )

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath $candidate) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    Write-Host "❌ 找不到 release APK，已尝试："
    $candidates | ForEach-Object { Write-Host "   $_" }
    Write-Host "请先执行：.\gradlew assembleRelease"
    exit 1
}

function Get-CurrentCommit {
    return ((Run-GitOrFail -Arguments @("rev-parse", "HEAD") -What "读取当前 commit") | Out-String).Trim()
}

function Ensure-LocalTag([string]$Tag) {
    $currentCommit = Get-CurrentCommit

    & git rev-parse -q --verify "refs/tags/$Tag" *> $null
    if ($LASTEXITCODE -eq 0) {
        $tagCommit = ((Run-GitOrFail -Arguments @("rev-list", "-n", "1", $Tag) -What "读取本地 tag commit") | Out-String).Trim()
        if ($tagCommit -ne $currentCommit) {
            if ($TagConflictMode -eq "Fail") {
                Fail "本地 tag $Tag 已存在，但不是指向当前 HEAD。`n当前 HEAD: $currentCommit`n${Tag}: $tagCommit`n可去掉 -TagConflictMode Fail，让脚本自动移动 tag；或手动处理 tag。"
            }

            Write-Warn "本地 tag $Tag 指向旧 commit，将自动移动到当前 HEAD。"
            Write-Host "   当前 HEAD: $currentCommit"
            Write-Host "   ${Tag}: $tagCommit"

            Run-GitOrFail -Arguments @("tag", "-d", $Tag) -What "删除旧本地 tag" *> $null
            Run-GitOrFail -Arguments @("tag", "-a", $Tag, "-m", "Release $Tag") -What "重新创建本地 tag" *> $null
            $Script:TagWasMoved = $true
            Write-Ok "已将本地 tag $Tag 移动到当前 HEAD。"
        } else {
            Write-Ok "本地 tag 已存在且指向当前 HEAD：$Tag"
        }
        return
    }

    Run-GitOrFail -Arguments @("tag", "-a", $Tag, "-m", "Release $Tag") -What "创建本地 tag" *> $null
    Write-Ok "已创建本地 tag：$Tag"
}

function Get-PushTarget {
    & git remote get-url $RemoteName *> $null
    if ($LASTEXITCODE -eq 0) {
        return $RemoteName
    }

    Write-Warn "未找到 Git remote '$RemoteName'，改用 token HTTPS URL 推送 tag。"
    $gitToken = UrlEncode $Script:GiteeToken
    return "https://$($Script:Owner):$gitToken@gitee.com/$($Script:Owner)/$($Script:RepoName).git"
}

function Get-RemoteTagCommit([string]$PushTarget, [string]$Tag) {
    $output = & git ls-remote --tags $PushTarget "refs/tags/$Tag" "refs/tags/$Tag^{}" 2>$null
    if ($LASTEXITCODE -ne 0) { return $null }

    $exactSha = $null
    foreach ($line in @($output)) {
        if (-not $line) { continue }
        $parts = ($line -split "\s+")
        if ($parts.Count -lt 2) { continue }
        $sha = $parts[0]
        $ref = $parts[1]

        if ($ref -eq "refs/tags/$Tag^{}") {
            return $sha
        }
        if ($ref -eq "refs/tags/$Tag") {
            $exactSha = $sha
        }
    }

    return $exactSha
}

function Test-RemoteTag([string]$PushTarget, [string]$Tag) {
    $sha = Get-RemoteTagCommit -PushTarget $PushTarget -Tag $Tag
    return -not [string]::IsNullOrWhiteSpace($sha)
}

function Push-Tag([string]$PushTarget, [string]$Tag, [bool]$Force) {
    $normalRefSpec = "refs/tags/${Tag}:refs/tags/${Tag}"
    $refSpec = $normalRefSpec
    if ($Force) {
        $refSpec = "+$normalRefSpec"
    }

    # 注意：git/Gitee 常把 remote: banner 写到 stderr。
    # 这里必须以 $LASTEXITCODE 为准，不能因为 stderr 有内容就判断失败。
    $output = & git push $PushTarget $refSpec 2>&1
    if ($LASTEXITCODE -eq 0) {
        return
    }

    $firstError = Mask-Secret ($output | Out-String)

    if ($Force) {
        # 有些 Gitee 仓库/钩子不接受 tag 的 force update，但允许先删除远端 tag 再推送。
        # 这样可以自动处理“远端 tag 指向旧 commit”的情况。
        Write-Warn "force 更新远端 tag 失败，将尝试先删除远端 tag 再重新推送。"
        if ($firstError.Trim()) {
            Write-Host "   首次失败输出:"
            foreach ($line in ($firstError -split "`r?`n")) {
                if ($line.Trim()) { Write-Host "   $line" }
            }
        }

        $deleteRefSpec = ":refs/tags/${Tag}"
        $deleteOutput = & git push $PushTarget $deleteRefSpec 2>&1
        if ($LASTEXITCODE -ne 0) {
            $deleteError = Mask-Secret ($deleteOutput | Out-String)
            throw "force 更新远端 tag 失败，且删除远端 tag 也失败。`n首次失败：`n$firstError`n删除失败：`n$deleteError"
        }

        $pushOutput = & git push $PushTarget $normalRefSpec 2>&1
        if ($LASTEXITCODE -ne 0) {
            $pushError = Mask-Secret ($pushOutput | Out-String)
            throw "删除远端 tag 后重新推送仍失败：`n$pushError"
        }

        return
    }

    throw "推送 Gitee tag 失败：`n$firstError"
}

function Ensure-RemoteTag([string]$Tag) {
    if ($SkipTagPush) {
        Write-Warn "已跳过推送 tag：$Tag"
        return
    }

    $pushTarget = Get-PushTarget
    $currentCommit = Get-CurrentCommit
    $remoteCommit = Get-RemoteTagCommit -PushTarget $pushTarget -Tag $Tag
    $remoteExists = -not [string]::IsNullOrWhiteSpace($remoteCommit)

    if ($remoteExists -and $remoteCommit -ne $currentCommit) {
        if ($TagConflictMode -eq "Fail") {
            Fail "Gitee 远端 tag $Tag 已存在，但不是指向当前 HEAD。`n当前 HEAD: $currentCommit`n远端 ${Tag}: $remoteCommit`n请手动处理远端 tag，或使用默认 Move 模式。"
        }
        Write-Warn "Gitee 远端 tag $Tag 指向旧 commit，将 force 更新到当前 HEAD。"
        Write-Host "   当前 HEAD: $currentCommit"
        Write-Host "   远端 ${Tag}: $remoteCommit"
        try {
            Push-Tag -PushTarget $pushTarget -Tag $Tag -Force $true
        } catch {
            Fail "$($_.Exception.Message)"
        }
        Write-Ok "已 force 更新 Gitee 远端 tag：$Tag"
        return
    }

    if ($remoteExists) {
        Write-Ok "Gitee 远端 tag 已存在且指向当前 HEAD：$Tag"
        return
    }

    try {
        Push-Tag -PushTarget $pushTarget -Tag $Tag -Force $false
    } catch {
        # 避免网络抖动/并发发布导致误判：失败后再查一次远端 tag。
        $remoteCommitAfterFailure = Get-RemoteTagCommit -PushTarget $pushTarget -Tag $Tag
        if ($remoteCommitAfterFailure) {
            Write-Ok "Gitee 远端 tag 已存在：$Tag"
            return
        }
        Fail "$($_.Exception.Message)"
    }

    Write-Ok "已推送 Gitee tag：$Tag"
}

function Build-ReleaseNotes([string]$Tag) {
    if ($NotesFile) {
        if (-not (Test-Path -LiteralPath $NotesFile)) {
            Fail "NotesFile 不存在：$NotesFile"
        }
        $content = (Get-Content -LiteralPath $NotesFile -Raw -Encoding UTF8).Trim()
        if ($content) { return $content }
        return "Release $Tag"
    }

    if ($Notes) {
        return $Notes.Trim()
    }

    $previousTag = (& git describe --tags --abbrev=0 --exclude="$Tag" 2>$null)
    $previousTag = (($previousTag | Out-String).Trim())

    if ($previousTag) {
        $log = (& git log "$previousTag..HEAD" --pretty=format:"* %s" 2>$null)
        $content = (($log | Out-String).Trim())
        if ($content) {
            Write-Info "Release Notes 自动生成范围：$previousTag..HEAD"
            return $content
        }
    }

    $lastCommit = (& git log -1 --pretty=format:"* %s" 2>$null)
    $lastCommitText = (($lastCommit | Out-String).Trim())
    if ($lastCommitText) { return $lastCommitText }

    return "Release $Tag"
}

function Get-TargetCommitish {
    if ($TargetCommitish) { return $TargetCommitish }

    $branch = (& git rev-parse --abbrev-ref HEAD 2>$null)
    $branch = (($branch | Out-String).Trim())

    if ($branch -and $branch -ne "HEAD") {
        return $branch
    }

    return Get-CurrentCommit
}

function Find-ReleaseByTag([string]$Tag) {
    $tagEnc = UrlEncode $Tag

    # Gitee 支持按 tag 查 release；但不同返回形态并不完全稳定。
    # 因此这里不能只判断“有返回值”，必须确认对象里真的有 id。
    $rawRelease = Invoke-GiteeJson -Method GET -Path "repos/$Script:OwnerEnc/$Script:RepoEnc/releases/tags/$tagEnc" -Allow404
    foreach ($candidate in @($rawRelease)) {
        if (Test-GiteeReleaseObject -Object $candidate -ExpectedTag $Tag) {
            return $candidate
        }
    }

    if ($rawRelease) {
        Write-Warn "按 tag 查询返回了内容，但不是有效 Release 对象，将改用列表兜底查询。返回摘要：$(ConvertTo-SafeDebugJson $rawRelease)"
    }

    # 兜底：有些环境/权限下按 tag 查询可能不可用，再分页扫 releases。
    for ($page = 1; $page -le 5; $page++) {
        $list = Invoke-GiteeJson -Method GET -Path "repos/$Script:OwnerEnc/$Script:RepoEnc/releases?page=$page&per_page=100" -Allow404
        if (-not $list) { break }

        foreach ($item in @($list)) {
            $itemTag = Get-ObjectPropertyValue -Object $item -Name "tag_name"
            if ([string]$itemTag -eq $Tag) {
                if (Test-GiteeReleaseObject -Object $item -ExpectedTag $Tag) {
                    return $item
                }
                Write-Warn "列表中找到了 tag=$Tag 的条目，但没有 id，已忽略。条目摘要：$(ConvertTo-SafeDebugJson $item)"
            }
        }

        if (@($list).Count -lt 100) { break }
    }

    return $null
}

function Update-ExistingReleaseIfNeeded($Release, [string]$Tag, [string]$ReleaseNotes, [string]$Target) {
    if (-not $Release) { return $Release }
    if (-not $Script:TagWasMoved) { return $Release }

    $releaseId = [string](Get-ObjectPropertyValue -Object $Release -Name "id")
    if (-not $releaseId) { return $Release }

    Write-Info "tag 已移动，尝试刷新已有 Gitee Release 的标题、说明和目标 commit..."
    $body = @{
        tag_name         = [string]$Tag
        name             = [string]$Tag
        body             = [string]$ReleaseNotes
        target_commitish = [string]$Target
    }

    try {
        $updated = Invoke-GiteeJson -Method PATCH -Path "repos/$Script:OwnerEnc/$Script:RepoEnc/releases/$releaseId" -Body $body
        if ($updated) {
            Write-Ok "Gitee Release 信息已刷新：id=$releaseId"
            return $updated
        }
    } catch {
        Write-Warn "刷新已有 Release 信息失败，但会继续上传 APK。详情：$($_.Exception.Message)"
    }

    return $Release
}

function Get-AttachmentUrl($Release, [string]$FileName) {
    if (-not $Release) { return $null }

    $collections = @()
    $attachFiles = Get-ObjectPropertyValue -Object $Release -Name "attach_files"
    if ($attachFiles) { $collections += ,$attachFiles }

    $assets = Get-ObjectPropertyValue -Object $Release -Name "assets"
    if ($assets) { $collections += ,$assets }

    foreach ($collection in $collections) {
        foreach ($item in @($collection)) {
            if (-not $item) { continue }
            $names = @()
            foreach ($prop in @("name", "filename", "file_name")) {
                $value = Get-ObjectPropertyValue -Object $item -Name $prop
                if ($value) { $names += [string]$value }
            }

            if ($names -contains $FileName) {
                foreach ($urlProp in @("browser_download_url", "download_url", "url")) {
                    $value = Get-ObjectPropertyValue -Object $item -Name $urlProp
                    if ($value) { return [string]$value }
                }
                return "已存在，但接口未返回下载地址"
            }
        }
    }

    return $null
}

# -------------------- 主流程 --------------------
Require-GitRepo

if ($GiteeRepo -notmatch "^[^/]+/[^/]+$") {
    Fail "GiteeRepo 格式应为 owner/repo，例如 windcloudjet/YamiboReaderPro。当前：$GiteeRepo"
}

$parts = $GiteeRepo.Split("/", 2)
$Script:Owner = $parts[0]
$Script:RepoName = $parts[1]
$Script:OwnerEnc = UrlEncode $Script:Owner
$Script:RepoEnc = UrlEncode $Script:RepoName

$Script:GiteeToken = Read-Token
$Script:EncodedToken = UrlEncode $Script:GiteeToken

$version = Get-VersionName
$tag = "v$version"
$apk = Resolve-ApkPath
$apkName = [System.IO.Path]::GetFileName($apk)
$releaseNotes = Build-ReleaseNotes $tag
$target = Get-TargetCommitish

Write-Host "========== Gitee Release =========="
Write-Host "仓库: $GiteeRepo"
Write-Host "版本: $version"
Write-Host "标签: $tag"
Write-Host "APK : $apk"
Write-Host "目标: $target"
Write-Host "冲突: $TagConflictMode"
Write-Host "==================================="

Ensure-LocalTag $tag
Ensure-RemoteTag $tag

Write-Info "检查 Gitee Release 是否已存在..."
$release = Find-ReleaseByTag $tag

if ($release) {
    $release = Update-ExistingReleaseIfNeeded -Release $release -Tag $tag -ReleaseNotes $releaseNotes -Target $target
    $releaseId = [string](Get-ObjectPropertyValue -Object $release -Name "id")
    if (-not $releaseId) {
        Write-Warn "查到的 Gitee Release 没有 id，将忽略并尝试重新创建。返回摘要：$(ConvertTo-SafeDebugJson $release)"
        $release = $null
    } else {
        Write-Ok "Gitee Release 已存在，直接复用：id=$releaseId"
    }
}

if (-not $release) {
    $body = @{
        tag_name         = [string]$tag
        name             = [string]$tag
        body             = [string]$releaseNotes
        target_commitish = [string]$target
    }

    Write-Info "创建 Gitee Release..."
    $release = Invoke-GiteeJson -Method POST -Path "repos/$Script:OwnerEnc/$Script:RepoEnc/releases" -Body $body
    $releaseId = [string](Get-ObjectPropertyValue -Object $release -Name "id")
    if (-not $releaseId) {
        Fail "Gitee Release 创建接口没有返回 id。返回摘要：$(ConvertTo-SafeDebugJson $release)"
    }
    Write-Ok "Gitee Release 创建成功：id=$releaseId"
}

if (-not $releaseId) {
    Fail "未取得 Gitee release id，无法上传 APK。"
}

$existingUrl = Get-AttachmentUrl $release $apkName
$shouldUpload = ([bool]$ForceUpload) -or $Script:TagWasMoved
$uploadPath = $apk
$tempUploadPath = $null

if ($existingUrl -and -not $shouldUpload) {
    Write-Ok "APK 附件已存在，跳过上传：$apkName"
    Write-Host "下载地址: $existingUrl"
} else {
    if ($existingUrl -and $Script:TagWasMoved -and -not $ForceUpload) {
        # 已有同名附件很可能是旧 commit 的包。为避免继续复用旧包，上传一个带版本和 commit 后缀的新 APK。
        $shortCommit = (Get-CurrentCommit).Substring(0, 7)
        $tempName = "YamiboReaderPro-${tag}-${shortCommit}.apk"
        $tempUploadPath = Join-Path ([System.IO.Path]::GetTempPath()) $tempName
        Copy-Item -LiteralPath $apk -Destination $tempUploadPath -Force
        $uploadPath = $tempUploadPath
        Write-Warn "已有同名 APK 附件：$apkName。为避免旧包被误用，将上传新文件：$tempName"
    }

    Write-Info "上传 APK 附件：$([System.IO.Path]::GetFileName($uploadPath))"
    try {
        $uploadResp = Invoke-GiteeFileUpload -ReleaseId $releaseId -FilePath $uploadPath
        $downloadUrl = $null

        if ($uploadResp) {
            foreach ($prop in @("browser_download_url", "download_url", "url")) {
                $value = Get-ObjectPropertyValue -Object $uploadResp -Name $prop
                if ($value) {
                    $downloadUrl = [string]$value
                    break
                }
            }
        }

        Write-Ok "APK 上传成功"
        if ($downloadUrl) {
            Write-Host "下载地址: $downloadUrl"
        } else {
            Write-Warn "上传成功，但接口未返回下载地址；请在 Release 页面确认。"
        }
    } catch {
        Fail "APK 上传失败：`n$($_.Exception.Message)"
    } finally {
        if ($tempUploadPath -and (Test-Path -LiteralPath $tempUploadPath)) {
            Remove-Item -LiteralPath $tempUploadPath -Force -ErrorAction SilentlyContinue
        }
    }
}

Write-Ok "完成：https://gitee.com/$GiteeRepo/releases/tag/$tag"
