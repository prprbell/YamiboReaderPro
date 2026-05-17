$GITEE_REPO = "windcloudjet/YamiboReaderPro"
$GITEE_TOKEN_FILE = Join-Path $PSScriptRoot ".gitee_token"

$giteeToken = $null
if (Test-Path $GITEE_TOKEN_FILE) {
    $giteeToken = (Get-Content -Path $GITEE_TOKEN_FILE -Raw).Trim()
}
if (-not $giteeToken) {
    Write-Host "❌ 未找到 .gitee_token 文件"
    exit 1
}

# ---------- 定位 APK ----------
$candidates = @(
    "app\release\app-release.apk",
    "app\build\outputs\apk\release\app-release.apk"
)
$APK = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1

if (-not $APK) {
    Write-Host "❌ 找不到 release APK，已尝试:"
    $candidates | ForEach-Object { Write-Host "   $_" }
    Write-Host "请先执行 gradlew assembleRelease"
    exit 1
}

# ---------- 读取版本 ----------
$version = (Select-String -Path "app\build.gradle.kts" -Pattern 'versionName\s*=\s*"([^"]+)"' | ForEach-Object { $_.Matches.Groups[1].Value })
$TAG = "v$version"

Write-Host "========== 版本: $version | 标签: $TAG | APK: $APK =========="

# ---------- 准备 Release Notes ----------
$notes = ""
$argList = $args -join " "
if ($argList -match "-NotesFile\s+(\S+)") {
    $notes = Get-Content -Path $Matches[1] -Raw -Encoding UTF8
} elseif ($argList -match "-Notes\s+(.+)") {
    $notes = $Matches[1]
} else {
    $lastTag = git describe --tags --abbrev=0 2>$null
    if ($lastTag) {
        $notes = (git log "$lastTag..HEAD" --pretty="* %s") -join "`n"
    } else {
        $notes = (git log -1 --pretty=%B | Select-Object -First 1)
    }
    Write-Host "Release Notes (auto-generated):"
    Write-Host $notes
}

# ====================== GITEE ======================
Write-Host "`n========== Gitee =========="

# 推送 tag 到 Gitee
$giteePushUrl = "https://windcloudjet:${giteeToken}@gitee.com/$GITEE_REPO.git"
git push $giteePushUrl $TAG 2>&1 | Out-Null
if ($LASTEXITCODE -eq 0) {
    Write-Host "✅ Gitee: 已推送标签 $TAG"
} else {
    Write-Host "⚠️  Gitee: 推送标签失败，尝试继续..."
}

# 创建 Gitee Release
# 使用 JavaScriptSerializer 而非 ConvertTo-Json，避免中文被转义为 \uXXXX
Add-Type -AssemblyName System.Web.Extensions

$serializer = New-Object System.Web.Script.Serialization.JavaScriptSerializer
$bodyJson = $serializer.Serialize(@{
    tag_name         = $TAG
    name             = "v$version"
    body             = $notes
    target_commitish = "master"
})
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($bodyJson)

$releaseId = $null
try {
    $createResp = Invoke-RestMethod `
        -Uri "https://gitee.com/api/v5/repos/$GITEE_REPO/releases?access_token=$giteeToken" `
        -Method Post `
        -ContentType "application/json; charset=utf-8" `
        -Body $bodyBytes

    $releaseId = $createResp.id
    Write-Host "✅ Gitee: Release 创建成功 (id=$releaseId)"
} catch {
    Write-Host "❌ Gitee: Release 创建失败:"
    Write-Host $_.Exception.Message
    if ($_.ErrorDetails) {
        Write-Host $_.ErrorDetails.Message
    }
    exit 1
}

if ($releaseId) {
    # 上传 APK 作为附件
    $uploadResp = curl.exe -s -X POST `
        "https://gitee.com/api/v5/repos/$GITEE_REPO/releases/$releaseId/attach_files?access_token=$giteeToken" `
        -F "file=@$APK" 2>&1

    $downloadUrl = $null
    try {
        $downloadUrl = ($uploadResp | ConvertFrom-Json).browser_download_url
    } catch { }

    if ($downloadUrl) {
        Write-Host "✅ Gitee: APK 上传成功"
        Write-Host "   下载地址: $downloadUrl"
    } else {
        Write-Host "⚠️  Gitee: APK 上传可能失败，请检查:"
        Write-Host "   $uploadResp"
    }
}

Write-Host "✅ Gitee: 完成 https://gitee.com/$GITEE_REPO/releases"
