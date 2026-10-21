# Persistent recovery acceptance

2026-10-22. Android app `1.4.2-69`, ARM64 native core
`v1.13.13-plus-68`, Go 1.26.7 with the existing CLOCK_BOOTTIME patch.
Native commit: `f4cd71253d9ce90126dcd78d58dd7428a6e622a0`.
No provider configuration or private device evidence belongs in this repository.

## Build and automated checks

Builds ran in isolated Linux containers with two CPUs, one Gradle worker
and bounded memory. Existing shared workloads were preserved. Only the Pixel's
ARM64 ABI was packaged. Local signing reused the installed debug certificate;
the keystore remained on the workstation.

| Check | Result |
| --- | --- |
| JVM tests | 157 passed, 33 suites, no failures/errors/skips |
| Native race regressions | 98 passed, no failed/skipped tests |
| Atomic intent journal on Pixel | 4 passed |
| Transferred source verification | 6,943 files matched |
| APK signing certificate | Matches the previously installed app |
| Installed APK readback | Matches the signed artifact |
| Native library in APK | Matches the rebuilt AAR |
| ELF LOAD segments and APK ZIP | 16 KiB alignment verified |

Native regression packages covered libcore, WireGuard transport, group selection,
DNS transport and XHTTP. The protocol/wireguard package has no tests itself;
real two-peer tunnel tests run in libcore. The SERVFAIL fixture was also run
against the previous implementation and reproduced global core startup failure.

The final signed APK SHA-256 is:
`cb7f2ea58bb425eed6b28032b24a0c60524e31904f375ee2f443d57abab1a714`.

## Physical Pixel checks

- Button and Quick Settings tile both start and stop the VPN and persist the
  corresponding intent. Existing tiles were preserved; any tile added for
  testing was removed afterward.
- Two injected startup failures recovered automatically, retaining and reusing
  the TUN descriptor twice. No intermediate `Stopped` service event occurred
  during recovery. Explicit Stop during a pending retry prevented resurrection.
  On app69, explicit Stop also remained off for more than 70 seconds, beyond
  the watchdog's nominal period; subsequent Connect passed a fresh Wi-Fi probe.
- Temporary SERVFAIL for the WireGuard peer left the VPN and independent
  Russian/foreign HTTPS paths working. Restoring the test resolver recovered an
  actual HTTPS request over that WireGuard outbound without restarting the VPN.
  The temporary resolver and profile override were removed afterward.
- Three full Wi-Fi -> cellular -> Wi-Fi cycles passed on native core 68,
  including a final cycle on app69. Each
  phase used five DNS cases and real Russian, foreign and home HTTPS requests
  through the TUN, with platform certificate validation and a foreign exit.
  The configured profile and manual Russian traffic mode were preserved.
- On app69, terminating the native process recovered in about 17 seconds;
  terminating both app processes recovered in about 24 seconds. No Control API
  call was made while waiting for the replacement process.
- A separate safety-net test removed the started-service record while preserving
  desired On, then killed the process. The alarm receiver recovered the VPN in
  about 105 seconds, with a recorded watchdog recovery event and no external
  start command during the wait.

The first sticky-only crash test on app68 did not restart within 90 seconds,
despite `startCommandResult=START_STICKY` and a saved On intent. That failure
motivated the independent alarm safety net. Initial cold-start probes also
observed transient provider/DNS unavailability; these observations were retained
privately rather than counted as passing checks. Current acceptance is a bounded
test, not a guarantee that a provider or Android will always be available.
All manual preferences, rules and manually configured routing profiles matched
the original checkpoint after testing. Automatic provider subscription refreshes
were preserved instead of rolling them back to the older backup.

## Lint and limits

`lintOssDebug` was run and is not green: 131 findings with this repository's
warnings-as-errors policy. No findings target the new journal, retry-policy,
TUN-policy, watchdog or fault-injection classes. Three new `Job.cancel(null)`
calls receive the existing `MemberExtensionConflict` warning because the file
imports coroutine extensions with a wildcard. These calls intentionally use
the Job member; supplying its argument explicitly did not silence that checker.
Other reported classes include existing notification permission checks,
API-level guards, receiver flags, translations and deprecations. Build/test
success must not be presented as a clean lint result.

Alarms are inexact and Android may defer them. Process death necessarily closes
its descriptors; recovery is not a promise of zero interruption. No system
Always-on/lockdown, notification/location permission or battery exemption setting
was enabled by this change. See [Android's VPN lifecycle guidance](https://developer.android.com/develop/connectivity/vpn)
and [alarm scheduling guidance](https://developer.android.com/develop/background-work/services/alarms/schedule).

Original APK, app data, configuration and identity comparisons are retained only
in the protected local audit directory. Rollback requires the original signing
certificate and must preserve app data; do not uninstall the configured app.
