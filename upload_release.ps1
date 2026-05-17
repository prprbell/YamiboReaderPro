$REPO = "prprbell/YamiboReaderPro"
$GITEE_REPO = "windcloudjet/YamiboReaderPro"
$GITEE_TOKEN_FILE = Join-Path $PSScriptRoot ".gitee_token"

# ---------- 检查依赖 ----------
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
    Write-Host "❌ 未安装 GitHub CLI，请先安装: https://cli.github.com/"
    exit 1
}

gh auth status *>$null
if (-not $?) {
    Write-Host "❌ 未登录 GitHub，请先执行: gh auth login"
    exit 1
}

$giteeToken = $null
if (Test-Path $GITEE_TOKEN_FILE) {
    $giteeToken = (Get-Content -Path $GITEE_TOKEN_FILE -Raw).Trim()
}
if (-not $giteeToken) {
    Write-Host "⚠️  未找到 .gitee_token 文件，将跳过 Gitee 上传"
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
    $notes = Get-Content -Path $Matches[1] -Raw
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

# ====================== GITHUB ======================
Write-Host "`n========== GitHub =========="

# 推送 tag
git rev-parse $TAG *>$null
if (-not $?) {
    git tag $TAG
    git push origin $TAG
    Write-Host "✅ GitHub: 已创建并推送标签 $TAG"
} else {
    Write-Host "GitHub: 标签 $TAG 已存在，跳过创建"
}

# 如果 release 已存在则删除重建
gh release view $TAG --repo $REPO *>$null
if ($?) {
    Write-Host "⚠️  GitHub Release $TAG 已存在，删除后重建..."
    gh release delete $TAG --repo $REPO --yes
}

gh release create $TAG `
    --repo $REPO `
    --title "v$version" `
    --notes $notes `
    $APK

Write-Host "✅ GitHub: 上传完成 https://github.com/$REPO/releases/tag/$TAG"

# ====================== GITEE ======================
if ($giteeToken) {
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
    $createBody = @{
        tag_name      = $TAG
        name          = "v$version"
        body          = $notes
        target_commitish = "master"
    } | ConvertTo-Json -Compress

    $createResp = curl.exe -s -X POST "https://gitee.com/api/v5/repos/$GITEE_REPO/releases?access_token=$giteeToken" `
        -H "Content-Type: application/json" `
        -d $createBody 2>&1

    $releaseId = $null
    try {
        $releaseId = ($createResp | ConvertFrom-Json).id
    } catch { }

    if ($releaseId) {
        Write-Host "✅ Gitee: Release 创建成功 (id=$releaseId)"

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
    } else {
        Write-Host "❌ Gitee: Release 创建失败:"
        Write-Host "   $createResp"
    }

    Write-Host "✅ Gitee: 完成 https://gitee.com/$GITEE_REPO/releases"
}

Write-Host "`n========== 全部完成 =========="
