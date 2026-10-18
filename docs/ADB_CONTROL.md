# ADB Control API and nekoctl

The fork provides a versioned, opt-in configuration API over Android's `content` command. No root or listening TCP port is required. Enable **Settings > Misc > ADB Control API** on the device first. Any computer authorized for that device's ADB can then control this app. The API cannot enable itself.

`tools/nekoctl/nekoctl.py` requires Python 3.9+ and Android platform-tools. The Windows wrapper is `tools/nekoctl/nekoctl.cmd`. Every command requires `--serial` or `ANDROID_SERIAL`; it never picks a connected phone automatically. The default package is `com.nb4a.plus.debug`; use `--package` for another installed build.

```powershell
$env:ANDROID_SERIAL = 'emulator-5580'
python tools/nekoctl/nekoctl.py status
python tools/nekoctl/nekoctl.py capabilities
python tools/nekoctl/nekoctl.py profiles
python tools/nekoctl/nekoctl.py rules
python tools/nekoctl/nekoctl.py settings
python tools/nekoctl/nekoctl.py --output config-redacted.json export
python tools/nekoctl/nekoctl.py plan patch.json
python tools/nekoctl/nekoctl.py apply patch.json --revision REVISION_FROM_PLAN
python tools/nekoctl/nekoctl.py checkpoints
python tools/nekoctl/nekoctl.py rollback CHECKPOINT_ID --revision CURRENT_REVISION
python tools/nekoctl/nekoctl.py vpn restart
```

`--adb` accepts the full adb executable path. `--output` creates a new UTF-8 JSON file and refuses to overwrite an existing file. `export --include-secrets` explicitly requests stored profile credentials. Default exports hide credentials and opaque custom configuration; do not feed `<redacted>` values back into a patch. NetBird peer identity files are never exported. Shell arguments contain no configuration payload: requests are sent on stdin. The client uses the duplex shell protocol (`shell -T`) to wait for command completion; `exec-in` can close the transport at stdin EOF before a cold provider finishes starting. Avoid putting real credentials in shell history, public files, source control or screenshots.

## Complete configuration workflow for an LLM

```powershell
python tools/nekoctl/nekoctl.py --serial DEVICE pull config.json
# Edit config.json locally. Keep its schema, revision and all fields.
python tools/nekoctl/nekoctl.py --serial DEVICE validate config.json
python tools/nekoctl/nekoctl.py --serial DEVICE push config.json
python tools/nekoctl/nekoctl.py --serial DEVICE status
# Restart only when intended:
python tools/nekoctl/nekoctl.py --serial DEVICE vpn restart
```

`pull` writes a plain `nekobox.full-config` schema-version-1 document to a NEW UTF-8 file. This complete document contains credentials, subscription details, stable IDs/order, rules, selection and every persistent preference, including keys unknown to the patch allowlist. Store it privately. `export --full --include-secrets` provides the same document inside the normal API envelope. The default `export` remains redacted and compatible with API v1 clients.

`validate FILE` and `push FILE --dry-run` validate without changing saved configuration or VPN. `push` and its alias `import` replace configuration atomically using the existing recovery journal and checkpoint mechanism. They use the file's `revision` for CAS; a subscription/UI/API change returns `REVISION_CONFLICT`. Pull a fresh document and reapply the intended edit. `--revision` can explicitly bind a document to a separately inspected current revision. Imports never silently retry, rewrite the source file or restart VPN. A subsequent edit cycle should start with a fresh pull, or an explicitly updated base revision. `FILE=-` accepts stdin. Full input files may be up to 8 MiB; the compact streamed API envelope still must fit the advertised request limit.

The `config` object has `profiles`, `groups`, `rules`, `preferences`, and `selectedProfile`. Profiles contain `id`, `groupId`, `userOrder`, `protocol`, `values`, and the internal `entityUuid`. `preferences` maps each name to `{ "type": "string", "value": "..." }`; types also include `boolean`, `int`, `long`, `float`, `string-set`, and `raw` for future opaque preference types. Keep complete objects: removing required fields, credentials or sections is rejected. Removing an entire profile/group/rule or preference is an explicit deletion/reset and is validated against references. Unknown fields, duplicate IDs, redacted placeholders, overflows and invalid known-setting ranges are rejected.

Device authorization and runtime state remain local: `adbControlEnabled`, last-running profile and selected UI group are not imported. Android permissions, NetBird peer identity files, caches, counters and installed plugin binaries are not configuration documents. New devices require their own local opt-in and Android VPN consent. `capabilities` advertises `fullConfigSchema` and the supported methods.

`render --profile ID` returns the generated native configuration without starting it; its default output is redacted. Use `--include-secrets` and a private `--output` file for local diagnostics. Rendering does not establish remote connections or guarantee runtime success.

## Logs and diagnostic bundles

```powershell
python tools/nekoctl/nekoctl.py --serial DEVICE logs --source core
python tools/nekoctl/nekoctl.py --serial DEVICE logs --source events --follow
python tools/nekoctl/nekoctl.py --serial DEVICE --output crash.json logs --source crash-bg
python tools/nekoctl/nekoctl.py --serial DEVICE --output native-crash.json logs --source crash-native-bg
python tools/nekoctl/nekoctl.py --serial DEVICE diagnostics PRIVATE-PATH/bundle.zip
```

`logs` returns a JSON envelope; `--follow` streams JSON lines until Ctrl+C. Sources: `core` (current native/app log), `previous` (bounded core tail preserved before background-process initialization truncates the cache log), `events` (bounded service/network journal), `crash-bg`, and `crash-main` (last persisted Java exception for each process). Crash and event recording works even when the core log level is off. An empty core source with `coreLoggingEnabled:false` means logging is disabled. Logs are never cleared by these commands.

`--max-bytes` is 256..262144, default65536; `--cursor` resumes from the raw byte offset returned by a previous read. Follow automatically carries the cursor. Rotation/truncation resets are explicit; cursors are best effort across concurrent rotation, not an exactly-once delivery guarantee. UTF-8 code points are retained across chunk boundaries. `--interval` is 0.2..60seconds, default2. Optional `--output` creates a NEW UTF-8 JSON/JSONL file. Secrets are masked by default using known stored credentials and conservative text patterns; `--include-secrets` explicitly requests raw app log text.

`diagnostics FILE` (alias `bundle`) creates a NEW ZIP containing a manifest with per-file hashes/errors, status before/after, capabilities, full redacted config, generated config, app/core versions, app/native memory, current Java and bounded Go goroutine stacks (argument values omitted), current underlying-network metadata, recent process exit reasons, all advertised log sources, package-scoped `dumpsys` exit/memory data and UID-filtered Android logcat. `--logcat-lines` defaults1000, max5000. No global logcat, network toggles, configuration changes, log clearing or VPN restart are performed. `--include-secrets` explicitly includes complete unredacted config and raw log text. Default OS text masking is best effort: routing addresses and other private details can remain, so keep every bundle in private local storage. No peer identity or private database files are collected.

Unavailable components are recorded in the manifest; a complete ZIP with `complete:false` is a successful partial collection. On the first transport failure, remaining provider calls are skipped, preserving scoped OS evidence without repeatedly waiting on a hung provider. Older APKs may support status/config but lack log/snapshot methods. Missing/disabled core logs cannot reconstruct old crashes. The Android exit API is available fromAPI30; native tombstone protobufs and historical ANR trace files are not exported by this first version. See [Android ApplicationExitInfo](https://developer.android.com/reference/android/app/ApplicationExitInfo).

Wire methods: `logs.read {source?,maxBytes?,cursor?,includeSecrets?}` and `diagnostics.snapshot {}`. The existing opt-in and shell-UID authorization apply to both. Snapshot records exclude exception messages and thread-local values; log text masking cannot guarantee anonymity for arbitrary third-party output.

`crash-native-bg` and `crash-native-main` retain Go runtime panics independently of
the core log level, Android logcat rotation and the Java exception handler. A
normal process restart preserves the previous crash. Native reads start at the
panic and crashing goroutine, with bounded cursor reads for the remaining dump.
Bundles collect both sources when advertised in `capabilities.logSources`,
retaining compatibility with older APKs. This captures Go runtime panics, not
arbitrary C/JNI signals, OOM kills or evidence from before installation.

## Editing configuration

A patch is an object containing an ordered `operations` array. The maximum is 256 operations and 1 MiB per request. IDs are positive integers and remain stable. Omitted properties retain their values, including passwords; an explicit empty string clears a string. Unknown fields, nulls, wrong types and integer overflow are rejected. `capabilities` reports supported setting names/types and protocol field templates. Integer-valued Android preferences use JSON strings, for example `"mtu":"1400"`.

```json
{
  "operations": [
    {"op":"profile.update","id":2,"values":{"name":"VLESS uplink"}},
    {"op":"settings.patch","values":{"remoteDns":"https://1.1.1.1/dns-query","enableDnsRouting":true}},
    {"op":"rule.create","values":{"name":"Example direct","enabled":true,"domains":"domain:example.org","outbound":-1}},
    {"op":"selection.set","id":3}
  ]
}
```

Supported operations:

| Operation | Fields |
| --- | --- |
| `profile.create` | `groupId`, `protocol`, `values`; optional `id`, `userOrder` |
| `profile.update` | `id`; optional `values`, `groupId`, `userOrder` |
| `profile.delete` | `id` |
| `group.create` | `values`; optional `id` |
| `group.update` | `id`, `values` |
| `group.delete` | `id`; default group cannot be deleted |
| `rule.create` | `values`; optional `id` |
| `rule.update` | `id`, `values` |
| `rule.delete` | `id` |
| `settings.patch` | `values` from the capabilities allowlist |
| `selection.set` | `id` of an existing profile |

Group edits cover name, order, selector, front/landing proxies and forced uTLS; API v1 does not create or edit subscription credentials. Existing subscriptions are retained. Profile types use names reported by capabilities, including `vless`, `netbird`, `chain`, `proxyset`, `wg` and `config`. Chain `values.proxies` uses the app's existing profile ID ordering. Create an explicit ID when another operation in the same patch must reference the new entity, or use the assigned IDs returned by `plan`.

Rules retain their native schema: `outbound:0` means selected proxy, `-1` direct, `-2` block, and positive IDs select a profile. Deleting referenced profiles/groups requires fixing all references in the same patch. Reopen the corresponding UI screen after an external edit to refresh its current list; do not save an old UI editor over a newer API change.

## Plan, apply and rollback

`plan` changes no database rows. It builds the candidate through the same configuration generator as the application, verifies references/cycles, and decodes options through the native core. It returns paths/actions without values, assigned IDs, current/candidate revisions and validated root profile IDs. NetBird profiles are components of chains in this fork; their parent chains are validated, and selecting a bare NetBird component is rejected. Validation supports at most 512 profiles. It never creates a second native box, joins NetBird, opens a VPN or tests remote connectivity. External plugin binaries, remote availability and all runtime conditions still require a separate connection test.

`apply` requires the revision returned by `plan` or `export`, validates again, then compares that revision while holding a shared process/OS lock. A change by the UI, a subscription update or another API call causes `REVISION_CONFLICT`; obtain a fresh export and plan. The lock surrounds every persistent DAO operation in both application processes, so readers cannot observe an API commit halfway through its two databases. A later UI save remains a new write; the API does not lock out the UI for the lifetime of an open editor.

Revisions are HMACs with a private, installation-local key and cover credential changes as well as configuration. Profile traffic/ping/status, last-running profile, selected UI group and the API opt-in switch are excluded. Apply writes an app-private undo journal before changing either database, commits only changed rows/keys, and preserves traffic counters. Recovery restores the previous state before the next DAO operation after an interrupted commit. Checkpoints and the journal live in `noBackupFilesDir/adb-control`, outside Android cloud backup; 20 completed checkpoints are retained.

Rollback restores the state before the specified checkpoint and creates a new checkpoint. It requires both the current revision and the selected checkpoint's resulting revision to match. To undo several changes, roll back in reverse order. Do not blindly retry an apply after a transport timeout: the outcome may already be committed. Inspect status/export/checkpoints first.

Saving and running are separate. `status` reports `savedRevision`, `runningRevision`, `selectedProfile`, `runningProfile`, `state`, and `restartRequired`. A successful configuration apply does **not** restart or disconnect VPN. `vpn start`, `vpn stop` and `vpn restart` are explicit asynchronous commands; poll `status` for completion. If Android VPN consent has not been granted, start/restart return `VPN_PERMISSION_REQUIRED`; grant consent in the app first.

## Wire protocol

Authority: `${applicationId}.control`, hosted in the existing `:bg` process.

1. Generate a UUID and stream a UTF-8 envelope to `content://AUTHORITY/requests/UUID` with `adb shell -T content write --uri ...`.
2. Run `adb shell content call --uri content://AUTHORITY --method execute --arg UUID`.
3. Read the response with `adb exec-out content read --uri content://AUTHORITY/responses/UUID`.
4. Delete the response with `adb shell content delete --uri ...`.

```json
{"apiVersion":1,"method":"config.plan","params":{"patch":{"operations":[{"op":"profile.update","id":2,"values":{"name":"New name"}}]}}}
```

Success: `{"ok":true,"apiVersion":1,"result":{...}}`.
Failure: `{"ok":false,"apiVersion":1,"error":{"code":"REVISION_CONFLICT","message":"..."}}`.

Methods: `capabilities`, `status`, `config.export`, `config.import`, `config.render`, `config.plan`, `config.apply`, `config.rollback`, `checkpoints.list`, `vpn.start`, `vpn.stop`, `vpn.restart`. `config.apply` parameters are `patch` and `expectedRevision`; rollback parameters are `checkpoint` and `expectedRevision`. `nekoctl` exit codes are 0 success, 2 API rejection, 3 transport/local failure. Errors never echo request JSON or native error messages that could contain credentials.

Every provider entry point checks `Binder.getCallingUid() == 2000` before clearing identity or dispatching work, plus the local opt-in. Root and ordinary application UIDs are not admitted. The manifest export is intentional, with the lint suppression confined to this provider; an Android instrumentation test exercises non-shell denial. Streams are bounded to 1 MiB, outstanding requests are bounded, UUID paths cannot traverse directories, and stale private request/result files are collected when subsequent requests arrive. Disabling the setting prevents new requests and is rechecked under the configuration commit lock.

## Verification

```powershell
python -m unittest discover -s tools/nekoctl -p test_nekoctl.py -v
.\gradlew.bat testOssDebugUnitTest assembleOssDebug assembleOssDebugAndroidTest lintOssDebug
# Install both APKs explicitly on the emulator; never use an unqualified connected-device task.
adb -s emulator-5580 install -r app/build/outputs/apk/androidTest/oss/debug/app-oss-debug-androidTest.apk
adb -s emulator-5580 shell am instrument -w com.nb4a.plus.debug.test/androidx.test.runner.AndroidJUnitRunner
```

The device tests verify denial for call/read/write/query/type/insert/update/delete and journal recovery from a partial cross-database commit. They temporarily modify synthetic configuration and restore the original configuration. Run only on a disposable test emulator. The command-line tests `tools/nekoctl/smoke_emulator.py` and `tools/nekoctl/lifecycle_emulator.py` exercise streamed JSON, partial updates, CAS, rollback and explicit lifecycle actions. Both require an explicit `emulator-*` serial and restore the original configuration. The lifecycle test may grant the initial VPN consent through the emulator UI.
