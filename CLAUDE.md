# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**IMPORTANT**: Do NOT attempt to build, compile, or run tests locally. The development machine does not have Java/Android SDK installed. Rely on code review and static analysis to verify correctness.

## 技术栈

**构建**: Gradle 8.2.1, AGP 8.2.1, Kotlin 1.9.0, Compose Compiler 1.5.1, Java 17 target, configuration cache enabled.

**平台**: minSdk 24, targetSdk/compileSdk 34, arm64-v8a + armeabi-v7a.

**UI**: Jetpack Compose (Material 3 + Material 2), Navigation Compose 2.8.3, Activity Compose 1.9.3, Lottie Compose 6.7.1, Telephoto 0.6.2 (zoom/pan).

**网络**: OkHttp 4.12.0 (Brotli + DoH), Retrofit 2.11.0 (Gson), Coil 2.6.0 (image loading + WebView proxy).

**数据**: DataStore Preferences 1.1.1, Fastjson2 2.0.51, Jsoup 1.17.2 (HTML parsing).

**其他**: OpenCC 1.2.0 (简繁转换), Reorderable 3.0.0 (拖拽排序), Lifecycle ViewModel Compose 2.8.7.

## Architecture

This is an Android reader app for the yamibo.com (百合会) forum, supporting novel and manga reading. Single-activity Compose app (`MainActivity`) using Navigation Compose for routing.

### Navigation & Pages

Three bottom-tab top-level routes: `FavoritePage`, `BBSPage`, `MinePage`.

Content routes:
- **`ProbingPage`** — Smart router. Loads a URL in a hidden `WebView` (from `WebViewPool`), runs JS to detect content type (novel=1, manga=2, forum=3), then navigates to the appropriate reader. Uses `MangaImagePipeline.handoffPrefetch()` for manga image pre-warming.
- **`ReaderPage`** — Novel reader. Horizontal pager (swipe left/right) or vertical scroll mode. Toolbar with night mode, font settings, translation (simplified/traditional Chinese), chapter drawer, and caching controls.
- **`NativeMangaPage`** — Native manga reader with zoom/pan gestures via Telephoto library.
- **`MangaWebPage`** — WebView-based manga reader (fallback / fast-forward mode).
- **`OtherWebPage`** — Generic WebView page for forum browsing.

### Core ViewModels

- **`ReaderVM`** — Novel reading engine. Fetches forum posts via `NovelApi` (filtered by `authorid`), parses HTML with Jsoup, paginates text content (horizontal or vertical mode) using `TextUtil`, preloads next web-page when nearing end, maintains reading progress in `FavoriteUtil`. Multi-level caching: memory (`CacheUtil`/LruCache) then disk (`LocalCacheUtil`). Supports chapter extraction from first-line-of-post heuristics.
- **`FavoriteVM`** — Favorites management. Fetches favorites by scraping HTML (`FavoriteApi`), supports smart/incremental refresh (stops when no new items found), drag-to-reorder via `Reorderable`, hide/unhide, batch delete with tombstone queue for offline retry.

### Networking (`global/YamiboRetrofit.kt`)

Two OkHttp clients:
- `okHttpClient` — General use, 50MB disk cache, default connection pool.
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

### Threading Rules

- `WebViewPool` and all WebView operations **must** be on the main thread (enforced with `checkMainThread`).
- ViewModels use `viewModelScope` (main-thread default) with `Dispatchers.IO` for network/disk.
- `ReaderVM.loadRequestId` (atomic counter) prevents stale callbacks from outdated loads.

### Release Upload

When the user requests a release upload, first run `git log <lastTag>..HEAD` and `git diff <lastTag>..HEAD` to analyze changes and generate Chinese release notes. Then reply with the upload command for the user to execute themselves (Claude cannot run it directly):

```powershell
powershell -ExecutionPolicy Bypass -File .\upload_release.ps1 -Notes "generated release notes"
```

The script automatically: reads version code → pushes git tag → uploads APK to GitHub Release.
Prerequisite: `gh` CLI installed and authenticated.
