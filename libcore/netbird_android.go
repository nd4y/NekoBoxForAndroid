package libcore

import (
	"fmt"
	"github.com/netbirdio/netbird/client/embed"
	nbnet "github.com/netbirdio/netbird/client/net"
	"github.com/netbirdio/netbird/client/underlay"
	wgconn "golang.zx2c4.com/wireguard/conn"
	"libcore/protect"
	"net"
	"strings"
	"syscall"
)

func init() {
	// WireGuard opens its own UDP sockets outside nbnet.NewListener. Register
	// exactly once during init, before any engine can access the control list.
	*wgconn.ControlFns = append(*wgconn.ControlFns, func(network, address string, c syscall.RawConn) error {
		if underlay.DirectEnabled() {
			return nbnet.ControlProtectSocket(network, address, c)
		}
		return nil
	})
}
func installNetBirdSocketProtector() {
	nbnet.SetAndroidProtectSocketFn(func(fd int32) bool { return protect.FD(int(fd)) == nil })
	embed.SetPhysicalInterfaceDiscoverer(func() (string, error) {
		interfaces, err := readNetworkInterfaces()
		if err != nil {
			return "", err
		}
		currentPlatformNetworkState.access.Lock()
		state := currentPlatformNetworkState.defaultInterface
		currentPlatformNetworkState.access.Unlock()
		var lines []string
		for _, iface := range interfaces {
			// Advertise only the network to which protected sockets are actually bound.
			if iface.Name != state.Name {
				continue
			}
			i := iface.build()
			flags := i.Flags
			if flags&net.FlagUp == 0 || flags&net.FlagLoopback != 0 {
				continue
			}
			var addresses []string
			for _, prefix := range i.Addresses {
				if prefix.Addr().Is4() && prefix.Addr().IsGlobalUnicast() {
					addresses = append(addresses, prefix.String())
				}
			}
			if len(addresses) == 0 {
				continue
			}
			lines = append(lines, fmt.Sprintf("%s %d %d %t %t %t %t %t|%s", i.Name, i.Index, i.MTU,
				flags&net.FlagUp != 0, flags&net.FlagBroadcast != 0, false, flags&net.FlagPointToPoint != 0, flags&net.FlagMulticast != 0, strings.Join(addresses, " ")))
		}
		return strings.Join(lines, "\n"), nil
	})
}
