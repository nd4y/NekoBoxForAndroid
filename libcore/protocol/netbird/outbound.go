package netbird

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/netbirdio/netbird/client/embed"
	"github.com/sagernet/sing-box/adapter"
	"github.com/sagernet/sing-box/adapter/outbound"
	"github.com/sagernet/sing-box/log"
	M "github.com/sagernet/sing/common/metadata"
	N "github.com/sagernet/sing/common/network"
	"github.com/sagernet/sing/service"
)

const Type = "netbird"

func RegisterOutbound(registry *outbound.Registry) {
	outbound.Register[OutboundOptions](registry, Type, NewOutbound)
}

type Outbound struct {
	outbound.Adapter
	ctx             context.Context
	logger          log.ContextLogger
	options         OutboundOptions
	manager         adapter.OutboundManager
	mu              sync.RWMutex
	client          peerClient
	newClient       func(embed.Options) (peerClient, error)
	underlay        *trackedUnderlay
	closed          bool
	ready           bool
	startDone       chan struct{}
	startCancel     context.CancelFunc
	releaseIdentity func()
	releaseProcess  func()
}

type peerClient interface {
	Start(context.Context) error
	Stop(context.Context) error
	DialContext(context.Context, string, string) (net.Conn, error)
	ListenUDP(string) (net.PacketConn, error)
	LookupIP(context.Context, string) ([]net.IP, error)
	ExchangeDNS(context.Context, []byte) ([]byte, error)
}

func NewOutbound(ctx context.Context, _ adapter.Router, logger log.ContextLogger, tag string, options OutboundOptions) (adapter.Outbound, error) {
	if options.Detour == "" || options.Detour == tag {
		return nil, errors.New("netbird requires a separate VLESS detour")
	}
	u, err := url.Parse(options.ManagementURL)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || (u.Path != "" && u.Path != "/") {
		return nil, errors.New("netbird management_url must be an HTTPS server URL without credentials, query or path")
	}
	if options.StateDirectory == "" || !filepath.IsAbs(options.StateDirectory) {
		return nil, errors.New("netbird requires an absolute persistent state_directory")
	}
	manager := service.FromContext[adapter.OutboundManager](ctx)
	if manager == nil {
		return nil, errors.New("netbird outbound manager is unavailable")
	}
	return &Outbound{
		Adapter: outbound.NewAdapterWithDialerOptions(Type, tag, []string{N.NetworkTCP, N.NetworkUDP}, options.DialerOptions),
		ctx:     ctx, logger: logger, options: options, manager: manager,
		newClient: func(opts embed.Options) (peerClient, error) { return embed.New(opts) },
	}, nil
}

func (o *Outbound) Start(stage adapter.StartStage) error {
	if stage != adapter.StartStateStarted {
		return nil
	}
	o.mu.Lock()
	if o.closed {
		o.mu.Unlock()
		return net.ErrClosed
	}
	if o.ready {
		o.mu.Unlock()
		return nil
	}
	if o.startDone != nil {
		o.mu.Unlock()
		return errors.New("netbird startup has already been attempted")
	}
	ctx, cancel := context.WithTimeout(o.ctx, 45*time.Second)
	done := make(chan struct{})
	o.startCancel, o.startDone = cancel, done
	o.mu.Unlock()
	defer close(done)
	defer cancel()

	if _, err := validatedVLESSDetour(o.manager, o.options.Detour); err != nil {
		return err
	}
	if strings.TrimSpace(o.options.SetupKey) == "" {
		info, err := os.Stat(filepath.Join(o.options.StateDirectory, "config.json"))
		if err != nil || info.IsDir() {
			return errors.New("netbird setup_key is required for first enrollment")
		}
	}
	if err := os.MkdirAll(o.options.StateDirectory, 0700); err != nil {
		return fmt.Errorf("create netbird state directory: %w", err)
	}
	if err := os.Chmod(o.options.StateDirectory, 0700); err != nil {
		return fmt.Errorf("protect netbird state directory: %w", err)
	}
	releaseIdentity, err := acquireIdentity(o.options.StateDirectory)
	if err != nil {
		return err
	}
	o.mu.Lock()
	o.releaseIdentity = releaseIdentity
	o.mu.Unlock()
	u := newUnderlay(o.ctx, vlessGroupDialer{manager: o.manager, tag: o.options.Detour})
	o.mu.Lock()
	if o.closed {
		o.mu.Unlock()
		u.Close()
		return net.ErrClosed
	}
	o.underlay = u
	o.mu.Unlock()
	releaseProcess, err := acquirePeerProcess()
	if err != nil {
		u.Close()
		return err
	}
	o.mu.Lock()
	o.releaseProcess = releaseProcess
	o.mu.Unlock()
	client, err := o.newClient(embed.Options{
		DeviceName:     o.options.Hostname,
		ManagementURL:  o.options.ManagementURL,
		SetupKey:       o.options.SetupKey,
		ConfigPath:     filepath.Join(o.options.StateDirectory, "config.json"),
		StatePath:      filepath.Join(o.options.StateDirectory, "state.json"),
		UnderlayDialer: u,
		BlockInbound:   true,
		DisableIPv6:    true,
		LogLevel:       "warn",
		LogOutput:      logWriter{logger: o.logger, secret: o.options.SetupKey},
	})
	if err != nil {
		u.Close()
		return fmt.Errorf("initialize netbird: %w", err)
	}
	o.mu.Lock()
	o.client = client
	o.mu.Unlock()
	if err = client.Start(ctx); err != nil {
		u.Close()
		return fmt.Errorf("start netbird through VLESS: %w", err)
	}
	o.mu.Lock()
	if o.closed {
		o.mu.Unlock()
		return net.ErrClosed
	}
	o.ready = true
	o.mu.Unlock()
	o.logger.Info("NetBird client started through VLESS; relay-only underlay")
	return nil
}

func (o *Outbound) getClient() (peerClient, error) {
	o.mu.RLock()
	defer o.mu.RUnlock()
	if o.closed {
		return nil, net.ErrClosed
	}
	if !o.ready || o.client == nil {
		return nil, errors.New("netbird is not connected")
	}
	return o.client, nil
}

func (o *Outbound) DialContext(ctx context.Context, network string, destination M.Socksaddr) (net.Conn, error) {
	network = N.NetworkName(network)
	if network != N.NetworkTCP && network != N.NetworkUDP {
		return nil, fmt.Errorf("netbird does not support network %s", network)
	}
	client, err := o.getClient()
	if err != nil {
		return nil, err
	}
	return client.DialContext(ctx, network, destination.String())
}

func (o *Outbound) ListenPacket(ctx context.Context, _ M.Socksaddr) (net.PacketConn, error) {
	client, err := o.getClient()
	if err != nil {
		return nil, err
	}
	conn, err := client.ListenUDP(":0")
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithCancel(ctx)
	return &packetConn{PacketConn: conn, ctx: ctx, cancel: cancel, resolver: client}, nil
}

func (o *Outbound) InterfaceUpdated() {
	o.mu.RLock()
	u := o.underlay
	o.mu.RUnlock()
	if u != nil {
		u.Reset()
	}
}

func (o *Outbound) Close() error {
	o.mu.Lock()
	if o.closed {
		o.mu.Unlock()
		return nil
	}
	o.closed = true
	u, cancelStart, done := o.underlay, o.startCancel, o.startDone
	o.mu.Unlock()
	if cancelStart != nil {
		cancelStart()
	}
	if u != nil {
		u.Close()
	}
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	if done != nil {
		select {
		case <-done:
		case <-ctx.Done():
			// Preserve eventual cleanup even if a dependency ignores cancellation.
			go func() { <-done; _ = o.stopClient(context.Background()) }()
			return ctx.Err()
		}
	}
	return o.stopClient(ctx)
}

func (o *Outbound) stopClient(ctx context.Context) error {
	o.mu.RLock()
	client := o.client
	releaseIdentity := o.releaseIdentity
	releaseProcess := o.releaseProcess
	o.mu.RUnlock()
	if client == nil {
		if releaseIdentity != nil {
			releaseIdentity()
		}
		if releaseProcess != nil {
			releaseProcess()
		}
		return nil
	}
	err := client.Stop(ctx)
	// Keep the identity locked if teardown did not finish. A timed-out engine
	// must never race a new process writing the same keys/state.
	if (err == nil || errors.Is(err, embed.ErrClientNotStarted)) && releaseIdentity != nil {
		releaseIdentity()
	}
	if (err == nil || errors.Is(err, embed.ErrClientNotStarted)) && releaseProcess != nil {
		releaseProcess()
	}
	if errors.Is(err, embed.ErrClientNotStarted) {
		return nil
	}
	return err
}

type logWriter struct {
	logger log.ContextLogger
	secret string
}

func (w logWriter) Write(p []byte) (int, error) {
	line := strings.TrimSpace(string(p))
	if w.secret != "" {
		line = strings.ReplaceAll(line, w.secret, "[redacted]")
	}
	if line != "" {
		w.logger.Warn(line)
	}
	return len(p), nil
}
