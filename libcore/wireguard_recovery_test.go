//go:build with_gvisor && with_wireguard

package libcore

import (
	"bufio"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/netip"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"github.com/miekg/dns"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/option"
	M "github.com/sagernet/sing/common/metadata"
)

func recoveryBox(t *testing.T, cfg map[string]any) *box.Box {
	t.Helper()
	ctx := box.Context(context.Background(), nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(), nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry())
	raw, _ := json.Marshal(cfg)
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, raw); err != nil {
		t.Fatal(err)
	}
	b, err := box.New(box.Options{Options: options, Context: ctx})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { b.Close() })
	start := time.Now()
	if err = b.Start(); err != nil {
		t.Fatalf("a failed peer stopped the box: %v", err)
	}
	if time.Since(start) > time.Second {
		t.Fatal("box start waited for peer DNS")
	}
	return b
}

func recoveryKey(t *testing.T) (string, string) {
	t.Helper()
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return base64.StdEncoding.EncodeToString(k.Bytes()), base64.StdEncoding.EncodeToString(k.PublicKey().Bytes())
}

func wgConfig(key, pub, addr, allowed, host string, port, listen int) map[string]any {
	return map[string]any{"log": map[string]any{"disabled": true}, "outbounds": []any{map[string]any{"type": "direct", "tag": "direct"}}, "endpoints": []any{map[string]any{"type": "wireguard", "tag": "wg", "address": []string{addr}, "private_key": key, "listen_port": listen, "workers": 1, "peers": []any{map[string]any{"public_key": pub, "address": host, "port": port, "allowed_ips": []string{allowed}}}}}}
}

func TestWireGuardDNSFailureKeepsDirectAndRecoversActualTunnel(t *testing.T) {
	var dnsReady atomic.Bool
	var queries atomic.Int32
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	srv := &dns.Server{PacketConn: pc, Handler: dns.HandlerFunc(func(w dns.ResponseWriter, r *dns.Msg) {
		queries.Add(1)
		m := new(dns.Msg)
		m.SetReply(r)
		if !dnsReady.Load() {
			m.Rcode = dns.RcodeServerFailure
		} else {
			m.Answer = []dns.RR{&dns.A{Hdr: dns.RR_Header{Name: r.Question[0].Name, Rrtype: dns.TypeA, Class: dns.ClassINET, Ttl: 1}, A: net.IPv4(127, 0, 0, 1)}}
		}
		w.WriteMsg(m)
	})}
	go srv.ActivateAndServe()
	defer srv.Shutdown()
	clientKey, clientPub := recoveryKey(t)
	serverKey, serverPub := recoveryKey(t)
	reservation, _ := net.ListenPacket("udp", "127.0.0.1:0")
	port := reservation.LocalAddr().(*net.UDPAddr).Port
	reservation.Close()
	recoveryBox(t, wgConfig(serverKey, clientPub, "10.90.0.2/32", "10.90.0.1/32", "", 0, port))
	cfg := wgConfig(clientKey, serverPub, "10.90.0.1/32", "10.90.0.2/32", "peer.example.test", port, 0)
	cfg["dns"] = map[string]any{"servers": []any{map[string]any{"type": "udp", "tag": "bootstrap", "server": "127.0.0.1", "server_port": pc.LocalAddr().(*net.UDPAddr).Port}}}
	cfg["endpoints"].([]any)[0].(map[string]any)["domain_resolver"] = map[string]any{"server": "bootstrap", "strategy": "ipv4_only", "disable_cache": true}
	b := recoveryBox(t, cfg)
	ep, _ := b.Endpoint().Get("wg")
	// Even while unavailable, implicit preferred routes remain owned by WG.
	if !ep.(adapter.OutboundWithPreferredRoutes).PreferredAddress(netip.MustParseAddr("10.90.0.2")) {
		t.Fatal("unavailable private route lost ownership")
	}
	if c, err := ep.DialContext(context.Background(), "tcp", M.ParseSocksaddr("10.90.0.2:80")); err == nil {
		c.Close()
		t.Fatal("unresolved peer leaked traffic")
	}
	origin := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, "recovered-wireguard") }))
	defer origin.Close()
	deadline := time.Now().Add(time.Second)
	for queries.Load() == 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if queries.Load() == 0 {
		t.Fatal("DNS failure was not exercised")
	}
	direct, _ := b.Outbound().Outbound("direct")
	c, err := direct.DialContext(context.Background(), "tcp", M.ParseSocksaddr(strings.TrimPrefix(origin.URL, "http://")))
	if err != nil {
		t.Fatalf("unrelated path stopped: %v", err)
	}
	c.Close()
	dnsReady.Store(true)
	_, httpPort, _ := net.SplitHostPort(strings.TrimPrefix(origin.URL, "http://"))
	var tunnel net.Conn
	deadline = time.Now().Add(15 * time.Second)
	for time.Now().Before(deadline) {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		tunnel, err = ep.DialContext(ctx, "tcp", M.ParseSocksaddr("10.90.0.2:"+httpPort))
		cancel()
		if err == nil {
			break
		}
		time.Sleep(100 * time.Millisecond)
	}
	if err != nil {
		t.Fatalf("WireGuard did not recover after DNS return: %v", err)
	}
	defer tunnel.Close()
	tunnel.SetDeadline(time.Now().Add(3 * time.Second))
	fmt.Fprintf(tunnel, "GET / HTTP/1.1\r\nHost: recovered.example\r\nConnection: close\r\n\r\n")
	resp, err := http.ReadResponse(bufio.NewReader(tunnel), nil)
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "recovered-wireguard" {
		t.Fatalf("wrong tunnel payload: %q", body)
	}
}

func TestWireGuardCloseCancelsBlockedResolver(t *testing.T) {
	pc, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer pc.Close()
	key, pub := recoveryKey(t)
	cfg := wgConfig(key, pub, "10.91.0.1/32", "10.91.0.2/32", "blocked.example.test", 51820, 0)
	cfg["dns"] = map[string]any{"servers": []any{map[string]any{"type": "udp", "tag": "bootstrap", "server": "127.0.0.1", "server_port": pc.LocalAddr().(*net.UDPAddr).Port}}}
	cfg["endpoints"].([]any)[0].(map[string]any)["domain_resolver"] = map[string]any{"server": "bootstrap", "strategy": "ipv4_only"}
	b := recoveryBox(t, cfg)
	got := make(chan struct{})
	go func() { var p [1024]byte; pc.ReadFrom(p[:]); close(got) }()
	select {
	case <-got:
	case <-time.After(time.Second):
		t.Fatal("resolver never entered blocked I/O")
	}
	done := make(chan struct{})
	go func() { b.Close(); close(done) }()
	select {
	case <-done:
	case <-time.After(time.Second):
		t.Fatal("Close waited for DNS timeout")
	}
}
