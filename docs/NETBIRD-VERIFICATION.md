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

At the initial version-49 fixture acceptance, physical Android transitions,
background behavior and production enrollment were still untested. The later
physical acceptance below supersedes that limitation for the measured scenarios.
A working local fixture or APK build alone is not proof of those paths.

## Physical Cloud acceptance, 2026-10-19

The installed debug application is version `1.4.2-63`, ARM64 versionCode `300`,
with native `plus-62`. The separately signed production application and official
NetBird application's profiles were preserved. USB ADB and the control API were
used for installation, configuration and measurements.

- A temporary userspace client first tested normal direct NetBird, without a
  VLESS underlay or Android VPN. Cloud enrollment succeeded. After the user
  assigned the required Cloud access group, internal DNS, TCP 5001/443/22,
  an SSH banner and HTTP 5000 succeeded on Wi-Fi. On restricted LTE, direct
  control connections and resource TCP failed. Wi-Fi restored access in the
  same process; the first recovery sample had transient TCP timeouts before
  the subsequent complete sample passed.
- Only that newly enrolled test identity was transferred into the debug app's
  private profile directory after stopping the temporary client. Byte equality
  and the retained peer key were checked without printing key material. The
  setup-key field was empty before the chained client first started.
- NetBird uses the existing VLESS-only RU URLTest group. Its physical group
  outbound and health selection are shared with ordinary routing. Every
  selectable branch is validated before dialing; missing, empty, cyclic and
  mixed/direct branches are rejected. Windows graph tests passed 100 repeats;
  the isolated Linux helper tests passed 20 repeats with the race detector.
- A real device failure exposed custom JSON replacing the generated internal
  DNS rule. Explicit overlay DNS and routing now apply after all custom merges.
  The regression test covers replaced lists, priority and idempotence. The
  complete JVM suite passed 148 tests, with no failures, errors or skips.
- The generated chain passed Wi-Fi and LTE DNS, TCP 5001/443/22, SSH-banner and
  complete HTTP-body checks. Three complete Wi-Fi/LTE cycles and three rapid
  switching cycles passed in the same process. Existing Russian, foreign and
  home DNS/TLS probes also passed, with foreign egress outside Russia.
- The final configuration uses only the actual DNS zone and IPv4 mesh prefix
  received from Cloud. All 321 eligible profile roots validated before applying
  that patch. A subsequent core restart on LTE reused the saved identity and
  passed the complete functional phase.
- After 85 seconds in verified deep Doze on LTE, the first overlay DNS, all
  three TCP connections, SSH banner and complete HTTP body passed while the
  device remained in IDLE. Forced idle was then removed. The same process
  survived the whole acceptance sequence, including the planned core restart.
- Literal internal IPv4 access passed on both networks. Complete TLS response
  bodies passed on Wi-Fi and LTE for a 1-MiB download, Google, GitHub, a Russian
  site and a home service plus its static asset; no partial body was hidden by
  an HTTP-status-only check.
- Final readback found no new operating-system process exits, Java/native
  crashes or ANRs; all four crash log sources were empty. The diagnostic bundle
  contained 17 files and every manifest hash verified, with a stable revision.
  Original rules, preferences, home WireGuard, location permissions, battery
  exemption and manual whitelist selection were retained. Wi-Fi was restored,
  forced idle removed, and the connected VPN left running. Task-owned phone
  probes and temporary enrollment files were removed; the app identity remains.

The APK SHA256 is
`6c0b0847ef703ac4a87a00f42c492850e2aef9d96222e4cbf8e4e82deb19a692`.
All 630 Android input files matched the build checkout; APK native bytes matched
the AAR, both native ABI ELF LOAD alignments were at least 16 KiB, zip alignment
passed with `-P 16`, and APK v1/v2 signatures verified. The AAR SHA256 is
`4853b046a744f4795b192ef1d2b9a553a7d0c4de631617169c19e88d3ff6cab7`.
Raw configurations, peer topology, logs and the diagnostic bundle remain in
private LocalAppData. Sanitized package and acceptance evidence is stored in the
sibling `artifacts/netbird63` directory. These are bounded field tests, not a
guarantee against every future provider outage or every possible device state.

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
