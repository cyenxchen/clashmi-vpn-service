package clashmicore

import (
	"context"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/metacubex/mihomo/component/netenv"

	"gopkg.in/yaml.v3"
)

func TestForkAndroidNetworkReport(t *testing.T) {
	before := netenv.Default
	netenv.Default = netenv.NewStore(time.Now)
	defer func() { netenv.Default = before; androidNetworkHealth.stop() }()
	raw := `{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","index":3,"addresses":["192.168.1.2/24"],"transport":"wifi","ssid":"\"GOLDWIND\"","bssid":"00-08-E3-FF-FC-04","gateway":"192.168.1.1","dhcpServer":"10.15.200.1"}]}`
	if err := SetAndroidNetworkInfo(raw); err != nil {
		t.Fatal(err)
	}
	got := netenv.Default.Current()
	if got.SSID != "GOLDWIND" || got.BSSID != "00:08:e3:ff:fc:04" || got.DHCPServer != "10.15.200.1" || got.Interface != "wlan0" {
		t.Fatalf("default network report missing/unnormalized: %+v", got)
	}
	shutdownLocked()
	if netenv.Default.Current() != got {
		t.Fatal("shutdown discarded pre-start report")
	}
	if err := SetAndroidNetworkInfo(`{"defaultInterface":"","interfaces":[]}`); err != nil {
		t.Fatal(err)
	}
	if netenv.Default.Current().State != netenv.None {
		t.Fatal("empty default is not none")
	}
}

func TestForkAndroidNetworkFingerprintExcludesIdentifiers(t *testing.T) {
	a, err := parseAndroidNetworkInfo(`{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","addresses":["192.168.1.2/24"]}]}`)
	if err != nil {
		t.Fatal(err)
	}
	b, err := parseAndroidNetworkInfo(`{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","addresses":["192.168.1.2/24"],"transport":"wifi","ssid":"GOLDWIND","bssid":"00:11:22:33:44:55","gateway":"192.168.1.1","dhcpServer":"10.15.200.1"}]}`)
	if err != nil {
		t.Fatal(err)
	}
	x, _ := a.healthCheckFingerprint()
	y, _ := b.healthCheckFingerprint()
	if x != y {
		t.Fatal("new identifiers changed health fingerprint")
	}
}

func TestForkReportSurvivesDNSRefreshFailure(t *testing.T) {
	before := netenv.Default
	netenv.Default = netenv.NewStore(time.Now)
	defer func() { netenv.Default = before; androidNetworkHealth.stop() }()
	oldSet, oldRefresh := forkSetAndroidDNSServers, forkRefreshAndroidDNSPolicy
	defer func() { forkSetAndroidDNSServers = oldSet; forkRefreshAndroidDNSPolicy = oldRefresh }()
	forkSetAndroidDNSServers = func(androidNetworkInfo) bool { return true }
	failure := errors.New("DNS refresh failed")
	forkRefreshAndroidDNSPolicy = func() error { return failure }
	err := SetAndroidNetworkInfo(`{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","transport":"wifi","ssid":"<unknown ssid>","bssid":"02:00:00:00:00:00"}]}`)
	if !errors.Is(err, failure) {
		t.Fatal(err)
	}
	got := netenv.Default.Current()
	if got.Type != "wifi" || got.Interface != "wlan0" || got.SSID != "" || got.BSSID != "" {
		t.Fatalf("report skipped before DNS failure: %+v", got)
	}
}

func TestForkSubnetPatchPreservesRules(t *testing.T) {
	dir := t.TempDir()
	base := filepath.Join(dir, "config.yaml")
	patch := filepath.Join(dir, "patch.yaml")
	if err := os.WriteFile(base, []byte("proxy-groups: []\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(patch, []byte("overwrite-proxy-groups: true\nproxy-groups:\n - name: Corp\n   type: subnet\n   default: ts-mihomo\n   matches:\n    - {ssid: GOLDWIND, proxy: DIRECT}\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	node, err := readMergedConfigRoot(base, patch, "")
	if err != nil {
		t.Fatal(err)
	}
	data, err := yaml.Marshal(node)
	if err != nil {
		t.Fatal(err)
	}
	if !strings.Contains(string(data), "default: ts-mihomo") || !strings.Contains(string(data), "ssid: GOLDWIND") {
		t.Fatal(string(data))
	}
}

func TestReviewA1SSIDAndInvalidFields(t *testing.T) {
	old := netenv.Default
	defer func() { netenv.Default = old }()
	for _, tc := range []struct{ ssid, want string }{{"474f4c4457494e44", "GOLDWIND"}, {"deadbeef", ""}, {strings.Repeat("b9fa", 20), ""}, {`"GOLDWIND"`, "GOLDWIND"}, {`""Corp""`, `"Corp"`}, {"GOLDWIND", "GOLDWIND"}} {
		t.Run(tc.ssid, func(t *testing.T) {
			netenv.Default = netenv.NewStore(time.Now)
			info := androidNetworkInfo{DefaultInterface: "wlan0", Interfaces: []androidNetworkInterface{{Name: "wlan0", Transport: "wifi", SSID: tc.ssid, BSSID: "invalid", Gateway: "not-an-ip", DHCPServer: "10.15.200.1"}}}
			if err := forkReportAndroidNetwork(info); err != nil {
				t.Fatalf("one invalid field rejected whole report: %v", err)
			}
			got := netenv.Default.Current()
			if got.SSID != tc.want || got.BSSID != "" || got.Gateway != "" || got.DHCPServer != "10.15.200.1" || got.Type != "wifi" {
				t.Fatalf("wrong sanitized report: %+v want SSID=%q", got, tc.want)
			}
		})
	}
}

func TestReviewA1InvalidSSIDDoesNotBlockDNSOrHealth(t *testing.T) {
	oldSet, oldRefresh, oldHealth := forkSetAndroidDNSServers, forkRefreshAndroidDNSPolicy, androidNetworkHealth
	defer func() {
		forkSetAndroidDNSServers = oldSet
		forkRefreshAndroidDNSPolicy = oldRefresh
		androidNetworkHealth = oldHealth
	}()
	androidNetworkHealth = &androidNetworkHealthMonitor{}
	set, refresh := false, false
	forkSetAndroidDNSServers = func(androidNetworkInfo) bool { set = true; return true }
	forkRefreshAndroidDNSPolicy = func() error { refresh = true; return nil }
	raw := `{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","transport":"wifi","ssid":"` + strings.Repeat("b9fa", 20) + `"}]}`
	_ = SetAndroidNetworkInfo(raw)
	if !set || !refresh || androidNetworkHealth.revision == 0 {
		t.Fatalf("subnet report blocked existing behavior: dns=%v refresh=%v health=%d", set, refresh, androidNetworkHealth.revision)
	}
}

func TestReviewA1NetenvErrorDoesNotBlockExistingUpdates(t *testing.T) {
	oldPublish, oldNotify, oldSet, oldRefresh, oldHealth := forkPublishNetwork, forkNotifyNetwork, forkSetAndroidDNSServers, forkRefreshAndroidDNSPolicy, androidNetworkHealth
	defer func() {
		forkPublishNetwork = oldPublish
		forkNotifyNetwork = oldNotify
		forkSetAndroidDNSServers = oldSet
		forkRefreshAndroidDNSPolicy = oldRefresh
		androidNetworkHealth = oldHealth
	}()
	failure := errors.New("report failure")
	calls := []string{}
	androidNetworkHealth = &androidNetworkHealthMonitor{}
	forkPublishNetwork = func(context.Context, netenv.Fields, string) error { return failure }
	forkNotifyNetwork = func(string) (int, int) { calls = append(calls, "notify"); return 0, 0 }
	forkSetAndroidDNSServers = func(androidNetworkInfo) bool { calls = append(calls, "dns"); return true }
	forkRefreshAndroidDNSPolicy = func() error { calls = append(calls, "refresh"); return nil }
	err := SetAndroidNetworkInfo(`{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","transport":"wifi"}]}`)
	if !errors.Is(err, failure) || strings.Join(calls, ",") != "dns,notify,refresh" || androidNetworkHealth.revision == 0 {
		t.Fatalf("failure blocked updates: %v %v health=%d", err, calls, androidNetworkHealth.revision)
	}
}
