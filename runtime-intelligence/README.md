# Runtime Intelligence

Selected-app Android runtime diagnostics for Android 8+.

## Current implementation
- Installed-app discovery and selected-app scanning.
- Android Usage Access integration for per-app foreground time and last-used timestamp.
- Optional root-assisted process inspection using PID and /proc status fields (RSS, PSS when exposed, data/private and file-backed memory, process state).
- APK integrity scan with SHA-256, size and code path metadata.
- Local JSON diagnostic report export.
- Optional user-configured HTTPS REST report upload.
- Root capability detection with explicit non-root limitations.

## Privacy boundary
The app does not query or collect whole-device RAM statistics. It does not call ActivityManager.MemoryInfo, getMemoryInfo(), totalMem or availMem. Scans are scoped to the application selected by the user. Usage Access is granted by the user in Android Settings. Root inspection is optional and only attempted after the user has configured root access.

## Build
Use JDK 17 and Android SDK 35:
`gradle :app:testDebugUnitTest :app:assembleDebug`

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Limitations and next milestones
Android's app sandbox prevents ordinary apps from reading another app's private process memory. Detailed /proc process inspection therefore requires a rooted device and approved superuser access. The current release provides per-app usage and APK integrity diagnostics without root. Event thresholds, continuous foreground monitoring, Room-backed report history, and AI-generated repair pull requests remain planned milestones; the AI Bug Intelligence workflow is separate and requires an Actions secret.
