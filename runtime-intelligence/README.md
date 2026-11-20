# Runtime Intelligence

Android 8+ local diagnostics and authorised app-security research companion.

## Sandbox & Debug Lab — current implementation

The Option A foundation is designed for a normal installable Android app and uses user-approved Android access rather than attempting to bypass the OS sandbox.

### Implemented in this branch

- **APK static inspection:** import an APK using Android's document picker. The app does not install or execute it. It reports package/version, requested permissions, exported component counts, bounded DEX/config string indicators, URL hostnames, IPv4-like strings and redacted possible embedded-secret pattern counts.
- **Installed-app inventory:** uses the declared `QUERY_ALL_PACKAGES` visibility to list user-installed packages visible to the current Android user/profile. It reports package/version, debuggable/test-only flags, dangerous permission declarations and grant state, plus exported component counts.
- **Device privilege audit:** reports Usage Access, Device Owner, Profile Owner, managed Work Profile state and battery-optimisation state. It explains that Device Owner/Profile Owner status requires managed-device provisioning and cannot be self-granted by an ordinary app.
- **Device and process memory dashboard:** reports device RAM and this app's own Java/native heap and PSS.
- **Network diagnostics:** reports active transport, interface and DNS server addresses; last-24-hour per-UID aggregate upload/download byte counters for launcher-visible apps when Usage Access is granted.
- **Settings shortcuts:** provides direct routes to Usage Access and this app's Android App Info page.
- **Local-first operation:** APK and package metadata analysis runs on-device. No automatic upload of reports or secrets.

### Permissions and special access

- `QUERY_ALL_PACKAGES`: broad installed-package visibility. This is sensitive and Google Play restricts its use to qualifying core use cases. Sideloading avoids Play review but does not remove Android's privacy boundaries.
- `PACKAGE_USAGE_STATS`: special Usage Access granted by the user in Android Settings. It is used for aggregate network counters.
- `ACCESS_NETWORK_STATE`: reads the current network transport and link metadata.
- No root, Accessibility Service, notification listener, Device Admin receiver, VPN interception or cross-app memory access is enabled in this build. Those capabilities are not silently enabled.

## Important distinction: sandbox vs managed profile

This APK is a diagnostics controller, not a virtual Android machine. Android already isolates apps with per-app UIDs, SELinux and kernel controls. A real Work Profile is a separate Android user/profile created through Android Enterprise provisioning by the user or an authorised organisation. An app cannot create itself as Profile Owner or Device Owner after an ordinary installation. When a Work Profile is provisioned, the app must be installed separately inside that profile to inspect that profile's app inventory; it still cannot read other apps' private heaps or keys.

## Network visibility boundaries

The app currently shows Android's aggregate per-app byte counters and the device's own active-network/DNS configuration. It does not yet capture per-app remote IP connections or packet payloads. A future VPN mode must be implemented as a complete, tested packet-forwarding tunnel with a visible foreground notification and Android's VPN consent flow. A packet reader that does not forward traffic is intentionally not included because it would break connectivity.

HTTPS content and session keys remain inaccessible for unrelated apps. For apps we own or are authorised to test, a debug SDK can record sanitised request/response details at the app's own networking layer, before encryption and after decryption. Test keys should be synthetic and restricted to debug builds.

## Compatibility

- Minimum SDK: API 26 (Android 8.0).
- Compile / target SDK: API 35.
- The minimum SDK allows installation on Android 8 and later, but each feature depends on APIs, vendor behaviour and user-granted settings. This branch has not yet been build-verified or device-tested.
- Android 16/17 support should be verified on physical devices and matching emulator images; the app's minSdk does not by itself prove every feature works on every release.

## Build

From the `runtime-intelligence` directory:

```bash
gradle :app:testDebugUnitTest :app:assembleDebug
```

Debug APK output:

`app/build/outputs/apk/debug/app-debug.apk`

## Roadmap

1. Build and test this branch on API 26, 28, 29, 30, 31, 33, 34, 35, and current Android emulator images.
2. Add a dedicated managed-profile provisioning guide and profile-aware inventory checks; do not attempt to self-assign enterprise ownership.
3. Implement a complete opt-in VPN tunnel only after packet forwarding, IPv4/IPv6, DNS, reconnect, battery and leak tests pass. Show IP/port/protocol/byte/time metadata; do not decrypt third-party HTTPS.
4. Add an authorised sample app and debug SDK with synthetic test credentials, structured network logs, memory profiling and heap/leak diagnostics for that app's own process.
5. Add local report export and optional AI analysis of redacted APK findings and metadata. External upload must be explicit and secret-redacted.

## Security and privacy

- Keep collection visible and user-controlled.
- Store diagnostic reports locally by default.
- Never collect passwords, tokens, cookies, private keys or private message contents from unrelated apps.
- Do not commit API keys, signing keys or credentials.
- Do not claim a vulnerability without reproducible evidence and an affected-version/patch assessment.

## GitHub Actions

The root workflow `.github/workflows/weekly-apk.yml` builds and runs unit tests on relevant pushes and pull requests. Scheduled builds are conditional on the repository's open bug issue count. A successful run uploads a debug APK artifact for 30 days. A workflow run and artifact must be confirmed before sharing an APK link.
