# NetBird through VLESS integration test

This separate Go module keeps the management-server and SQLite test dependencies
out of the Android production build. Run the repository's
`buildScript/lib/core/get_source.sh` first to prepare the pinned, patched NetBird
source checkout. The default SQL fixture path is
`../../../netbird/client/testdata/store.sql`; `NEKO_NETBIRD_SOURCE` can override the
checkout location.

Requirements: Go 1.26.7, a C compiler, and CGO for the upstream SQLite fixture.
From this directory:

```sh
CGO_ENABLED=1 GOMAXPROCS=1 GOMEMLIMIT=1GiB \
  go test -p=1 -ldflags=-checklinkname=0 \
  -run '^TestChainedNetBird$' -timeout 5m -v .
```

The linker option matches NetBird's upstream build requirements for its network
interface dependencies. It does not change the test's network access policy.

The test starts real NetBird management, signal and WebSocket relay servers,
then connects two embedded clients through separate VLESS listeners using the production
VLESS codec fork. The management fixture uses plain HTTP gRPC on loopback; the production outbound
requires HTTPS. The second client runs in a subprocess because the embedded
NetBird runtime has process-global state. All host listeners use ephemeral
`127.0.0.1` ports. Temporary identities and setup credentials are created only
for the fixture and removed with the test directory. The local client uses the
production `BlockInbound=true` firewall mode; the remote echo peer accepts inbound
connections.

The assertions cover enrollment, relay selection, TCP by IP and FQDN, UDP via
both connected sockets and PacketConn, full DNS messages, proxy outage failure
and recovery for the local client, and saved-identity reuse without a setup key. Control-plane names
end in `.invalid`; only the VLESS forwarder knows their loopback mapping. Both
clients reject and count host DNS requests and require that count to remain
zero. The VLESS forwarder rejects every destination outside its three service
mappings and rejects UDP underlay requests.

On Linux, the test can additionally run in a transient service with
`PrivateNetwork=yes`, `MemoryMax=2G`, `MemorySwapMax=0` and `CPUQuota=100%`.
Download module dependencies beforehand; the test itself needs only loopback.
This test does not replace Android device testing of TLS/Reality transports,
network handover, or the application lifecycle and profile configuration tests.

The module starts from production dependency pins. Shared dependency versions are
kept aligned; test-only server dependencies are separate. Both modules replace
NetBird with the same pinned, patched sibling checkout, irrespective of the
nominal NetBird version selected by Go module requirements.
