# Android 1.4.2-70 verification

## Build checkpoint (2026-10-22)

- `lintOssDebug`: zero findings, checkAllWarnings/showAll/warningsAsErrors retained.
- `testOssDebugUnitTest`: 159 tests in 34 suites, zero failures/errors/skips.
- `assembleOssDebug` and `assembleOssDebugAndroidTest`: successful.
- build-01 container, JDK 17, Android SDK/build-tools 35, one Gradle worker,
  offline dependencies, arm64-v8a. Combined build: 31m30s.
- 6,947 source input hashes matched the remote build workspace.
- Native v1.13.13-plus-68 and libgojni.so unchanged from accepted version 69.
- Signed APK: package com.nb4a.plus.debug, versionCode 335, versionName 1.4.2-70.
- Same signing certificate as installed 69; zipalign 16 KiB check passed.
- APK SHA-256: `2dcbb8b9a53ee60dac3d7b18ef509601689fcdff7d7e3094226575c2e3fa5342`.

The build-source checkout URL now points at nd4y/sing-box, where the exact pinned
native68 commit f4cd71253d9ce90126dcd78d58dd7428a6e622a0 is published. This shell
metadata change was verified after the Android build and does not alter APK code.
The existing ByeDPI patch covers the accepted six dependency file modifications.
Legacy compiler deprecation warnings are outside the original 131 lint findings.
See LINT_REVIEW.md for 92 fixes and 39 individually scoped exceptions, including
32 dependency recommendations retained with the owner's approval.

## Pixel physical regression checkpoint (2026-10-22)

The main APK was installed through Obtainium and Android's package installer,
not adb install. Installed SHA-256 and signing certificate match the candidate.
The protected preinstall configuration and NetBird identity were unchanged after
installation. Only the separate instrumentation package was installed using ADB.

| Check | Observed result |
| --- | --- |
| Android instrumentation | 4 intent-journal tests and 1 MasterDNS Parcel test passed |
| Startup failures | 2 injected failures recovered, existing TUN reused |
| Stop while retry pending | Remained Off, pending retry did not undo Stop |
| Rapid Stop/Connect | 3 cycles, newest intent retained |
| Actual uncaught Java exception | OS recreated process, diagnostics captured, Connected in 18.41 s |
| VPN process SIGKILL | Autonomous recovery in 33.17 s |
| Main and VPN process SIGKILL | Autonomous recovery in 24.16 s |
| Wi-Fi/LTE | 2 complete round trips; all 4 final phases passed on first attempt |
| Network checks per phase | 5 DNS checks and 3 trusted HTTPS checks through actual VPN route |
| Radio state journal | No Stopped transition during the accepted round trips |
| WireGuard DNS failure | VPN stayed Connected, other RU/foreign HTTPS routes worked |
| WireGuard DNS restored | Tunnel recovered without VPN restart |
| Watchdog safety net | Recovery event and Connected confirmed without vpn.start; 189.42 s after OS service removal |
| UI Connect/Stop and quick-settings tile | Start/Stop passed; temporary tile removed |

The first version of the new Parcel test incorrectly expected generic host/port
fields in MasterDNS's existing protocol-specific serialization. The corrected test
checks domains, resolvers, packet duplication, name and serialized bytes instead.
Only test code changed; the published application APK and storage format did not.
The corrected test APK was rebuilt on build-01; lint again reported zero findings,
and all five device tests passed. All 6,947 source hashes were checked again with
the corrected test source.

## Observations and limits

- The initial LTE attempt reported SSLHandshakeException for the home HTTPS target
  on three samples; RU/foreign HTTPS and DNS worked and VPN stayed Connected. The
  initial probe retained exception type only. An enhanced private diagnostic probe
  subsequently passed on Wi-Fi and LTE, followed by the two successful round trips.
  Follow-up origin logs and scheduled-backup evidence established that the HTTPS
  origin was intentionally stopped during the retained failing samples. This is
  resolved as origin unavailability; see HOME_TLS_VERIFICATION.md for 48 successful
  current-profile checks. Certificate verification was not weakened.
- USB disappeared during the first watchdog observation; that observation was
  inconclusive. The repeated test used wireless ADB after verifying the physical
  phone identity. A different process appeared before Control API polling; the
  watchdog recovery event preceded Connected, with no VPN-start command. The test's
  initial 90-second post-process polling budget was shorter than actual recovery;
  later events and status establish the 189.42-second result, not a bounded SLA.
- During later tests, the selected profile changed from the route-selection profile
  used in the accepted radio tests to a preexisting provider profile. Manual profile
  contents, preferences and routing rules remain identical to the checkpoint. The
  owner confirmed on 2026-10-22 that this selection change was manual and requested
  keeping the current selection. This observation is resolved; no profile rollback
  or device configuration change was made.
- Dashboard rotation was attempted but the device was locked; no rotation result
  is claimed. The Connect button and tile tests were performed while unlocked.
- Temporary charging-screen behavior was restored to its original value. Test DNS
  overrides, failure injections, temporary files and instrumentation package were
  removed. VPN was left Connected with desired intent On; Wi-Fi was enabled.
- Raw logs, private configurations, endpoints and peer identity remain outside Git.

The two blocking observations are resolved: the owner confirmed manual selection
and origin evidence explains the home TLS outage. The documented regression matrix
is accepted within its stated scope; see HOME_TLS_VERIFICATION.md. Dashboard rotation
and inexact watchdog timing limits remain. No application/native change is included
in this follow-up; release promotion is separate from the runtime result.
