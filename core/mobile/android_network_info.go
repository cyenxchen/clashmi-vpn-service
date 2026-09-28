package clashmicore

import (
	"encoding/json"
	"errors"
	"net/netip"
	"slices"
	"strings"
)

type androidNetworkInfo struct {
	DefaultInterface string                    `json:"defaultInterface"`
	Interfaces       []androidNetworkInterface `json:"interfaces"`
}

type androidNetworkInterface struct {
	Name          string   `json:"name"`
	Index         int      `json:"index"`
	MTU           int      `json:"mtu"`
	NetworkHandle uint64   `json:"networkHandle"`
	Validated     bool     `json:"validated"`
	Addresses     []string `json:"addresses"`
	DNSServers    []string `json:"dnsServers"`
}

func (info androidNetworkInfo) healthCheckFingerprint() (string, bool) {
	interfaces := make([]string, 0, len(info.Interfaces))
	available := false
	for _, iface := range info.Interfaces {
		iface.Addresses = canonicalAndroidNetworkAddresses(iface.Addresses)
		iface.DNSServers = canonicalAndroidNetworkAddresses(iface.DNSServers)
		if info.DefaultInterface != "" && iface.Name == info.DefaultInterface && len(iface.Addresses) > 0 {
			available = true
		}
		encoded, _ := json.Marshal(iface)
		interfaces = append(interfaces, string(encoded))
	}
	// Android may enumerate the same networks, addresses and DNS servers in a
	// different order on otherwise equivalent capability callbacks.
	slices.Sort(interfaces)
	encoded, _ := json.Marshal(struct {
		DefaultInterface string
		Interfaces       []string
	}{info.DefaultInterface, slices.Compact(interfaces)})
	return string(encoded), available
}

func canonicalAndroidNetworkAddresses(values []string) []string {
	canonical := make([]string, 0, len(values))
	for _, value := range values {
		value = strings.TrimSpace(value)
		if value == "" {
			continue
		}
		if prefix, err := netip.ParsePrefix(value); err == nil {
			value = prefix.String()
		} else if address, err := netip.ParseAddr(value); err == nil {
			value = address.String()
		}
		canonical = append(canonical, value)
	}
	slices.Sort(canonical)
	return slices.Compact(canonical)
}

func parseAndroidNetworkInfo(raw string) (androidNetworkInfo, error) {
	var info androidNetworkInfo
	if err := json.Unmarshal([]byte(raw), &info); err != nil {
		return androidNetworkInfo{}, errors.New("invalid Android network snapshot")
	}
	info.DefaultInterface = strings.TrimSpace(info.DefaultInterface)
	// Interface names are diagnostic and routing metadata, never arbitrary log
	// payloads. Reject control characters and unreasonable values before the
	// value crosses into Tailscale or persistent logging.
	if len(info.DefaultInterface) > 64 || strings.ContainsAny(info.DefaultInterface, "\r\n\t") {
		return androidNetworkInfo{}, errors.New("invalid Android default interface")
	}
	return info, nil
}
