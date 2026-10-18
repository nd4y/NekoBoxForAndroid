# Phone verification - 2026-10-18

The ADB control fork is installed as `com.nb4a.plus.debug` beside the original `com.nb4a.plus`. The original application and its data are retained. Further automation uses the fork through `nekoctl`; no UI interaction is needed for the edit cycle.

## Complete configuration

`pull FILE` saves the complete editable `nekobox.full-config` version 1 document. `validate FILE` returns a value-free diff and validates native options without saving or connecting. `push FILE` (alias `import`) atomically imports with revision protection and a recovery checkpoint. Saving does not restart VPN; `vpn restart` is explicit. Full documents include credentials and must remain in private local storage. Normal exports/rendering redact credentials; unknown preferences are hidden by default.

The workspace helper `tools/nekoctl-phone.py` outside this repository pins the known physical phone, verifies its hardware serial, prefers USB and otherwise uses that same paired wireless device. It pins the debug fork and never retries writes. The portable client requires an explicit `--serial`.

## Build and checks

- Android Studio JBR, `ossDebug`, ARM64 APK. Built in a local temporary checkout to avoid Synology reparse-point restrictions.
- 132 JVM tests and 12 Python client tests pass.
- Lint reports 127 existing issues. Issue ID, message and normalized file counters exactly match the previous baseline; no added or removed issues. Consequently the combined Gradle command exits unsuccessfully at lint, although unit tests and APK assembly complete.
- All 647 checked Android build inputs match the canonical repository, including bundled AAR. ARM64 native library hashes match the previous release. Native sources were unchanged and were not rebuilt.
- APK signature and 16 KiB ZIP alignment verified. Exact hashes and source commit are in the accompanying artifact validation manifest.
- Physical phone: Android API 37, ARM64. Full-document acceptance tests cover 320 profiles, read-only validation, preserved existing configuration, live VPN remaining connected during import, stale revision rejection, Unicode/shell characters, default render redaction and reverse checkpoint rollback.
- Explicit stop/start/restart verified. Three actual HTTPS probes verify TLS and HTTP 200 for foreign, Russian and home endpoints. The foreign probe reports FR exit country. These are point-in-time checks of the current network.

The original ADB provider authorization and interrupted cross-database recovery tests were previously run on the disposable emulator. Physical phone tests use the installed fork and temporary synthetic additions, then restore the exact pre-test configuration.

## Migration and limits

A fresh original native backup and original APK were saved in private local storage before migration. Newer native profile parcels required decoding with the original APK classes and re-encoding with fork classes; a blind native import lost 90 profiles and was discarded. Supported configuration fields round-trip correctly. Newer presentation/country/subscription-origin metadata remains recoverable from the original backup. Active proxy-set security exclusions were ported, and XHTTP session aliases from current subscriptions are normalized for the native decoder.

Automatic subscription refresh replaced 148 profile IDs at first startup while retaining 320 profiles and valid references. No claim is made that those IDs stayed identical to the original backup. The native runtime reports `sing-box v1.13.13-plus-47 (latest uTLS)`, matching the bundled core; the app version remains `1.4.2-49`.

Fine, coarse and background location permissions were granted with explicit user approval to preserve home Wi-Fi recognition. VPN authorization was granted using Android's ADB app-op; subsequent lifecycle operations use the API. Always-on VPN is not configured. The running `RU MODE` selector was observed as `Обычная сеть`; native backups do not preserve runtime selector caches, so a prior live cached choice was not proven preserved.

Carrier LTE/whitelist behavior, remote NetBird enrollment and field transitions between networks remain untested. No additional NetBird identity was started. Complete config documents exclude OS permissions, peer identity files, caches, counters and installed native/plugin binaries.

Task-owned temporary phone migration files and the diagnostic port forward are removed after verification; protected local backups and app-private recovery checkpoints remain available. Rollback for API changes uses `checkpoints` and `rollback` in reverse order. The original app and its private native backup remain the migration fallback.
