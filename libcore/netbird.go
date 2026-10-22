package libcore

import "libcore/protocol/netbird"

func NetBirdStatus() string            { return netbird.RuntimeStatus() }
func NetBirdDiagnostics() string       { return netbird.RuntimeDiagnostics() }
func StartNetBirdDiagnostics() string  { return netbird.StartDiagnostics() }
func CancelNetBirdDiagnostics() string { return netbird.CancelDiagnostics() }
