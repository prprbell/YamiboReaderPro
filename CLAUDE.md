# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**IMPORTANT**: Do NOT attempt to build, compile, or run tests locally. The development machine does not have Java/Android SDK installed. Rely on code review and static analysis to verify correctness.

**IMPORTANT**: NEVER run `git checkout -- <file>` or any other destructive/irreversible git command without explicit user confirmation. These commands discard uncommitted changes permanently with no recovery. Always show `git diff` first and ask before reverting. Use `git stash` if a reversible alternative is needed.

## 技术栈

**构建**: Gradle 8.2.1, AGP 8.2.1, Kotlin 1.9.0, Compose Compiler 1.5.1, Java 17 target, configuration cache enabled.

**平台**: minSdk 24, targetSdk/compileSdk 34, arm64-v8a + armeabi-v7a.

**UI**: Jetpack Compose (Material 3 + Material 2), Navigation Compose 2.8.3, Activity Compose 1.9.3, Lottie Compose 6.7.1, Telephoto 0.6.2 (zoom/pan).

**网络**: OkHttp 4.12.0 (Brotli + DoH), Retrofit 2.11.0 (Gson), Coil 2.6.0 (image loading + WebView proxy).

**数据**: DataStore Preferences 1.1.1, Fastjson2 2.0.51, Jsoup 1.17.2 (HTML parsing).

**其他**: OpenCC 1.2.0 (简繁转换), Reorderable 3.0.0 (拖拽排序), Lifecycle ViewModel Compose 2.8.7.

## 主题

日间/夜间双主题，通过 `SettingsUtil` 持久化主题偏好。主题 CSS 样式通过 `ThemeCssData` 注入 WebView，样式数据拆分为独立文件管理。

## Architecture

This is an Android reader app for the yamibo.com (百合会) forum, supporting novel and manga reading. Single-activity Compose app (`MainActivity`) using Navigation Compose for routing.

### Navigation & Pages

Three bottom-tab top-level routes: `FavoritePage`, `BBSPage`, `MinePage`.

**External URL handling**: All WebView pages (`BBSPage`, `MinePage`, `MangaWebPage`, `OtherWebPage`) intercept non-yamibo.com `http/https` links in `shouldOverrideUrlLoading` and open them in the system browser. The shared whitelist `BBSGlobalWebViewClient.isYamiboUrl()` covers `bbs.yamibo.com`, `m.yamibo.com`, `www.yamibo.com`, and `yamibo.com` (root). This prevents third-party sites from blocking the main WebView when unreachable on the user's network.

**BBSPage error recovery**: `BBSPageState` drives a state machine. On main-frame error, `handleErrorState()` triggers silent auto-recovery (`requestRecoveryBeforeShowingError()`), which retries once. Only after a 10s timeout without recovery does `showLoadError = true` display the error page. `bestRecoveryUrl()` prioritizes `currentUrl` during error recovery to retry the intended target, not the stale `webView.url`. `BBSGlobalWebViewClient` also runs a 15s main-frame timeout on every `onPageStarted` as a safety net against stalled loads. Error handlers across all pages ignore non-yamibo URLs via `isYamiboUrl()` guard, so external link failures can't trigger the recovery state machine or error UI.

Content routes:
- **`ProbingPage`** — Smart router. Loads a URL in a hidden `WebView` (from `WebViewPool`), runs JS to detect content type (novel=1, manga=2, forum=3), then navigates to the appropriate reader. Uses `MangaImagePipeline.handoffPrefetch()` for manga image pre-warming. **Scope: only used for favorite type detection — must not take on any other responsibility.**
- **`ReaderPage`** — Novel reader. Horizontal pager (swipe left/right) or vertical scroll mode. Toolbar with night mode, font settings, translation (simplified/traditional Chinese), chapter drawer, and caching controls.
- **`NativeMangaPage`** — Native manga reader with zoom/pan gestures via Telephoto library.
- **`MangaWebPage`** — WebView-based manga reader (fallback / fast-forward mode).
- **`OtherWebPage`** — Generic WebView page for forum browsing.
- **`HistoryPage`** — Full-screen browsing history page. Timeline grouping (today/yesterday/week/older), search/filter, multi-select delete. Reads from `HistoryUtil.getHistoryFlow()`.

### Core ViewModels

- **`ReaderVM`** — Novel reading engine. Fetches forum posts via `NovelApi` (filtered by `authorid`), parses HTML with Jsoup, paginates text content (horizontal or vertical mode) using `TextUtil`, preloads next web-page when nearing end, maintains reading progress in `FavoriteUtil`. Multi-level caching: memory (`CacheUtil`/LruCache) then disk (`LocalCacheUtil`). Supports chapter extraction from first-line-of-post heuristics.
- **`FavoriteVM`** — Favorites management. Fetches favorites by scraping HTML (`FavoriteApi`), supports smart/incremental refresh (stops when no new items found), drag-to-reorder via `Reorderable`, hide/unhide, batch delete with tombstone queue for offline retry.

### Networking (`global/YamiboRetrofit.kt`)

Two OkHttp clients:
- `okHttpClient` — General use, 128MB disk cache, default connection pool.
- `threadOkHttpClient` — For forum image loading, no cache, 6 max requests per host, with `RateLimitInterceptor` (100ms) and `ImageCheckerUtil`.

Custom `DynamicDns` races AliDNS and TencentDNS DoH resolvers (1.5s timeout), falls back to system DNS. Supports manual custom DNS URL. All requests get cookie, User-Agent, Accept-Language headers injected via application interceptor. `proxyWebViewResource()` allows OkHttp to proxy WebView resource requests (bypassing WebView's own networking).

### Data Flow

- **Preferences**: `DataStore` (Preferences) for settings. `SettingsUtil` wraps read/write with callbacks.
- **Cookies**: `CookieUtil` reads Android WebView `CookieManager`, exposes via `cookieFlow`. Saved to `DataStore` for persistence.
- **Favorites**: `FavoriteUtil` manages an in-memory list with JSON persistence. Exposes via `getFavoriteFlow()` which `FavoriteVM` collects.
- **Global state**: `GlobalData` object holds app-wide mutable state (cookie, feature flags, settings) as `MutableStateFlow`/`mutableStateOf`.
- **`WebViewPool`**: Object pool (max 3) for WebView instances. Acquire/release pattern. Washes dirty WebViews by loading blank HTML. Auto-replenishes on idle.

### Key Utilities

- `util/reader/` — `TextUtil` (text pagination), `HTMLUtil` (HTML-to-text), `ChineseConvertUtil` (OpenCC), `CacheUtil` (memory LruCache), `LocalCacheUtil` (disk persistence).
- `util/manga/` — `MangaReaderManager`, `MangaImagePipeline` (prefetch), `ZoomPanGestureHandler`, `MangaProber`.
- `util/network/` — `NetworkMonitor`, `NetworkPreWarmer`, `RateLimitInterceptor`, `TtlDnsCache`.
- `util/favorite/` — `FavoriteUtil`, `FavoriteDeleteUtil`, `TombstoneQueueUtil` (offline delete retry).
- `util/history/` — `HistoryUtil`. `LinkedHashMap`-backed browsing history, persisted to DataStore as JSON. Flow-based reads, mutex-guarded writes with debounced save (1.5s). 500-entry cap, URL-normalized dedup. Exposes `isThreadUrl()` to identify forum thread URLs.

### Threading Rules

- `WebViewPool` and all WebView operations **must** be on the main thread (enforced with `checkMainThread`).
- ViewModels use `viewModelScope` (main-thread default) with `Dispatchers.IO` for network/disk.
- `ReaderVM.loadRequestId` (atomic counter) prevents stale callbacks from outdated loads.

### Release Upload

1. Verify `app/build.gradle.kts` `versionName` > latest git tag. If equal, warn to bump.
2. Analyze changes: `git log <lastTag>..HEAD` and `git diff <lastTag>..HEAD`, then write user-facing Chinese release notes. Write `release_notes.txt` (project root) and `release/update.json`.
3. Check `app/release/app-release.apk` exists (warn user to build if missing).
4. Tag and push: `git tag vX.X.X && git push origin vX.X.X`.

```bash
VERSION="1.11.6"
TOKEN=$(echo -e "protocol=https\nhost=github.com" | git credential fill | grep password | cut -d= -f2)
APK="app/release/app-release.apk"

# Create GitHub release (body from release/update.json)
RELEASE_ID=$(curl -s -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  -d @release/update.json \
  https://api.github.com/repos/prprbell/YamiboReaderPro/releases | grep -o '"id": [0-9]*' | head -1 | grep -o '[0-9]*')

# Upload APK to GitHub release (MUST be .apk)
curl -s -X POST \
  -H "Authorization: token $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/vnd.android.package-archive" \
  "https://uploads.github.com/repos/prprbell/YamiboReaderPro/releases/$RELEASE_ID/assets?name=yamibo_v$VERSION.apk" \
  --data-binary "@$APK"

# OSS (in-app update): rename to .zip (Aliyun blocks .apk), upload update.json + .zip
cp "$APK" "release/yamibo_v$VERSION.zip"
```

**OSS upload**: `release/update.json` + `release/yamibo_vX.X.X.zip` to bucket `yamibo-reader-pro-release` (oss-cn-chengdu). The client uses `application/vnd.android.package-archive` MIME so `.zip` extension is fine.

**`update.json` format**:
```json
{
  "tag_name": "v1.11.6",
  "body": "...",
  "apkName": "yamibo_v1.11.6.apk",
  "apkDownloadUrl": "https://yamibo-reader-pro-release.oss-cn-chengdu.aliyuncs.com/yamibo_v1.11.6.zip"
}
```
