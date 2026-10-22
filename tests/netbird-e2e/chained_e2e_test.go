package netbirde2e

import (
	"context"
	"crypto/sha256"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"maps"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/coder/websocket"
	"github.com/google/uuid"
	"github.com/miekg/dns"
	"github.com/netbirdio/netbird/client/embed"
	"github.com/netbirdio/netbird/management/internals/controllers/network_map/controller"
	"github.com/netbirdio/netbird/management/internals/controllers/network_map/update_channel"
	"github.com/netbirdio/netbird/management/internals/modules/peers"
	"github.com/netbirdio/netbird/management/internals/modules/peers/ephemeral/manager"
	"github.com/netbirdio/netbird/management/internals/server/config"
	nbgrpc "github.com/netbirdio/netbird/management/internals/shared/grpc"
	mgmt "github.com/netbirdio/netbird/management/server"
	"github.com/netbirdio/netbird/management/server/activity"
	nbcache "github.com/netbirdio/netbird/management/server/cache"
	"github.com/netbirdio/netbird/management/server/groups"
	"github.com/netbirdio/netbird/management/server/integrations/integrated_validator/validator"
	"github.com/netbirdio/netbird/management/server/job"
	"github.com/netbirdio/netbird/management/server/permissions"
	"github.com/netbirdio/netbird/management/server/settings"
	"github.com/netbirdio/netbird/management/server/store"
	"github.com/netbirdio/netbird/management/server/telemetry"
	"github.com/netbirdio/netbird/management/server/types"
	relayserver "github.com/netbirdio/netbird/relay/server"
	relayws "github.com/netbirdio/netbird/relay/server/listener/ws"
	mgmtproto "github.com/netbirdio/netbird/shared/management/proto"
	relayauth "github.com/netbirdio/netbird/shared/relay/auth"
	signalproto "github.com/netbirdio/netbird/shared/signal/proto"
	signalserver "github.com/netbirdio/netbird/signal/server"
	"github.com/netbirdio/netbird/util"
	"github.com/sagernet/sing-vmess/vless"
	"github.com/sagernet/sing/common/logger"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/stretchr/testify/require"
	"go.opentelemetry.io/otel"
	"go.uber.org/mock/gomock"
	"google.golang.org/grpc"
)

// Run explicitly with CGO enabled and a C compiler:
// CGO_ENABLED=1 go test -ldflags=-checklinkname=0 -run '^TestChainedNetBird$' -timeout 5m .
// Every server binds an ephemeral loopback port. No production setup key,
// account, host DNS, or remote NetBird service is used.
func TestChainedNetBird(t *testing.T) {
	e2eRejectHostDNS(t)
	ctx, cancel := context.WithTimeout(context.Background(), 3*time.Minute)
	t.Cleanup(cancel)
	work := t.TempDir()
	setupKey := strings.ToUpper(uuid.NewString())
	relaySecret := uuid.NewString()
	signalAddress := e2eSignal(t, ctx)
	relayAddress := e2eRelay(t, relaySecret)
	managementAddress := e2eManagement(t, ctx, setupKey, relaySecret)
	targets := map[string]string{
		"management.invalid:443": managementAddress,
		"signal.invalid:443":     signalAddress,
		"relay.invalid:443":      relayAddress,
	}
	proxy := e2eVLESS(t, targets)
	remoteProxy := e2eVLESS(t, targets)
	peerConfig := e2ePeerConfig{
		ProxyAddress: remoteProxy.address, VLESSID: remoteProxy.id, SetupKey: setupKey,
		StateDir: filepath.Join(work, "remote-peer"), ReadyFile: filepath.Join(work, "ready.json"),
		StopFile: filepath.Join(work, "stop"),
	}
	configFile := filepath.Join(work, "peer.json")
	e2eWriteJSON(t, configFile, peerConfig)
	executable, err := os.Executable()
	require.NoError(t, err)
	child := exec.CommandContext(ctx, executable, "-test.run=^TestChainedNetBirdPeerHelper$", "-test.timeout=180s", "-test.v")
	child.Env = append(os.Environ(), "NEKO_NETBIRD_PEER_CONFIG="+configFile)
	logFile, err := os.Create(filepath.Join(work, "peer.log"))
	require.NoError(t, err)
	child.Stdout, child.Stderr = logFile, logFile
	require.NoError(t, child.Start())
	childDone := make(chan error, 1)
	go func() { childDone <- child.Wait() }()
	t.Cleanup(func() {
		_ = os.WriteFile(peerConfig.StopFile, []byte("stop"), 0600)
		select {
		case err := <-childDone:
			if err != nil && !t.Failed() {
				t.Errorf("remote peer: %v", err)
			}
		case <-time.After(10 * time.Second):
			_ = child.Process.Kill()
			<-childDone
			t.Error("remote peer did not stop")
		}
		_ = logFile.Close()
		data, _ := os.ReadFile(logFile.Name())
		if t.Failed() {
			t.Logf("remote peer log:\n%s", data)
		} else {
			require.Contains(t, string(data), "host DNS attempts: 0")
			t.Log("remote peer host DNS attempts: 0")
		}
	})
	var ready e2eReady
	require.Eventually(t, func() bool {
		data, err := os.ReadFile(peerConfig.ReadyFile)
		return err == nil && json.Unmarshal(data, &ready) == nil && ready.FQDN != ""
	}, 40*time.Second, 100*time.Millisecond, "remote peer did not enroll")
	localState := filepath.Join(work, "local-peer")
	client := e2eClient(t, ctx, proxy.address, proxy.id, setupKey, localState, "chained-local", true)
	t.Cleanup(func() { e2eStop(t, client) })
	status, err := client.Status()
	require.NoError(t, err)
	identity := status.LocalPeerState.PubKey
	e2eWaitEcho(t, ctx, client, "tcp", net.JoinHostPort(ready.IP, ready.TCPPort))
	e2eWaitEcho(t, ctx, client, "tcp", net.JoinHostPort(ready.FQDN, ready.TCPPort))
	e2eWaitEcho(t, ctx, client, "udp", net.JoinHostPort(ready.FQDN, ready.UDPPort))
	packetConn, err := client.ListenUDP(":0")
	require.NoError(t, err)
	require.NoError(t, packetConn.SetDeadline(time.Now().Add(5*time.Second)))
	_, err = packetConn.WriteTo([]byte("packet-echo"), &net.UDPAddr{IP: net.ParseIP(ready.IP), Port: e2ePort(t, ready.UDPPort)})
	require.NoError(t, err)
	buffer := make([]byte, 128)
	n, _, err := packetConn.ReadFrom(buffer)
	require.NoError(t, err)
	require.Equal(t, "packet-echo", string(buffer[:n]))
	require.NoError(t, packetConn.Close())
	query := new(dns.Msg)
	query.SetQuestion(dns.Fqdn(ready.FQDN), dns.TypeA)
	queryWire, err := query.Pack()
	require.NoError(t, err)
	answerWire, err := client.ExchangeDNS(ctx, queryWire)
	require.NoError(t, err)
	answer := new(dns.Msg)
	require.NoError(t, answer.Unpack(answerWire))
	require.Equal(t, query.Id, answer.Id)
	require.Equal(t, dns.RcodeSuccess, answer.Rcode)
	found := false
	for _, rr := range answer.Answer {
		if a, ok := rr.(*dns.A); ok && a.A.String() == ready.IP {
			found = true
		}
	}
	require.True(t, found, "overlay DNS must return remote peer IP: %s", answer)
	status, err = client.Status()
	require.NoError(t, err)
	relayed := false
	for _, peer := range status.Peers {
		if peer.PubKey == ready.PubKey {
			relayed = peer.Relayed
		}
	}
	require.True(t, relayed, "real peer data must use relay")
	proxy.mu.Lock()
	seen, denied := maps.Clone(proxy.seen), append([]string(nil), proxy.denied...)
	proxy.mu.Unlock()
	for _, target := range []string{"management.invalid:443", "signal.invalid:443", "relay.invalid:443"} {
		require.Positive(t, seen[target], "missing VLESS destination %s", target)
	}
	require.Empty(t, denied, "unexpected underlay destination")

	// Stop all existing proxy streams while rejecting new ones. An overlay
	// request must fail instead of selecting a direct connection or host DNS.
	proxy.setBlocked(true)
	failureCtx, failureCancel := context.WithTimeout(ctx, 2*time.Second)
	err = e2eEcho(failureCtx, client, "tcp", net.JoinHostPort(ready.IP, ready.TCPPort))
	failureCancel()
	require.Error(t, err, "proxy outage must fail closed")
	proxy.setBlocked(false)
	e2eWaitEcho(t, ctx, client, "tcp", net.JoinHostPort(ready.IP, ready.TCPPort))

	// Reopen the saved identity without a setup key; registration must survive
	// Stop/Start and must not depend on an enrollment credential on every run.
	require.NoError(t, client.Stop(ctx))
	client = e2eClient(t, ctx, proxy.address, proxy.id, "", localState, "chained-local", true)
	status, err = client.Status()
	require.NoError(t, err)
	require.Equal(t, identity, status.LocalPeerState.PubKey)
	e2eWaitEcho(t, ctx, client, "tcp", net.JoinHostPort(ready.FQDN, ready.TCPPort))
	t.Log("real NetBird enrollment, TCP, UDP, DNS, VLESS outage recovery and persistent identity passed")
}

type e2ePeerConfig struct{ ProxyAddress, VLESSID, SetupKey, StateDir, ReadyFile, StopFile string }
type e2eReady struct{ IP, FQDN, PubKey, TCPPort, UDPPort string }

func TestChainedNetBirdPeerHelper(t *testing.T) {
	file := os.Getenv("NEKO_NETBIRD_PEER_CONFIG")
	if file == "" {
		t.Skip("subprocess helper")
	}
	e2eRejectHostDNS(t)
	data, err := os.ReadFile(file)
	require.NoError(t, err)
	var cfg e2ePeerConfig
	require.NoError(t, json.Unmarshal(data, &cfg))
	ctx, cancel := context.WithTimeout(context.Background(), 170*time.Second)
	defer cancel()
	client := e2eClient(t, ctx, cfg.ProxyAddress, cfg.VLESSID, cfg.SetupKey, cfg.StateDir, "chained-remote", false)
	defer e2eStop(t, client)
	tcp, err := client.ListenTCP(":0")
	require.NoError(t, err)
	defer tcp.Close()
	udp, err := client.ListenUDP(":0")
	require.NoError(t, err)
	defer udp.Close()
	go func() {
		for {
			conn, err := tcp.Accept()
			if err != nil {
				return
			}
			go func() { defer conn.Close(); _, _ = io.Copy(conn, conn) }()
		}
	}()
	go func() {
		buffer := make([]byte, 65535)
		for {
			n, addr, err := udp.ReadFrom(buffer)
			if err != nil {
				return
			}
			_, _ = udp.WriteTo(buffer[:n], addr)
		}
	}()
	status, err := client.Status()
	require.NoError(t, err)
	ip, _, _ := net.ParseCIDR(status.LocalPeerState.IP)
	if ip == nil {
		ip = net.ParseIP(status.LocalPeerState.IP)
	}
	require.NotNil(t, ip)
	_, tcpPort, err := net.SplitHostPort(tcp.Addr().String())
	require.NoError(t, err)
	_, udpPort, err := net.SplitHostPort(udp.LocalAddr().String())
	require.NoError(t, err)
	e2eWriteJSON(t, cfg.ReadyFile, e2eReady{ip.String(), status.LocalPeerState.FQDN, status.LocalPeerState.PubKey, tcpPort, udpPort})
	ticker := time.NewTicker(100 * time.Millisecond)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			t.Fatal(ctx.Err())
		case <-ticker.C:
			if _, err := os.Stat(cfg.StopFile); err == nil {
				return
			}
		}
	}
}

func e2eClient(t *testing.T, ctx context.Context, proxyAddress, id, setupKey, stateDir, hostname string, blockInbound bool) *embed.Client {
	t.Helper()
	require.NoError(t, os.MkdirAll(stateDir, 0700))
	codec, err := vless.NewClient(id, "", logger.NOP())
	require.NoError(t, err)
	lazy := false
	client, err := embed.New(embed.Options{
		ManagementURL: "http://management.invalid:443", SetupKey: setupKey, DeviceName: hostname,
		ConfigPath: filepath.Join(stateDir, "config.json"), StatePath: filepath.Join(stateDir, "state.json"),
		DisableIPv6: true, BlockLANAccess: true, BlockInbound: blockInbound, LazyConnectionEnabled: &lazy, LogLevel: "warn",
		UnderlayDialer: dialFunc(func(ctx context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" && network != "tcp4" && network != "tcp6" {
				return nil, fmt.Errorf("unexpected underlay network %s", network)
			}
			conn, err := (&net.Dialer{}).DialContext(ctx, "tcp", proxyAddress)
			if err != nil {
				return nil, err
			}
			proxied, err := codec.DialConn(conn, M.ParseSocksaddr(address))
			if err != nil {
				_ = conn.Close()
			}
			return proxied, err
		}),
	})
	require.NoError(t, err)
	startCtx, cancel := context.WithTimeout(ctx, 35*time.Second)
	defer cancel()
	require.NoError(t, client.Start(startCtx))
	return client
}

func e2eWaitEcho(t *testing.T, ctx context.Context, client *embed.Client, network, address string) {
	t.Helper()
	deadline := time.Now().Add(45 * time.Second)
	var lastErr error
	for time.Now().Before(deadline) && ctx.Err() == nil {
		attemptCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		lastErr = e2eEcho(attemptCtx, client, network, address)
		cancel()
		if lastErr == nil {
			return
		}
		time.Sleep(250 * time.Millisecond)
	}
	t.Fatalf("%s echo to %s: %v (context: %v)", network, address, lastErr, ctx.Err())
}

func e2eEcho(ctx context.Context, client *embed.Client, network, address string) error {
	conn, err := client.DialContext(ctx, network, address)
	if err != nil {
		return err
	}
	defer conn.Close()
	if deadline, ok := ctx.Deadline(); ok {
		_ = conn.SetDeadline(deadline)
	}
	payload := []byte("netbird-through-vless")
	if _, err := conn.Write(payload); err != nil {
		return err
	}
	buffer := make([]byte, len(payload))
	if _, err := io.ReadFull(conn, buffer); err != nil {
		return err
	}
	if string(buffer) != string(payload) {
		return errors.New("echo payload mismatch")
	}
	return nil
}

type e2eProxy struct {
	address, id string
	targets     map[string]string
	mu          sync.Mutex
	seen        map[string]int
	denied      []string
	blocked     bool
	connections map[net.Conn]struct{}
}

func e2eVLESS(t *testing.T, targets map[string]string) *e2eProxy {
	t.Helper()
	lis := e2eListener(t)
	proxy := &e2eProxy{address: lis.Addr().String(), id: uuid.NewString(), targets: targets, seen: make(map[string]int), connections: make(map[net.Conn]struct{})}
	service := vless.NewService[string](logger.NOP(), proxy)
	service.UpdateUsers([]string{"ephemeral-test"}, []string{proxy.id}, []string{""})
	go func() {
		for {
			conn, err := lis.Accept()
			if err != nil {
				return
			}
			go func() {
				if err := service.NewConnection(context.Background(), conn, M.ParseSocksaddr(conn.RemoteAddr().String()), nil); err != nil {
					_ = conn.Close()
				}
			}()
		}
	}()
	t.Cleanup(func() {
		proxy.setBlocked(true)
		proxy.mu.Lock()
		seen, denied := maps.Clone(proxy.seen), append([]string(nil), proxy.denied...)
		proxy.mu.Unlock()
		require.Empty(t, denied, "unexpected VLESS destination during complete lifecycle")
		for target := range targets {
			require.Positive(t, seen[target], "missing VLESS destination %s", target)
		}
		t.Logf("VLESS destinations: %v", seen)
	})
	return proxy
}

func (p *e2eProxy) setBlocked(blocked bool) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.blocked = blocked
	if blocked {
		for conn := range p.connections {
			_ = conn.Close()
		}
	}
}

func (p *e2eProxy) NewConnectionEx(ctx context.Context, conn net.Conn, _ M.Socksaddr, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	p.mu.Lock()
	address, allowed := p.targets[destination.String()]
	blocked := p.blocked
	if !allowed {
		p.denied = append(p.denied, destination.String())
	}
	if allowed && !blocked {
		p.connections[conn] = struct{}{}
		p.seen[destination.String()]++
	}
	p.mu.Unlock()
	if !allowed || blocked {
		_ = conn.Close()
		return
	}
	go func() {
		defer func() {
			p.mu.Lock()
			delete(p.connections, conn)
			p.mu.Unlock()
			_ = conn.Close()
			if onClose != nil {
				onClose(nil)
			}
		}()
		target, err := (&net.Dialer{}).DialContext(ctx, "tcp", address)
		if err != nil {
			return
		}
		defer target.Close()
		done := make(chan struct{})
		go func() { _, _ = io.Copy(target, conn); _ = target.Close(); close(done) }()
		_, _ = io.Copy(conn, target)
		_ = conn.Close()
		<-done
	}()
}

func (p *e2eProxy) NewPacketConnectionEx(_ context.Context, conn N.PacketConn, _, destination M.Socksaddr, onClose N.CloseHandlerFunc) {
	p.mu.Lock()
	p.denied = append(p.denied, "udp:"+destination.String())
	p.mu.Unlock()
	_ = conn.Close()
	if onClose != nil {
		onClose(errors.New("UDP underlay prohibited"))
	}
}

func e2eListener(t *testing.T) net.Listener {
	t.Helper()
	lis, err := net.Listen("tcp", "127.0.0.1:0")
	require.NoError(t, err)
	t.Cleanup(func() { _ = lis.Close() })
	return lis
}

func e2eSignal(t *testing.T, ctx context.Context) string {
	t.Helper()
	lis := e2eListener(t)
	signal, err := signalserver.NewServer(ctx, otel.Meter("netbird-chain-test"))
	require.NoError(t, err)
	srv := grpc.NewServer()
	signalproto.RegisterSignalExchangeServer(srv, signal)
	go func() { _ = srv.Serve(lis) }()
	t.Cleanup(func() { signal.Stop(); srv.Stop() })
	return lis.Addr().String()
}

func e2eRelay(t *testing.T, secret string) string {
	t.Helper()
	lis := e2eListener(t)
	hashedSecret := sha256.Sum256([]byte(secret))
	relay, err := relayserver.NewServer(relayserver.Config{ExposedAddress: "relay.invalid:443", AuthValidator: relayauth.NewTimedHMACValidator(hashedSecret[:])})
	require.NoError(t, err)
	httpServer := &http.Server{ReadHeaderTimeout: 5 * time.Second, Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		conn, err := websocket.Accept(w, r, nil)
		if err != nil {
			return
		}
		addr, err := net.ResolveTCPAddr("tcp", r.RemoteAddr)
		if err != nil {
			_ = conn.CloseNow()
			return
		}
		relay.RelayAccept()(relayws.NewConn(conn, addr))
	})}
	go func() { _ = httpServer.Serve(lis) }()
	t.Cleanup(func() {
		_ = httpServer.Close()
		shutdownCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = relay.Shutdown(shutdownCtx)
	})
	return lis.Addr().String()
}

func e2eManagement(t *testing.T, ctx context.Context, setupKey, secret string) string {
	t.Helper()
	cfg := &config.Config{
		Stuns: []*config.Host{}, TURNConfig: &config.TURNConfig{},
		Relay:  &config.Relay{Addresses: []string{"rel://relay.invalid:443"}, CredentialsTTL: util.Duration{Duration: time.Hour}, Secret: secret},
		Signal: &config.Host{Proto: "http", URI: "signal.invalid:443"}, Datadir: t.TempDir(),
	}
	source := os.Getenv("NEKO_NETBIRD_SOURCE")
	if source == "" {
		source = filepath.Join("..", "..", "..", "netbird")
	}
	fixture, err := os.ReadFile(filepath.Join(source, "client", "testdata", "store.sql"))
	require.NoError(t, err)
	fixture = []byte(strings.ReplaceAll(string(fixture), "A2C8E62B-38F5-4553-B31E-DD66C696CEBB", setupKey))
	fixtureFile := filepath.Join(cfg.Datadir, "fixture.sql")
	require.NoError(t, os.WriteFile(fixtureFile, fixture, 0600))
	testStore, cleanUp, err := store.NewTestStoreFromSQL(ctx, fixtureFile, cfg.Datadir)
	require.NoError(t, err, "SQLite test fixture needs CGO_ENABLED=1 and a C compiler")
	t.Cleanup(cleanUp)
	eventStore := &activity.InMemoryEventStore{}
	permissionsManager := permissions.NewManager(testStore)
	peersManager := peers.NewManager(testStore, permissionsManager)
	jobManager := job.NewJobManager(nil, testStore, peersManager)
	cacheStore, err := nbcache.NewStore(ctx, 100*time.Millisecond, 300*time.Millisecond, 100)
	require.NoError(t, err)
	iv, err := validator.NewIntegratedValidator(ctx, peersManager, nil, eventStore, cacheStore)
	require.NoError(t, err)
	metrics, err := telemetry.NewDefaultAppMetrics(ctx)
	require.NoError(t, err)
	settingsManager := settings.NewMockManager(gomock.NewController(t))
	settingsManager.EXPECT().GetSettings(gomock.Any(), gomock.Any(), gomock.Any()).Return(&types.Settings{}, nil).AnyTimes()
	settingsManager.EXPECT().GetExtraSettings(gomock.Any(), gomock.Any()).Return(&types.ExtraSettings{}, nil).AnyTimes()
	groupsManager := groups.NewManagerMock()
	updateManager := update_channel.NewPeersUpdateManager(metrics)
	requestBuffer := mgmt.NewAccountRequestBuffer(ctx, testStore)
	networkMapController := controller.NewController(ctx, testStore, metrics, updateManager, requestBuffer, mgmt.MockIntegratedValidator{}, settingsManager, "netbird.selfhosted", manager.NewEphemeralManager(testStore, peersManager), cfg, nil)
	accountManager, err := mgmt.BuildManager(ctx, cfg, testStore, networkMapController, jobManager, nil, "", eventStore, nil, false, iv, metrics, settingsManager, permissionsManager, false, cacheStore)
	require.NoError(t, err)
	secretsManager, err := nbgrpc.NewTimeBasedAuthSecretsManager(updateManager, cfg.TURNConfig, cfg.Relay, settingsManager, groupsManager)
	require.NoError(t, err)
	management, err := nbgrpc.NewServer(cfg, accountManager, settingsManager, jobManager, secretsManager, nil, nil, &mgmt.MockIntegratedValidator{}, networkMapController, nil, nil)
	require.NoError(t, err)
	srv := grpc.NewServer()
	mgmtproto.RegisterManagementServiceServer(srv, management)
	lis := e2eListener(t)
	go func() { _ = srv.Serve(lis) }()
	t.Cleanup(srv.Stop)
	return lis.Addr().String()
}

func e2eWriteJSON(t *testing.T, file string, value any) {
	t.Helper()
	data, err := json.Marshal(value)
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(file+".tmp", data, 0600))
	require.NoError(t, os.Rename(file+".tmp", file))
}

func e2ePort(t *testing.T, value string) int {
	t.Helper()
	addr, err := net.ResolveUDPAddr("udp", "127.0.0.1:"+value)
	require.NoError(t, err)
	return addr.Port
}

// A custom resolver makes any accidental host-DNS path fail observably. All
// control names are .invalid and only the VLESS server knows their mappings.
func e2eRejectHostDNS(t *testing.T) {
	t.Helper()
	previous := net.DefaultResolver
	var queries atomic.Int64
	net.DefaultResolver = &net.Resolver{PreferGo: true, Dial: func(context.Context, string, string) (net.Conn, error) {
		queries.Add(1)
		return nil, errors.New("host DNS prohibited by isolated E2E test")
	}}
	t.Cleanup(func() {
		net.DefaultResolver = previous
		require.Zero(t, queries.Load(), "NetBird attempted host DNS outside VLESS")
		t.Logf("host DNS attempts: %d", queries.Load())
	})
}

type dialFunc func(context.Context, string, string) (net.Conn, error)

func (f dialFunc) DialContext(ctx context.Context, network, address string) (net.Conn, error) {
	return f(ctx, network, address)
}

func e2eStop(t *testing.T, client *embed.Client) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := client.Stop(ctx); err != nil && !errors.Is(err, embed.ErrClientNotStarted) {
		t.Errorf("stop NetBird test peer: %v", err)
	}
}
