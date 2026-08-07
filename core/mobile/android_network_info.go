package clashmicore

import (
	"encoding/json"
	"errors"
	"strings"
)

type androidNetworkInfo struct {
	DefaultInterface string `json:"defaultInterface"`
	Interfaces       []struct {
		DNSServers []string `json:"dnsServers"`
	} `json:"interfaces"`
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
