package libcore

import (
	"context"
	box "github.com/sagernet/sing-box"
	"github.com/sagernet/sing-box/option"
)

// CheckConfigJSON decodes typed options only. It never constructs transports,
// creates a NetBird identity, binds sockets, or starts a second VPN instance.
func CheckConfigJSON(config string) error {
	ctx := box.Context(context.Background(),
		nekoboxAndroidInboundRegistry(), nekoboxAndroidOutboundRegistry(), nekoboxAndroidEndpointRegistry(),
		nekoboxAndroidDNSTransportRegistry(nil), nekoboxAndroidServiceRegistry())
	var options option.Options
	if err := options.UnmarshalJSONContext(ctx, []byte(config)); err != nil {
		return err
	}
	return validateByeDPIOptions(options)
}
