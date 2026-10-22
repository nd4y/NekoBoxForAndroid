# Home HTTPS regression verification - Android 1.4.2-70

## Root cause of the previously open observation

The original home TLS failures occurred while the test HTTPS origin was deliberately
stopped by its scheduled consistent-backup exporter. Independent origin service
logs, container start/finish times, the backup schedule's last execution and the
exporter's explicit service stop/start commands establish the outage. Two retained
time-stamped failed samples fall entirely inside the approximately 125-second
HTTPS shutdown window. The VPN remained Connected in those samples. This closes
the previously unclassified home TLS observation as origin unavailability.

The service was restored by the existing backup workflow. No VPN/profile change,
certificate-trust workaround, application patch or origin/backup configuration
change was required. Private timestamps, addresses, schedules and raw logs remain
outside this repository. A deliberate origin shutdown still makes that resource
unavailable until its service resumes; a running VPN cannot provide the stopped
HTTPS service.

## Current-profile verification

The owner's current selected profile was captured before testing and asserted
unchanged during each test. It was not replaced by the route-selection profile
used in earlier tests. Android 1.4.2-70 and native core68 remain unchanged.

| Phase | Home HTTPS requests | Result |
| --- | ---: | --- |
| Initial Wi-Fi | 12 | All trusted TLS / HTTP 200 through VPN |
| LTE | 12 | All trusted TLS / HTTP 200 through VPN |
| LTE after main + VPN process SIGKILL | 12 | All trusted TLS / HTTP 200 through VPN |
| Final Wi-Fi | 12 | All trusted TLS / HTTP 200 through VPN |

The process crash recovered autonomously in 25.22 seconds without changing the
owner's selected profile. The 48 HTTPS requests presented the same valid leaf
certificate. Both LTE phases were confirmed by native network snapshots and by
Wi-Fi-disabled state before/after device-side sampling. A short-lived foreground
shell trap restored Wi-Fi on exit, including interruption; no recurring task was
created. System certificate and hostname validation stayed enabled.

The original three-target probe was also run after the cold LTE handover: home
and RU HTTPS passed, while its foreign control reported a connection-closed TLS
failure on the owner's current profile. That separate control result is not a
home TLS failure and is not a claim that this profile supports every destination
under restricted LTE. The profile was preserved as requested.

## Diagnostic improvement and limits

The original probe retained only an exception class, which was insufficient to
classify the failure. `tools/probes/HomeTlsProbe.java` now provides a reproducible
on-demand probe with full TLS causes, certificate and socket metadata. It was built
in a bounded Linux build container and exercised on the real phone. It is a test
utility, not a background monitor or a component added to the application APK.

This resolves the home TLS blocker in LINT70_VERIFICATION.md. It does not claim
uninterrupted availability during scheduled origin maintenance or zero transient
errors on arbitrary destinations. Earlier dashboard-rotation and inexact watchdog
latency limits remain as documented. Publication/stable-release promotion is a
separate action from this bounded runtime verification.
