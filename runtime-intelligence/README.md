# Runtime Intelligence

Android app for the LoganTech Runtime Intelligence project.

## Current app behavior

- Preserves the original Runtime Intelligence dashboard layout: title, status area, statistics panel, and refresh control.
- The app does **not** collect or calculate statistics about its own device. In particular, it does not query Android `ActivityManager`, `MemoryInfo`, total RAM, available RAM, or used RAM.
- The refresh control only refreshes the static privacy/status message; it does not read device statistics.
- No background device-statistics polling or telemetry collection is enabled.
- Minimum Android version: API 26 (Android 8.0).
- Target SDK: API 35.

Only device-statistics collection was removed. The dashboard structure and unrelated project files are retained.

## Build and reverse-engineering toolchain

This project has Kotlin source, so the reliable rebuild path is the Android Gradle Plugin with the Android D8 compiler (and R8 where applicable). It is preferable to rebuilding source code by decoding an APK to Smali and reassembling it.

The CI pipeline uses:

- **Gradle + Android D8** to compile the Kotlin app and produce DEX/APK output.
- **JADX 1.5.7** to decompile the generated APK back to readable Java for a post-build inspection that confirms device RAM collection code is absent.
- **Google's maintained smali/dexlib2** for structured DEX inspection or binary-level edits only when working with an APK that has no usable source code.
- **Apktool** when Android resources or the manifest need to be decoded/rebuilt; it is not used as the primary compiler for this source-based project.

JADX is a decompiler, not a lossless source reconstruction tool. The maintained Google smali fork provides DEX assembler/disassembler libraries; binary edits should be made through dexlib2 APIs rather than fragile text substitutions in Smali.

## Build locally

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, or run:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

## Weekly GitHub Actions build

The workflow at the repository root, `.github/workflows/weekly-apk.yml`, runs every Monday at 03:17 UTC (08:47 India time). It counts open issues carrying the `bug` label. If there are 10 or more, it runs unit tests, builds the debug APK, decompiles the APK with JADX, and verifies that device RAM collection code is absent. A manual workflow dispatch also runs the build.

The workflow uploads the APK as a 30-day artifact. This automation builds and verifies the code; it does not automatically diagnose or repair bugs. AI-assisted repair, test validation, and pull-request creation are separate. Merges and releases should remain subject to maintainer approval.

## Security and privacy

Android restricts access to other apps' private process memory. This project will use public Android APIs and explicitly authorized diagnostic data. Do not add credentials, API keys, or signing keys to source control.

## AI Bug Intelligence setup

The repository-level workflow `.github/workflows/ai-bug-intelligence.yml` can analyze an existing issue labelled `bug` when a maintainer also applies the `ai-repair` label, or when manually dispatched with an issue number.

Before using it, add an Actions repository secret named `AI_API_KEY` containing an OpenAI API key:
1. Open the repository Settings.
2. Select Secrets and variables, then Actions.
3. Choose New repository secret.
4. Set the name to `AI_API_KEY` and paste the API key as its value.

Optionally set the repository Actions variable `AI_MODEL`; the default is `gpt-4.1-mini`.

The engine posts an analysis comment when it cannot propose a safe patch. If it proposes a patch, it restricts changes to existing files under this project, runs the unit-test task and debug APK build, then opens a draft pull request. It never merges automatically. Review every generated change before approval.
