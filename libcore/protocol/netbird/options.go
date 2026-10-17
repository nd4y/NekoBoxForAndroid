package netbird

import "github.com/sagernet/sing-box/option"

// OutboundOptions describes a NetBird peer whose entire underlay uses Detour.
// There is intentionally no direct/P2P fallback option.
type OutboundOptions struct {
	option.DialerOptions
	ManagementURL  string `json:"management_url"`
	SetupKey       string `json:"setup_key"`
	Hostname       string `json:"hostname,omitempty"`
	StateDirectory string `json:"state_directory"`
}
