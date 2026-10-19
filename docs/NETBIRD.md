# NetBird through a VLESS chain

## Design

The Android VPN remains owned by NekoBox. A `netbird` outbound runs an embedded
NetBird client with a userspace network stack. Its control connections and relay
WebSocket connections use the explicitly selected VLESS outbound.

```text
Android apps -> NekoBox TUN -> sing-box rules -> NetBird userspace network
                                               |
                              encrypted overlay packets + control traffic
                                               |
                                          VLESS outbound
                                               |
                                  VLESS server -> NetBird services/relay
```

This first implementation deliberately uses **relay-only WebSocket transport**.
Direct peer connections, ICE/STUN, QUIC, port forwarding discovery and direct
underlay DNS are disabled in this mode. A TCP-capable VLESS server is sufficient
to carry both TCP and UDP application traffic inside the NetBird overlay.
This trades direct peer latency and throughput for predictable chaining.

If VLESS fails, NetBird fails or reconnects through the same VLESS outbound.
There is no direct fallback. NetBird management and relay hostnames are passed
unchanged to VLESS for resolution at the remote end. Resolving the VLESS server
itself is still governed by the existing NekoBox bootstrap DNS configuration.

## Boundaries

- Only one active embedded NetBird client is supported in a core process.
  Upstream NetBird uses process-wide networking state; the integration takes an
  exclusive lease before constructing the client. An operating-system file lock
  also protects the identity directory against another process. A teardown
  timeout keeps these guards held until the process exits.
- The immediate outer hop must be a VLESS profile or a proxy set containing
  only VLESS profiles. Native selector/URLTest trees must have nonempty,
  existing VLESS-only branches; mixed, missing or cyclic branches are rejected,
  including branches that are not currently selected. The graph is revalidated
  before every underlay dial. Another NetBird and implicit direct routing are rejected.
- When a proxy set also serves ordinary routing, the generated configuration
  shares its physical outbound and health selection with NetBird. NetBird does
  not create a second independent copy of that group.
- Enrollment uses a setup key and an HTTPS management URL. Interactive SSO is
  not implemented in this version. After successful enrollment the setup key may
  be cleared; the saved peer identity is used for subsequent connections.
- Peer identity is persisted under the app's `noBackupFilesDir`, indexed by the
  stable NetBird profile ID. Switching the VLESS transport does not register a
  new peer. Android cloud backup and profile exports do not include private keys.
- This is an outbound client. Inbound connections to Android services and use
  of the phone as a routing peer are not part of this implementation.
- Access policies and resource routes remain controlled by the NetBird server.
  Chaining does not grant access to resources denied to the enrolled peer.
- Existing phone profiles and subscriptions are not migrated by this patch.
- The initial adapter uses an IPv4 overlay. External CNAME targets that require
  host DNS are refused; an internal DNS server must be reachable over NetBird.
  Ordinary Internet DNS continues to follow the existing NekoBox policy.

## Android usage

Use the separate debug application for acceptance testing. Back up its current
configuration before adding the NetBird profile and its routing rule.

1. Create or import the VLESS profile that will carry NetBird. Test that VLESS
   profile on its own first. Its TCP transport must be able to reach the NetBird
   management, signal and WebSocket relay services.
2. Add a **NetBird** profile. Enter the HTTPS management-server URL, a setup key
   for the first enrollment and a device name. Save the profile before connecting.
   Once enrolled, the saved identity is reused and the setup-key field may be cleared.
3. Optionally enter the actual internal DNS suffixes from that NetBird network,
   separated by commas, such as `office.example.netbird.cloud`. Leave the field
   empty for manual DNS configuration. Do not enter a wildcard, a public parent
   suffix or the hostname/zone used to bootstrap the VLESS proxy itself.
4. Add a **Proxy chain** with this top-to-bottom order in the chain editor:
   **VLESS, NetBird**, or **VLESS-only proxy set, NetBird**. In traffic direction that means applications enter NetBird
   and NetBird opens its transport connections through VLESS. A standalone
   NetBird profile, the reversed order, a mixed proxy set or two NetBird profiles in
   one generated core configuration is rejected.
5. For split routing, keep the normal VLESS profile selected and add an enabled
   routing rule targeting the new chain for the actual NetBird IPv4 addresses,
   advertised destination networks or internal domains. No mesh CIDR is guessed
   or installed automatically. A configured internal DNS suffix also receives
   a higher-priority DNS and connection rule once the chain is loaded.

Selecting the NetBird chain as the main profile makes it the default outbound;
Internet access then requires an exit/resource route provided by that NetBird
network. The first implementation carries IPv4 overlay traffic. Both application
TCP and UDP travel inside the TCP/WebSocket relay transport.

Internal DNS rules take precedence over FakeDNS, direct DNS and LAN bypass for
those explicit suffixes, including after global and profile custom JSON merges.
DNS reverse mapping preserves that choice for subsequent
connections to the returned addresses. Requests outside those suffixes retain the
normal NekoBox DNS policy. Applications using their own encrypted DNS may require
an explicit IPv4 routing rule because their queries bypass the app DNS router.
The same applies to a DNS alias whose final address record belongs to a name
outside the selected suffix: sing-box reverse mapping uses that final record name.

Background profile/group URL tests are disabled for configurations containing
NetBird, including full JSON profiles. Those tests can run in a different Android
process from the active VPN and must not reopen the same persistent identity.
The existing connection test against the already running VPN instance remains
available; use an accessible internal target rather than assuming an Internet
connectivity URL is reachable through the mesh.

Shared `sn://netbird` profiles and exported full configurations omit the setup key.
An exported configuration is a template: replace `/NETBIRD_STATE_DIRECTORY` with
an absolute private directory and supply a setup key for a new enrollment. It does
not contain the existing peer private key. While retained, the setup key follows
normal profile-credential storage and can be included in app database backups. Copying or importing a profile creates
an independent identity when it is first connected. Deleting a profile does not
remove its retained private state directory; uninstalling the debug app or clearing
its data removes that local state. Remove an enrolled test peer separately in
NetBird when retiring it.

## Configuration contract

The app generates the following outbound within a normal chain:

```json
{
  "type": "netbird",
  "tag": "netbird-home",
  "management_url": "https://netbird.example.com",
  "setup_key": "ENTER_SETUP_KEY",
  "hostname": "android-neko",
  "state_directory": "/absolute/private/app/path/netbird/profile-id",
  "detour": "vless-outer"
}
```

`state_directory` is generated by Android and is not a portable profile setting.
The setup key is credential material; do not publish runtime configuration.
NetBird logs are limited to warnings with setup-key redaction.

## Lifecycle

1. Validate that the profile is in a chain with a VLESS outer hop.
2. Validate native options and the detour reference; initialize the remaining
   sing-box services.
3. At the final start stage, create the persistent identity directory and start
   the embedded client with the guarded underlay dialer.
4. Forward selected application connections through NetBird's userspace stack.
5. On network changes, close old underlay connections. NetBird retries through
   the same VLESS transport. On VPN shutdown, cancel pending dials, close tracked
   connections and stop the embedded client.

## Verification

Executed checks and remaining device acceptance work are recorded in
[NETBIRD-VERIFICATION.md](NETBIRD-VERIFICATION.md).

The tests must cover transport behavior, not only JSON generation:

- Opaque management/relay names reach the selected proxy without local lookup.
- A broken VLESS dialer cannot reach an otherwise accessible direct listener.
- UDP underlay requests are rejected, while overlay datagrams remain supported.
- Pending connections are canceled on shutdown; reconnect after network reset
  still uses the same detour.
- Overlay name lookup failures do not fall back to the host resolver.
- NetBird's gRPC, relay and WireGuard paths honor the strict underlay mode.
- Android chain order, unsupported profile combinations and credential redaction
  are tested independently of the native build.
- Shutdown during enrollment cancels startup rather than waiting for its normal
  timeout, and a second process cannot open an active identity directory.

A full acceptance run additionally requires a NetBird management server and
relay, a test peer and a VLESS server. Verify enrollment, a private TCP service,
UDP/DNS, VLESS shutdown, recovery and a device Wi-Fi/LTE transition. Unit or
loopback proxy tests alone must not be presented as that full acceptance run.

## Rollback

Use a separate debug application ID for testing. The production application's
data is not touched. Remove the debug app to discard its local identity and
delete the corresponding test peer in NetBird if one was enrolled. Never copy
or overwrite a production NetBird identity directory.
