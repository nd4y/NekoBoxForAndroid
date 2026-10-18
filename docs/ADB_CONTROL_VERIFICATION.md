# ADB Control API acceptance record

Date: 2026-10-18. Branch: `feature/adb-control`. Package: `com.nb4a.plus.debug`, version `1.4.2-49`.

## Result

The API and `nekoctl` support structured profile/group/rule/settings edits, selection, redacted export, plan, revision-checked apply, private recovery checkpoints, rollback and explicit VPN lifecycle commands. API access is disabled by default and restricted to Android shell UID 2000 after local opt-in. The implementation and protocol are documented in [ADB_CONTROL.md](ADB_CONTROL.md).

## Verified

| Check | Result |
| --- | --- |
| JVM tests | 120 passed, 0 failures/errors/skips |
| Host Python client tests | 8 passed, including UTF-8 redirected output and diagnostic-looking user data |
| Android instrumentation | 4 passed: non-shell denial, journal recovery/CAS, native validation, generated block rule |
| Native Android Go test | `TestCheckConfigJSON` passed |
| API acceptance | 15 checks passed, including 128 KiB stdin, partial edits, secret preservation/redaction, concurrent CAS, rollback and invalid-input rejection |
| VPN lifecycle | 5 checks passed: start, edit while connected, dirty revision, restart/stop, original configuration restored |
| Cold provider start | 3 consecutive force-stop/start cycles passed |
| Unicode/shell metacharacters | Exact synthetic profile-name roundtrip and rollback passed |
| Local opt-in | Enabled/disabled through UI; disabled API rejects access; initial off state restored |
| Android lint | 127 existing findings, 0 added/removed, compared by ID/message/source path |

Device: existing `dosette-review-20261018`, serial `emulator-5580`, x86_64 Android API 36. The tested installed base APK SHA-256 exactly matched the built x86_64 APK: `9813c7f86ac568c80a95475b7a33e924249c0102509dc30d623139a5371d4a20`.

The test fixtures were restored, VPN stopped and the task-started emulator shut down. Task test files were removed from the emulator. The physical phone was not modified or used for install/configuration tests. No private subscriptions were needed.

ARM64, x86_64 and universal64 debug APKs are packaged separately. The artifact verifier checks signatures, package identity, ABI set, native AAR/APK hash consistency, 16 KiB APK ZIP alignment and ELF LOAD alignment. `artifacts/adb-control/validation.json` and `SHA256SUMS.txt` in the parent task directory identify the resulting files and source commit.

## Build environment and limits

Builds used Android Studio JBR and the existing Windows Android SDK/NDK. The native core was rebuilt for Android arm64/amd64. Synology Drive changed generated Gradle outputs into cloud reparse files; Gradle then rejected them as non-regular output files. Final JVM/lint checks therefore ran in a local temporary source copy outside Synology. Its 822 copied files were verified by SHA-256; all Android/native build inputs still match. The later host-client-only fixes have their own Python tests. The delivered APK is the original build tested on the emulator.

The combined Gradle verification command exits nonzero because the existing lint findings are treated as errors. This is not a clean lint result. A native reference check also exposed an existing generated per-app block rule pointing to an undeclared `block` outbound; it now uses the core's `reject` action and has regression coverage.

ARM64 was packaged and statically verified, not installed on the physical phone. This task did not retest remote VLESS/NetBird connectivity. Configuration validation never starts a second NetBird identity or native VPN. API v1 does not edit subscription credentials; an already-open UI editor can still save a later change, so reopen it after external edits. Interrupted cross-database commits were simulated in instrumentation; no physical power-loss test was performed. APKs use debug signing and a separate debug package.
