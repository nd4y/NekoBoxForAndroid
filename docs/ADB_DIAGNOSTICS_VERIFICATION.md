# ADB diagnostics and network handover fix

## Confirmed failure

The routing configuration activates a WireGuard endpoint through a URLTest detour. A real Wi-Fi/LTE handover reproduced a native deadlock while the foreground VPN still reported Connected. Java snapshots showed the network callback blocked in `Libcore.updatePlatformNetworkState`; the event journal stopped, and the background process retained cellular state after Wi-Fi returned. DNS then failed also on Wi-Fi.

A scoped native runtime capture showed two goroutines waiting for eight minutes:

1. `NetworkWake` holds the pause manager registration mutex and invokes WireGuard Up/keepalive. It waits for the client bind connection mutex.
2. The WireGuard receive/connect goroutine holds that bind mutex and calls URLTest Touch, which registers a pause ticker and waits for the pause manager mutex.

This is a lock cycle, independent of the number of routing rules. Cached/recovery LOW_MEMORY exits in the original app were insufficient evidence for a VPN memory failure. The original old exception stack was unavailable; the native deadlock above was reproduced in the installed fork with the migrated configuration.

## Changes

The WireGuard pause callback now only publishes the latest combined device/network pause state. A single worker applies Up/Down outside the pause manager mutex. Endpoint shutdown stops the worker, closes the device and joins the worker. The immutable device reference and stopped flag avoid callback/close races. The native fix is committed in sibling sing-box `8296713`, with core-version bump to `v1.13.13-plus-49` in `600fd7eb2c06215454d15a702048bf4e50d2cd97`; the Android source pin is updated.

Additional Android fixes keep native network/Wi-Fi work off the main callback handler, isolate listener exceptions, guard a disappearing interface's MTU, publish the underlying Network with volatile visibility, clear/capture stale underlying handles, compare Network handles as well as interface names, and reset connections after recovery even when full VPN reconnect is disabled. Network callbacks remain serialized.

`nekoctl logs` reads bounded, default-redacted core/previous/event/crash sources with cursor/reset semantics; `--follow` emits JSONL. Events and Java exceptions persist independently of core logging. Event masking preserves JSON number types and row syntax. Core startup preserves the previous tail before the native logger truncates its cache file.

`nekoctl diagnostics FILE` creates a private ZIP with a per-component hash/error manifest, saved/running state, redacted config and generated config, current network metadata, Java and Go stacks, memory, exit history and package/UID-scoped OS diagnostics. Go stack arguments are omitted inside the core, before export. Normal collection does not sample profiles, start another box, join a peer, change routing, clear logs or restart VPN. Native stack collection works while normal networking is blocked. Legacy/dead providers produce a useful partial ZIP without repeated provider timeouts. See ADB_CONTROL.md for commands, limits and privacy.

## Verification and operational limits

Machine-readable results and exact APK/AAR/source hashes are in the accompanying `artifacts/adb-diagnostics` validation manifest. Build uses the repository's Windows native helper, patched Go1.26.7 CLOCK_BOOTTIME, SDK35/NDK27 and Android Studio JBR. The existing routing assets are preserved. A Synology cloud reparse tag made the native helper refuse its previous generated build directory; that verified task-owned directory was preserved under `.tools`, then a fresh build completed without weakening the guard. Android Gradle uses the existing local temporary checkout.

The deterministic Go regression recreates the pause/bind/ticker lock relationship, and a second test checks coalescing and shutdown. Both passed100repetitions, with a build of the actual affected package. A separate Go test verifies bounded runtime JSON and omission of argument values. JVM tests cover listener isolation, disappearing interfaces, recovery with unchanged interface/new Network handle, reset-only recovery, cursor/rotation/UTF-8 and log redaction. Two selected instrumentation tests on the physical phone check real actor survival and persisted Java crash masking. The separate cross-database recovery/security suite remains emulator-only.

Configured AVD startup was unavailable due a retained exited QEMU/AVD lock; cold/read-only attempts also failed. No hypervisor, WSL or container restart was used. Device results below refer to the real ARM64 phone, not an emulator.

A temporary bootstrap-DNS change did not establish an improvement and was rolled back. Temporary verbose logging and an explicit test of the existing manual LTE-whitelist selector were also restored. On the measured LTE network, the Russian HTTPS probe worked in whitelist mode and failed in ordinary mode. This is reported separately from native callback stability; no automatic mode selection or broad routing change is introduced. Initial LTE requests sometimes failed DNS/TLS/timeouts, so a Connected status alone is never counted as proof of traffic availability.

Subscription refresh during testing updated136existing profile values and four subscriptions while retaining IDs/selection. Final preferences and routing rules are compared with the protected initial pull; current subscription updates are retained rather than overwritten with stale backup data. Private full pulls, raw traces and diagnostic ZIPs stay outside the synced workspace. Public artifacts contain only sanitized reports and application binaries/client code.

Temporary phone probe files, diagnostic forwards, the task's profiler-output directory and instrumentation package are removed after final verification. App-private recovery checkpoints and protected host backups remain. Source commits are local; no push or PR was requested. The installed fork remains `com.nb4a.plus.debug` beside the original app.

## Final phone result

- 141 JVM tests,19 Python tests and2selected physical-device instrumentation tests pass.
- Native regression tests pass100repetitions, affected package builds, bounded/argument-free runtime JSON test passes.
- Fixed core49:2full Wi-Fi/LTE/Wi-Fi cycles and3rapid cycles preserve the background process and deliver every observed transport update. No new background exit or network-worker exception; after each Wi-Fi return, foreign/Russian/home HTTPS probes all return200 with verified TLS. Foreign exitFR.
- LTE home HTTPS passes in both measured cycles; foreign/Russian probes still have individual transient DNS/TLS failures. These are retained in the report, not counted as universal connectivity success. The separate manual-whitelist test made the Russian endpoint available; original manual mode was restored.
- Full default-redacted bundle:14components complete, hashes verified; configuration unchanged during collection. Legacy app without provider:partial ZIP retains scoped OS evidence and skips later provider calls after one transport failure.
- Explicit stop joins the WireGuard pause worker; start and restart reconnect with the same saved/running revision.
- Final APK installed bytes match the delivered file. Signature, APK16KiB alignment and both native ELF alignments verified. Source/AAR inputs recorded. Lint126vs127baseline,0added/1removed (guarded MTU API); combined Gradle command exits1at existing lint, while unit tests and assembly succeed.
- User preferences, routing rules, selected profile and location grants remain preserved. Wi-Fi enabled, RU MODE ordinary, VPN Connected with matching saved/running revisions. Task probe/profile files, diagnostic forwards and test package for user0 removed; private host evidence and app recovery checkpoints retained.
