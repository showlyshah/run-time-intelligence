# Runtime Intelligence

Android application for transparent, user-authorised runtime and network diagnostics.

## App sandbox lab — initial implementation

The app now includes a user-selected APK static-inspection workflow. It reads the APK as a file and does not install or execute it. The initial report includes:

- Package name and version, requested permissions, and exported component counts.
- Bounded scanning of DEX/configuration strings for URL hostnames and IPv4-like strings.
- Counts of possible embedded-secret patterns, with candidate values deliberately hidden.
- Explicit limitations and false-positive warnings.

This is not an Android virtual machine or a second app runtime. A normal third-party Android app cannot transparently host arbitrary apps inside a separate Android OS sandbox or read their private runtime heap. True app virtualization requires a supported managed/work profile, a dedicated virtual-device environment, or a custom OS/virtualization framework; compatibility and isolation guarantees vary. The safe next step is to build an emulator-based lab and a companion test app that uses synthetic keys and opt-in debug instrumentation.

The APK scanner does not recover runtime keys, decrypt HTTPS, or prove that a discovered hostname/IP is contacted. Runtime IP metadata collection remains a separate, opt-in VPN feature requiring Android's VPN consent dialog. Plaintext request/response inspection is limited to test apps or builds instrumented by their owner.

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


## Sandbox analysis roadmap

1. **Static APK inspection (implemented foundation):** user-selected APK, manifest permissions/components, DEX/configuration string indicators, hostnames, IP-like strings, and redacted potential-secret counts.
2. **Isolated execution lab:** use Android Emulator images for API 26–35 and 36/37 where available, with two deliberately authored test apps. One app exposes synthetic test data; the other verifies that the data is not accessible across the app boundary.
3. **Network metadata mode:** add a visible, user-started VPN capture service. Capture endpoint IPs, ports, protocol, timestamps, packet sizes, DNS and TLS handshake metadata where observable. Do not claim per-app attribution unless verified on the target Android release/device.
4. **Authorised debug inspection:** provide a sample SDK for apps we own/control. It can record sanitised request/response metadata before encryption and after decryption, and test-only key material only when explicitly enabled in a controlled build.
5. **AI report:** analyse static findings and sanitised network metadata locally or after explicit opt-in. Never upload credentials, cookies, tokens, raw private traffic, or extracted secrets by default.

The app must not attempt to bypass Android's sandbox or collect private keys, tokens, passwords, or message contents from unrelated apps.
