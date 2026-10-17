# NetBird/VLESS verification record

Implementation base: `AlwaysBusyStack/NekoBoxForAndroid` commit
`e4a4e05500250038f508df0e0c884ea40c9e4c9c`, local branch
`feature/netbird-vless-chain`. Upstream app metadata is `1.4.2-49`.
NetBird source is pinned to `cf017119711184a0661062a0c8ac2043e4e8d411`
and patched by `patches/netbird/0001-strict-proxy-underlay.patch`.

## Build

The Android core uses Go 1.26.7 rebuilt with the repository's CLOCK_BOOTTIME
patch, NDK 27.3.13750724, and Android Studio's bundled JBR. The generated APKs
are restricted to arm64-v8a and x86_64; no unpopulated 32-bit variants are
published. The Windows and Docker build paths include NetBird's required
`-checklinkname=0` and external-linker 16-KB page alignment.

Verified native ELF LOAD alignment: `0x4000` for every segment in both ABIs.
The AAR build uses the Windows helper. A complete Docker rebuild was
not validated in this run; its wrapper was checked separately. The exact AAR
and source fingerprints accompany the APKs in
`libcore.buildinfo.json`.

## Checks

- Native outbound tests exercise real VLESS codec framing, opaque destination
  names, no direct fallback, forbidden UDP underlay, reset/cancellation,
  full DNS reply validation, identity file locks and the process lease.
- Android tests exercise chain order, generated JSON, DNS priority, migration
  schema, profile serialization, secret redaction, and cancellation before
  waiting for native startup.
- `tests/netbird-e2e` is a separate CGO test module using real management,
  signal, WebSocket relay and two peer processes. It uses temporary identities
  and synthetic credentials; no production NetBird account is involved.
- Android JVM suite: **107 tests across 23 suites passed**, including 16 added
  NetBird/export/cancellation tests. Room 19-to-20 migration and preservation
  of an existing profile row were checked separately.
- Real isolated integration: **TestChainedNetBird passed in 15.96 seconds**.
  Two actual embedded peers enrolled through VLESS, selected the WebSocket
  relay, exchanged TCP by IP/FQDN, UDP through both socket APIs, and full DNS
  messages. Forced VLESS outage failed closed, recovery restored traffic, and
  restarting without a setup key retained the peer public key. Both host DNS
  counters were zero; only the three allowed management/signal/relay
  destinations reached the VLESS forwarders.
- That integration test first exposed a real startup panic: the engine created
  ICE configuration from an intentionally absent UDP mux. Strict mode now
  returns an unused empty ICE configuration, consistent with its disabled ICE
  worker. The successful rerun includes this correction.
- Android lint is **not clean**: 127 findings remain on unchanged upstream
  source/resource lines. Three newly exposed native 16-KB alignment errors
  were corrected. No finding remains on added NetBird/cancellation lines;
  no lint baseline or suppression was added.
- Final adapter suite: all 15 `libcore/protocol/netbird` tests passed with CGO
  on Linux, read-only module resolution and the final strict-mode patch (0.184 seconds).
  The root Box cancellation regression passed (0.016 seconds). Patched NetBird
  underlay, real gRPC/WebSocket transport, embedded-client and strict local
  forwarding environment tests also passed (0.006/0.032/6.077/0.017 seconds).
  These Linux checks used CGO, Go 1.26.7, `-mod=readonly` and
  `-ldflags=-checklinkname=0`; the exact commands accompany the artifact logs.
- Scoped emulator smoke passed profile editing/save, setup-key masking, HTTPS
  validation, visible `[VLESS, NetBird]` ordering, standalone-start rejection,
  correct-chain startup and cancellation. The observed cancellation-to-ready
  upper bound was 2.889 seconds including UI observation, with the background
  process still alive. Identity directories/files had 0700/0600 permissions;
  their contents were not read.
- This smoke used the earlier candidate x86_64 APK, with the same Android UI
  and lifecycle source. Later native-only strict-mode corrections were covered
  by the real integration/native tests. Final packaging is tracked separately.
  Initial coldboot SystemUI and debug background-process ANRs were observed;
  a single scoped debug-app restart recovered, after which the checks passed.
  The ANR cause remains unestablished. The task-started emulator was stopped
  without wiping its data. The fresh debug app retains only synthetic fixtures.
- Final `assembleOssDebug` passed in 25 seconds. Packaging verification passed
  for arm64-v8a, x86_64 and the universal 64-bit APK: signing certificates,
  declared ABI sets, all ELF LOAD alignments at 0x4000, `zipalign -P 16`, and
  embedded native-library hashes matching the final AAR. Package ID is
  `com.nb4a.plus.debug`, version `1.4.2-49`, versionCode 230.
- Final native source fingerprint:
  `0af97dc76245278f93635b6639c65fe257a51f910266cc04a95eb2c04be902a1`.
  Final AAR SHA256:
  `61eb668612adc223b24ee14f013c6970976ff3997e5b3fc4e9016b6dc9754a3d`.
  The NetBird patch changes 32 files and has SHA256
  `9832d50128a169b2502cfe783523596508d1297e0aa30ade99311cc2f95f6c8e`.

## Delivered evidence

The sibling `artifacts` directory contains final APKs, `SHA256SUMS.txt`,
`validation.json`, `libcore.buildinfo.json`, JVM/lint summaries, native test
logs and exact commands in `native-validation.txt`, `netbird-e2e.log`, and
`ui/SMOKE.md` with emulator evidence. The UI smoke identifies the earlier
candidate it tested; it is not represented as a live Android enrollment test
of the final APK. The final APKs were checked against the final native AAR.

## Acceptance boundaries

The local integration fixture uses plain loopback gRPC; production profiles
require HTTPS. The fixture therefore does not validate a particular server's
certificate, Reality/TLS credentials, ACL configuration, advertised resource
routes or external relay reachability.

Physical Android Wi-Fi/LTE transitions, background battery behavior and
production enrollment require a separate device acceptance run. A working
local fixture or APK build is not proof of those paths. No APK has been
installed on the production phone and no production profile has been changed.

## Operational note

During initial parallel Linux toolchain/NDK preparation at 01:31 Moscow on
2026-10-18, WSL and existing containers restarted. A bounded read-only review of
Windows Hyper-V events identified a guest kernel panic: `Fatal local machine
check`, preceded by `Machine check: Processor Context Corrupt` (Event 18590,
record 9702). Event 18515 records the guest-initiated reset. This task issued no
WSL restart, shutdown or container-stop command. The underlying cause and causal
attribution to any workload remain unestablished; session continuity cannot be
claimed. The narrowly scoped evidence is saved in
`artifacts/wsl-guest-crash-evidence.json` alongside the build artifacts.
Subsequent heavy phases were serialized and bounded. The integration fixture
runs in a transient private network with a 2-GB memory limit, no swap and one
CPU; it cannot reach production services.