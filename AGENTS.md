# Repository Guidelines

## Project Structure & Module Organization

This is a single-module Android project. The app module lives in `app/`, with Kotlin/Java source under `app/src/main/java/org/shirakawatyu/yamibo/novel/` and Android resources under `app/src/main/res/`.

Key areas:
- `ui/page/`, `ui/widget/`, `ui/vm/`, and `ui/state/` contain Compose screens, reusable UI, ViewModels, and state models.
- `util/reader/`, `util/manga/`, `util/favorite/`, and `util/history/` contain domain utilities.
- `repository/`, `bean/`, and `global/` contain persistence, data models, and app-wide state.
- Release artifacts and update metadata are staged in `release/`.

## Build, Test, and Development Commands

Do not build, compile, or run tests locally on this machine. The local environment is not expected to have a working Java/Android SDK. Use static review for verification unless the user explicitly says the environment is ready.

Reference commands for a properly configured Android environment:
- `./gradlew assembleDebug` builds a debug APK.
- `./gradlew test` runs JVM unit tests.
- `./gradlew connectedAndroidTest` runs instrumented tests on a device/emulator.

## Coding Style & Naming Conventions

Follow existing Kotlin/Compose style. Use 4-space indentation, concise functions, and data classes for structured models. Keep package placement consistent with existing boundaries: UI in `ui/*`, storage/network helpers in `util/*` or `repository/*`, and DTO-like classes in `bean/`.

Prefer established project utilities such as `SettingsUtil`, `DataStoreUtil`, `FavoriteUtil`, and reader/manga helper APIs instead of adding parallel mechanisms. For Fastjson2 models, use `@JSONCreator` and `@JSONField` when stable serialized field names matter, especially for Boolean `isXxx` properties.

## Testing Guidelines

There is no visible formal test suite requirement in this repository. For code changes, validate through static analysis and focused code review. When tests are added, place JVM tests under `app/src/test/` and Android tests under `app/src/androidTest/`, using descriptive names such as `ReaderSettingsSerializationTest`.

## Commit & Pull Request Guidelines

Recent history uses short, direct messages in English or Chinese, sometimes with prefixes such as `refactor:` or `revert:`. Prefer clear imperative messages, for example `Fix reader mode settings serialization` or `Optimize favorite refresh logic`.

Pull requests should describe the user-visible change, list affected screens or utilities, mention verification performed, and include screenshots for UI changes. Call out any skipped build/test steps when following the local environment restriction.

## Agent-Specific Instructions

Read `CLAUDE.md` before substantial work; it contains project architecture and release rules. Never discard uncommitted user changes, and never run destructive git commands such as `git checkout -- <file>` or `git reset --hard` without explicit confirmation.
