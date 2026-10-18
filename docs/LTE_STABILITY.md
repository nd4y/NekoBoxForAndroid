# Restricted LTE: DNS bootstrap and URLTest selection

The physical phone uses the manual `LTE - белые списки` mode. Profile74567
`FastCon LTE NonRu` carries foreign payload traffic; profile74195,
renamed from `RU WL Balancer` to `FastCon LTE RU`, carries Russian traffic
and is the shared outer hop of the home WireGuard endpoint27. The saved
chain75882 `[74195,27]` remains available.
The normal direct Russian mode remains a manual choice. No reachability-based
mode automation is introduced.

## Confirmed failures

The original bootstrap depended on direct external DoH or Android's local
resolver. The restricted carrier closed direct TLS connections and local
resolution of the home peer timed out. The required WireGuard endpoint's
startup error stopped the whole VPN. A separate modern-format DoH server
without `detour` used a direct dialer, unlike the legacy default-outbound
behavior; selecting a proxy did not make that DNS transport use it.

After fixing those dependencies, a real cold start still failed: URLTest
used its first member before any successful health result. An unavailable
first member exhausted the required peer's DNS deadline. A successful warm
request or a Connected state was insufficient to detect this failure.

Native stacks after a real handover also showed DoH waiting for a shared
HTTP/2 dial and TLS reading an old XHTTP stream for minutes. XHTTP reset only
closed idle HTTP connections; active logical streams and detached requests
survived the old network. A connection closed before response headers could
also leave its HTTP request alive. These failures were reproduced separately
from callback/lock stability.

## Configuration correction

An IP-addressed DoH bootstrap uses `FastCon LTE RU`. The current RU
members have numeric server addresses, so that bootstrap does not depend on
resolving their names. The home peer explicitly uses this bootstrap resolver.
The existing private public-DNS service has an explicit RU detour and resolves
its own hostname via the bootstrap. Its NonRU path failed on restricted LTE;
foreign payload traffic still uses the NonRu profile. A temporary exact
`.invalid` dependency rule was removed after that experiment; the final
configuration retains eleven routing rules (only home-away rules102/103 now point directly to
endpoint27, whose explicit RU detour supplies the first hop). The RU pool uses a
Russian HTTPS health target instead of Google, so its selection tests the
requested Russian-internet path. The existing private DoH URL and tunnel credentials remain
private; no provider credential is included in this document.

A temporary selector tested all seven existing RU subscription members with
unique home-zone DNS queries through WireGuard. Two members passed in that
measurement; a fast HTTPS member failed the actual tunnel test. Generic UDP/53
health also proved insufficient: a member can pass that query yet not carry the
home WireGuard destination. A later subscription refresh changed server addresses;
those legitimate updates were preserved and the core was restarted on the new
revision before acceptance.

The final RU pool explicitly uses the two existing members74540 and74548 which
passed the real home-tunnel tests. All seven subscription profiles remain intact
in group24, but only those two participate automatically in this pool. The first
attempt to narrow the pool had used the old Google health target and failed HTTP
bootstrap; with the Russian HTTPS target the two-member pool passes cold LTE.
Removing the now-unused temporary `.invalid` rule also passes full320-profile
validation, retaining the original eleven routing rules.

The pool configures `udp_url: udp://77.88.8.8:53?name=example.com` through its
existing custom outbound JSON. HTTP and real UDP DNS probes maintain independent
histories and choose TCP/UDP members independently. The UDP probe verifies generic
UDP support; it does not by itself certify every WireGuard/NetBird destination.
The saved home chain continues to refer to RU pool74195. Active home-away
rules use endpoint27 with an explicit detour to the original RU pool. This
avoids creating duplicate RU-1 transports and independent health scans for the
same provider connections; render confirms one shared RU pool. The temporary
manual selector is removed. Subscription changes or removal of both selected members
require rechecking this explicit membership.

Home split DNS remains direct on the configured home SSID and uses WireGuard
away from home. The home WireGuard payload itself retains its RU outer hop.
There is no enrolled NetBird profile in the phone configuration: its chain
cannot be field-tested without a real enrollment and peer.

## Core correction

`protocol/group/urltest_selection.go` waits for the first confirmed healthy
member, without waiting for slower failed siblings or dialing an unverified
first entry. Concurrent callers share a scan. Caller cancellation and core
shutdown unblock waiting callers. On a network reset, URLTest invalidates
the previous network's latency/selection and cancels its probes. Generation
checks prevent old results from repopulating the new network's history.
Each scan also owns a generation token. A slow canceled scan cannot delay
new-network probes or clear the current scan's completion state; new-network
selection is tested while the old transport deliberately ignores cancellation.
Late failures from an old dial or a closed box cannot erase health learned
by the current network/box from shared storage. Canceled callers also preserve
member health. Selection fields are protected by a mutex. Configured probe URLs, latency
tolerance and manual routing choices remain in use; there is no direct
fallback.

The first member actually used is recorded before the full scan completes.
If the completed scan chooses a better member, internal UDP binds connected
to the earlier member are interrupted and can reconnect to the new choice.
TCP and UDP have separate interruption groups: a TCP choice does not close a
healthy WireGuard UDP connection. `nowUDP` in the existing local Clash API
reports the actual UDP member. Without `udp_url`, the legacy shared health
policy remains available.

XHTTP now owns its pending dials and active logical streams. Reset cancels
pending dials, closes every active stream and rejects late results from the
old generation. New dials wait until the reset finishes. Closing a download
cancels its HTTP request even before headers arrive, and cancellation happens
before closing an HTTP/2 body. Member transports reset before groups launch
new-network health scans. DoH creates fresh HTTP/1.1 and HTTP/2 pools and ALPN
fallback state; stale timeouts do not reset a newer pool. TLS handshakes have
a bounded deadline while retaining normal certificate verification.

A completed health scan can also replace a member and interrupt an internal
DoH connection. The first real LTE handover exposed that case: two DNS
requests timed out despite a later successful HTTPS request. DoH now retries
an idempotent DNS POST once after a connection interruption or internal
deadline, with fresh pools and the original caller deadline. Caller cancellation
and certificate failures are not retried; persistent failure stops after two
attempts. A regression test failed before this fix and passes afterward.

The focused regression set covers first-entry failure, a slow sibling,
network-dependent member changes, cancellation/shutdown, all members down,
concurrent selection/reset and replacement of an early internal bind.
They also cover TCP-only/UDP-only members, preserving UDP on a TCP update,
DNS response validation, rejection of bootstrap-dependent probe URLs, and
cancellation of a blocked UDP read.
Additional XHTTP/TLS/DoH tests cover active/pending/late streams, closure before
response headers, HTTP/2 cancellation order, fresh pools, bounded handshakes
and member-before-group reset order. Focused regressions pass100repetitions;
the seven affected packages pass20repetitions with the Linux race detector.
Their full regular suites pass and the actual packages build. Real HTTP/1.1
and trusted-certificate HTTP/2 stream tests run against the Android application
module versions: established streams survive dial-context cancellation, reset
unblocks reads, and subsequent dials work (100repetitions, race20).

## First WireGuard packet after idle

Physical logs showed a healthy WireGuard handshake and incoming keepalives,
then a failed first outgoing packet after81seconds. XHTTP had closed its idle
upload while its download continued receiving. ClientBind closed that stale
connection but discarded the encrypted datagram, causing the first home DNS
request to time out; the next request succeeded. ClientBind now reconnects and
resends that WireGuard datagram once, preserving reserved bytes and offsets.
WireGuard replay protection handles an ambiguous duplicate; a persistent error
still propagates after two writes. Its current socket is always accessed under
the connection mutex, removing the concurrent-reconnect race reproduced before
the fix. Dedicated first-packet, bounded-failure and concurrent-reconnect tests
cover this behavior.

## HTTP/2 retry panic and native crash evidence

Android recorded a native Go panic in APK58 after3386seconds of process uptime.
The exact matching ELF and tombstone contain an HTTP/2 `getClientConn` return
address immediately after `shouldRetryDial`. The original Go panic text was
lost before collection, so the identity of that historical failure remains
unproven. It must not be declared resolved merely from a Connected state.

The local HTTP/2 fork has the unsafe context-interface comparison reported in
[Go issue70535](https://github.com/golang/go/issues/70535). A new regression
reproduces its panic with valid contexts containing slices, including nested
interface wrappers. Retry decisions now check the requesting context's
cancellation instead of comparing context values. Canceled callers stop,
another live caller can retry a canceled shared dial, and permanent network
errors do not gain a retry. The eight-case regression passes100repetitions;
Linux race checks pass20repetitions, and cancellation/retry/dial tests pass.

The application also installs `debug.SetCrashOutput` for each Go runtime in
separate private BG/main files. A normal restart preserves the last crash;
turning off core logs or truncating the cache log does not remove it. The
ADB API and `nekoctl logs` expose `crash-native-bg` and `crash-native-main`.
Native reads start at the panic, then continue through bounded cursor reads.
Diagnostic bundles collect the sources advertised by the APK, including both
native sources on new versions. Subprocess tests verify original panic/frame
capture, descriptor ownership, rotation across crashes and normal restarts,
and separation of main/background evidence. These pass on Windows (five
repetitions), Linux with race detection (three), and the physical Android
device (three), without deliberately crashing the installed VPN application.
Arbitrary C/JNI faults and OS kills still require Android exit/tombstone data.

## Android Doze and overlay connectivity

A forced deep-idle test on APK60 reproduced a separate failure: after85seconds,
foreign/Russian DNS and HTTPS still worked, while first home DNS and home HTTPS
failed. Leaving Doze immediately restored home DNS. Saved/running revisions and
kernel process birth were unchanged. The Android receiver unconditionally called
core.sleep on idle, intentionally pausing WireGuard even for a battery-exempt VPN.
The original installed NekoBox was battery-exempt; the parallel debug fork was not.

APK61 keeps native60 and corrects only Android policy: an exempt VPN stays awake
in Doze, a non-exempt VPN retains sleep/wake behavior, exemption changes while idle
resume or suspend the core, and idle exit retains user-configured wake recovery.
Four JVM regressions cover these branches. Diagnostics expose the system power
state and exemption. The fork was given the same system exemption as the original
application. This system permission is separate from portable configuration.

## Physical acceptance

Native60 passed three full Wi-Fi/LTE cycles, three rapid cycles, and twelve cycles
with two concurrent HTTPS workers. During handover266of279requests succeeded;
13in-flight failures remain counted as failures. All37steady-tail batches
(111requests starting at least15seconds after the last switch) passed normal TLS.
A10second total cellular outage recovered automatically. Six full-body transfers
passed on LTE, including1MiB, Google204, GitHub, RU, homeHTML and a homeJSasset.
A later kernel process-birth check proved the same process beyond63minutes, with
empty native panic files. Host observation had a gap and does not establish a
completed continuous65minute monitor. The failing Doze test above invalidated
APK60 as the final version and led to APK61.

APK61 passed eight independent85second deep-Doze intervals with the first unique
home DNS reply in135-199ms; all6DNS/3HTTPS per interval passed while IDLE both
before and after payload. One earlier measurement was invalid because the prior
safety timer ended Doze early; it is preserved separately and not counted.
A per-test marker isolates the watchdog; the repeated interval passed.

Six ordinary network phases and three rapid loops passed. Twelve further cycles
with two concurrent workers produced243requests:236success and7in-flight failures
(3SSLHandshakeException,4IOException). All30steady-tail batches90requests passed.
After20seconds with cellular disabled, allDNS/HTTPS recovered automatically.
Six complete-body HTTPS transfers passed on each of LTE and Wi-Fi:1MiB,
Google204, GitHub, RussianHTML, homeHTML and a same-host homeJSasset. Tests kept
the same kernel process birth throughout; network/wake reconnect preferences
remain false, connection-reset preferences true. All146JVM tests pass.

Final observations, OS-exit/bundle/config checks and exact artifact/source hashes
are recorded in `artifacts/lte-stability/final/validation.json`.
Tests use explicit USB serial, verify transport and saved/running revisions,
issue real DNS packets including unique uncached queries, and require normal
certificate verification for foreign/Russian/home HTTPS. The foreign trace must
report a non-Russian exit. Full configuration, raw logs and diagnostic bundles
remain outside the synced workspace. No enrolled NetBird profile is available
for a field test. The identity of the historical APK58 panic remains unproven.
