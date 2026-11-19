# Runtime Intelligence

Android application starter for device-level runtime diagnostics.

## Current implementation

- Device RAM dashboard using Android's public `ActivityManager.MemoryInfo` API.
- Manual refresh of total, available, estimated used RAM, and low-memory state.
- Minimum Android version: API 26 (Android 8.0).
- Target SDK: API 35.

This first milestone reports device-level memory statistics only. It does **not** inspect another app's private heap, bypass Android sandbox protections, or run a continuous background monitor.

## Build locally

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, or run:

```bash
gradle :app:assembleDebug
```

The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

## Weekly GitHub Actions build

The workflow at the repository root, `.github/workflows/weekly-apk.yml`, runs every Monday at 03:17 UTC (08:47 India time). It counts open issues carrying the `bug` label. If there are 10 or more, it runs unit-test and debug-APK build tasks and stores the APK as a 30-day workflow artifact.

A manual workflow dispatch also runs the build. This automation builds the code; it does not automatically diagnose or repair bugs. AI-assisted repair, test validation, and pull-request creation are separate work still to be implemented. Merges and releases should remain subject to maintainer approval.

## Planned milestones

1. Process statistics for a user-selected app using Android-authorized usage data.
2. Runtime event thresholds and structured diagnostic reports.
3. Optional HTTPS upload to a user-configured REST endpoint.
4. AI Bug Intelligence that proposes tested fixes through pull requests.
5. Separate stable and LTS release branches after those branches are created and validated.

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

The engine posts an analysis comment when it cannot propose a safe patch. If it proposes a patch, it restricts changes to existing files under this project, runs the unit-test task and debug APK build, then opens a **draft pull request**. It never merges automatically. Review every generated change before approval.

<!-- build-trigger: 2026-11-19 -->
