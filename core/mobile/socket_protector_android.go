//go:build android

package clashmicore

import (
	"encoding/json"
	"net"
	"net/netip"
	"net/url"
	"slices"
	"strings"
	"sync"
	"sync/atomic"

	"github.com/metacubex/mihomo/log"
	"tailscale.com/net/netns"
	"tailscale.com/net/tshttpproxy"
)

var (
	tailscaleDNSMu      sync.RWMutex
	tailscaleDNSServers []string

	tailscaleProxyMu          sync.RWMutex
	tailscaleProxyURL         string
	tailscaleProxyInstallOnce sync.Once
	tailscaleProxyInstallErr  error
	tailscaleProxySelectCount atomic.Uint64
)

type androidNetworkDNSInfo struct {
	Interfaces []struct {
		DNSServers []string `json:"dnsServers"`
	} `json:"interfaces"`
}

func setTailscaleSocketProtector(protector SocketProtector) {
	netns.SetAndroidProtectFunc(nil)
	if protector != nil {
		log.Infoln("[ClashMiCore] Tailscale Android netns protector disabled; Tailscale self traffic remains inside VPN for Mihomo routing")
	}
}

func setTailscaleAndroidDNSServersFromRaw(raw string) bool {
	var info androidNetworkDNSInfo
	if err := json.Unmarshal([]byte(raw), &info); err != nil {
		log.Warnln("[ClashMiCore] parse Android physical DNS servers failed: %v", err)
		return false
	}

	seen := map[string]struct{}{}
	servers := make([]string, 0)
	for _, iface := range info.Interfaces {
		for _, value := range iface.DNSServers {
			server, ok := normalizeTailscaleDNSServer(value)
			if !ok {
				continue
			}
			if _, exists := seen[server]; exists {
				continue
			}
			seen[server] = struct{}{}
			servers = append(servers, server)
		}
	}

	tailscaleDNSMu.Lock()
	changed := !slices.Equal(tailscaleDNSServers, servers)
	tailscaleDNSServers = servers
	tailscaleDNSMu.Unlock()

	if len(servers) == 0 {
		log.Warnln("[ClashMiCore] no Android physical DNS servers found for Mihomo DNS bootstrap")
		return changed
	}
	log.Infoln("[ClashMiCore] Android physical DNS servers updated: %s", strings.Join(servers, ","))
	return changed
}

func setTailscaleControlHTTPProxy(proxyURL string) {
	proxyURL = strings.TrimSpace(proxyURL)

	tailscaleProxyMu.Lock()
	changed := tailscaleProxyURL != proxyURL
	tailscaleProxyURL = proxyURL
	tailscaleProxyMu.Unlock()

	tailscaleProxyInstallOnce.Do(func() {
		tailscaleProxyInstallErr = tshttpproxy.SetProxyFunc(tailscaleProxyFromConfig)
	})
	if tailscaleProxyInstallErr != nil {
		log.Warnln("[ClashMiCore] install Tailscale control proxy hook failed: %v", tailscaleProxyInstallErr)
		return
	}
	if !changed {
		return
	}
	if proxyURL == "" {
		log.Warnln("[ClashMiCore] Tailscale control proxy disabled; no local HTTP proxy port found")
		return
	}
	log.Infoln("[ClashMiCore] Tailscale control proxy set to %s", redactedProxyURL(proxyURL))
}

func tailscaleProxyFromConfig(target *url.URL) (*url.URL, error) {
	tailscaleProxyMu.RLock()
	raw := tailscaleProxyURL
	tailscaleProxyMu.RUnlock()
	if raw == "" {
		return nil, nil
	}
	proxyURL, err := url.Parse(raw)
	if err != nil {
		log.Warnln("[ClashMiCore] parse Tailscale control proxy failed target=%s proxy=%s error=%v", target.Redacted(), redactedProxyURL(raw), err)
		return nil, err
	}
	count := tailscaleProxySelectCount.Add(1)
	if count <= 8 || count%50 == 0 {
		log.Infoln("[ClashMiCore] Tailscale control proxy selected target=%s proxy=%s count=%d", target.Redacted(), proxyURL.Redacted(), count)
	}
	return proxyURL, nil
}

func redactedProxyURL(raw string) string {
	proxyURL, err := url.Parse(raw)
	if err != nil {
		return "<invalid>"
	}
	return proxyURL.Redacted()
}

func normalizeTailscaleDNSServer(value string) (string, bool) {
	value = strings.TrimSpace(value)
	if value == "" {
		return "", false
	}
	if host, port, err := net.SplitHostPort(value); err == nil {
		if port == "" {
			port = "53"
		}
		if addr, err := netip.ParseAddr(strings.Trim(host, "[]")); err == nil {
			return net.JoinHostPort(addr.String(), port), true
		}
		return "", false
	}
	if addr, err := netip.ParseAddr(strings.Trim(value, "[]")); err == nil {
		return net.JoinHostPort(addr.String(), "53"), true
	}
	return "", false
}

func androidPhysicalDNSServers() []string {
	tailscaleDNSMu.RLock()
	defer tailscaleDNSMu.RUnlock()
	return append([]string(nil), tailscaleDNSServers...)
}
