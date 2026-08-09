//go:build darwin && with_gvisor && !no_tailscale

package clashmicore

import (
	"net"
	"testing"

	"github.com/metacubex/tailscale/net/netmon"
)

// This assertion exercises a mobile-only Tailscale API. Keep it on Darwin,
// where the API exists and can run locally, instead of breaking Linux CI.
func TestSetAndroidNetworkInfoUpdatesTailscaleDefaultRoute(t *testing.T) {
	previous, previousErr := netmon.DefaultRouteInterface()
	interfaces, err := net.Interfaces()
	if err != nil {
		t.Fatal(err)
	}
	var target string
	for _, iface := range interfaces {
		addrs, addrErr := iface.Addrs()
		if iface.Name != previous && iface.Flags&net.FlagUp != 0 && iface.Flags&net.FlagLoopback == 0 && addrErr == nil && len(addrs) > 0 {
			target = iface.Name
			break
		}
	}
	if target == "" {
		t.Skip("no alternate active interface available")
	}
	if previousErr == nil {
		t.Cleanup(func() {
			netmon.UpdateLastKnownDefaultRouteInterface(previous)
		})
	}

	raw := `{"defaultInterface":"` + target + `","interfaces":[]}`
	if err := SetAndroidNetworkInfo(raw); err != nil {
		t.Fatal(err)
	}
	got, err := netmon.DefaultRouteInterface()
	if err != nil {
		t.Fatal(err)
	}
	if got != target {
		t.Fatalf("default route interface = %q, want %q after Android network update", got, target)
	}
}
