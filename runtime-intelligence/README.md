# Runtime Intelligence

Android application for transparent, user-authorised runtime and network diagnostics.

## Current diagnostics foundation

- Device RAM dashboard using Android's public `ActivityManager.MemoryInfo` API.
- This app's own process memory: Java heap, native heap allocation, and process PSS.
- On-demand, last-24-hour aggregate network byte counts for launcher-visible apps using Android's `NetworkStatsManager`.
- Per-app network statistics require the user to explicitly grant Usage Access in Android Settings.
- Minimum Android version: API 26 (Android 8.0); target SDK: API 35.

## Important Android security boundaries

This app does not read another app's private process memory, TLS session keys, passwords, tokens, or private message contents. Android's application sandbox prevents ordinary third-party apps from doing that.

The current per-app network view reports aggregate upload/download bytes only. It does not identify each remote IP, inspect packet payloads, or decrypt HTTPS. Coverage and UID attribution vary by Android version, device vendor, carrier, and network type.

## Planned network intelligence milestones

1. Add a user-started, persistent-notification VPN capture mode with explicit Android VPN consent. Packet forwarding must be implemented and tested so monitoring does not break device connectivity.
2. Capture packet metadata such as IP endpoints, ports, protocol, packet sizes, timestamps, and DNS/TLS metadata where observable.
3. Add an app-owned debug SDK/sample app that records sanitised request/response details before encryption and after decryption. Support test-only TLS session-key logging for controlled test builds.
4. Add optional AI analysis for sanitised metadata and authorised debug payloads. Never upload raw captures or credentials by default; require explicit opt-in and redact secrets.
5. Add local memory diagnostics and leak tracking for this app and explicitly instrumented test apps. Do not attempt cross-app private-memory inspection.

## Build locally

Open this directory in Android Studio with JDK 17 and Android SDK 35 installed, or run:

```bash
gradle :app:assembleDebug
```

The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`

## Weekly GitHub Actions build

The workflow at the repository root, `.github/workflows/weekly-apk.yml`, runs every Monday at 03:17 UTC (08:47 India time). It counts open issues carrying the `bug` label. If there are 10 or more, it runs unit-test and debug-APK build tasks and stores the APK as a 30-day workflow artifact.

A manual workflow dispatch and pushes affecting `runtime-intelligence/**` also run the build. This automation builds the code; it does not automatically diagnose or repair bugs. AI-assisted repair, test validation, and pull-request creation are separate work. Merges and releases should remain subject to maintainer approval.

## Security and privacy

- Network monitoring must be visible and user-controlled.
- Store captures locally by default.
- Require explicit opt-in before sending diagnostic data to an external AI service.
- Redact authentication headers, cookies, tokens, personal identifiers, and message content.
- Do not add credentials, API keys, or signing keys to source control.
