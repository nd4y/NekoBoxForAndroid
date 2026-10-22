package libcore

import (
	"bufio"
	"context"
	stdtls "crypto/tls"
	"crypto/x509"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/sagernet/sing-box/common/tls"
	"github.com/sagernet/sing-box/option"
	xhttp "github.com/sagernet/sing-box/transport/v2rayxhttp"
	M "github.com/sagernet/sing/common/metadata"
)

type handoverSocketDialer struct{}

func (handoverSocketDialer) DialContext(ctx context.Context, network string, address M.Socksaddr) (net.Conn, error) {
	return (&net.Dialer{}).DialContext(ctx, network, address.String())
}
func (handoverSocketDialer) ListenPacket(context.Context, M.Socksaddr) (net.PacketConn, error) {
	panic("unused")
}

type handoverTLS struct{ config *stdtls.Config }

func (c *handoverTLS) ServerName() string                 { return c.config.ServerName }
func (c *handoverTLS) SetServerName(n string)             { c.config.ServerName = n }
func (c *handoverTLS) NextProtos() []string               { return c.config.NextProtos }
func (c *handoverTLS) SetNextProtos(p []string)           { c.config.NextProtos = p }
func (c *handoverTLS) STDConfig() (*stdtls.Config, error) { return c.config, nil }
func (c *handoverTLS) Client(conn net.Conn) (tls.Conn, error) {
	return stdtls.Client(conn, c.config), nil
}
func (c *handoverTLS) Clone() tls.Config { return &handoverTLS{c.config.Clone()} }

// Run standalone with: go test -ldflags=-checklinkname=0 xhttp_handover_test.go
// Uses the application's actual module versions and real HTTP streams.
func TestXHTTPRealHTTPStreamSurvivesDialContextAndResets(t *testing.T) {
	for _, h2 := range []bool{false, true} {
		t.Run(map[bool]string{false: "http1", true: "http2"}[h2], func(t *testing.T) {
			srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if _, err := bufio.NewReader(r.Body).ReadString('\n'); err != nil {
					return
				}
				_, _ = w.Write([]byte("ok\n"))
				w.(http.Flusher).Flush()
				_, _ = io.Copy(io.Discard, r.Body)
			}))
			srv.EnableHTTP2 = h2
			var config tls.Config
			if h2 {
				srv.StartTLS()
				roots := x509.NewCertPool()
				roots.AddCert(srv.Certificate())
				config = &handoverTLS{&stdtls.Config{ServerName: "example.com", RootCAs: roots, NextProtos: []string{"h2"}}}
			} else {
				srv.Start()
			}
			defer srv.Close()
			client, err := xhttp.NewClient(t.Context(), handoverSocketDialer{}, M.SocksaddrFromNet(srv.Listener.Addr()), option.V2RayXHTTPOptions{V2RayXHTTPBaseOptions: option.V2RayXHTTPBaseOptions{Path: "/xhttp"}, Mode: "stream-one"}, config)
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			for i := 0; i < 2; i++ {
				ctx, cancel := context.WithCancel(t.Context())
				conn, err := client.DialContext(ctx)
				cancel()
				if err != nil {
					t.Fatal(err)
				}
				done := make(chan error, 1)
				go func() {
					_, err := conn.Write([]byte("hello\n"))
					if err == nil {
						b := make([]byte, 3)
						_, err = io.ReadFull(conn, b)
					}
					done <- err
				}()
				select {
				case err := <-done:
					if err != nil {
						_ = conn.Close()
						t.Fatal(err)
					}
				case <-time.After(2 * time.Second):
					_ = conn.Close()
					t.Fatal("established stream lost dial-context lifetime")
				}
				blocked := make(chan error, 1)
				go func() { _, err := conn.Read(make([]byte, 1)); blocked <- err }()
				if err := client.(interface{ Reset() error }).Reset(); err != nil {
					t.Fatal(err)
				}
				select {
				case <-blocked:
				case <-time.After(time.Second):
					_ = conn.Close()
					t.Fatal("reset did not unblock real stream")
				}
				_ = conn.Close()
			}
		})
	}
}
