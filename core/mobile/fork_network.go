package clashmicore

import (
	"context"
	"encoding/hex"
	"strings"
	"unicode/utf8"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/component/netenv"
	"github.com/metacubex/mihomo/log"
)

func forkReportAndroidNetwork(info androidNetworkInfo) error {
	fields := netenv.Fields{Type: "none"}
	if info.DefaultInterface != "" {
		found := false
		for _, iface := range info.Interfaces {
			if iface.Name != info.DefaultInterface {
				continue
			}
			found = true
			ssid := forkAndroidSSID(iface.SSID)
			fields = netenv.Fields{}
			values := map[string]string{"type": iface.Transport, "interface": iface.Name, "ssid": ssid, "bssid": iface.BSSID, "gateway": iface.Gateway, "dhcp-server": iface.DHCPServer}
			for key, value := range values {
				if value == "" || key == "bssid" && strings.EqualFold(value, "02:00:00:00:00:00") || key == "type" && value == "none" {
					continue
				}
				f, err := netenv.ParseFields(map[string]any{key: value}, true)
				if err != nil {
					continue
				}
				switch key {
				case "type":
					fields.Type = f.Type
				case "interface":
					fields.Interface = f.Interface
				case "ssid":
					fields.SSID = f.SSID
				case "bssid":
					fields.BSSID = f.BSSID
				case "gateway":
					fields.Gateway = f.Gateway
				case "dhcp-server":
					fields.DHCPServer = f.DHCPServer
				}
			}
			if fields.Type == "wired" || fields.Type == "cellular" {
				fields.SSID = ""
				fields.BSSID = ""
			}
			break
		}
		if !found {
			// Legacy snapshots can carry only a route hint. Without the interface
			// record there is no complete new report; retain the last known network.
			return nil
		}
	}
	if err := forkPublishNetwork(context.Background(), fields, "embed"); err != nil {
		return err
	}
	log.Infoln("[ClashMiCore] subnet network report: %+v", netenv.Default.Current())
	return nil
}

// Injectable platform operations keep refresh-failure tests independent of Android.
var (
	forkPublishNetwork = func(ctx context.Context, f netenv.Fields, source string) error {
		return netenv.Default.Report(ctx, f, source)
	}
	forkNotifyNetwork           = outbound.NotifyTailscaleNetworkChange
	forkSetAndroidDNSServers    = setTailscaleAndroidDNSServers
	forkRefreshAndroidDNSPolicy = refreshAndroidTailscaleDNSPolicy
)

func forkAndroidSSID(value string) string {
	if len(value) >= 2 && value[0] == '"' && value[len(value)-1] == '"' {
		value = value[1 : len(value)-1]
	} else if value != "" {
		if raw, err := hex.DecodeString(value); err == nil {
			value = string(raw)
		}
	}
	if value == "<unknown ssid>" || !utf8.ValidString(value) || len(value) > 32 {
		return ""
	}
	return value
}
