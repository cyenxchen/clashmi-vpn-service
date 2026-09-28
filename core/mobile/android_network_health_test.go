package clashmicore

import (
	"strings"
	"sync/atomic"
	"testing"
	"testing/synctest"
	"time"

	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	P "github.com/metacubex/mihomo/constant/provider"
)

type recoveryTestProvider struct {
	P.ProxyProvider
	checks atomic.Int32
	alive  atomic.Bool
	check  func(int32)
}

func (p *recoveryTestProvider) HealthCheck() {
	count := p.checks.Add(1)
	if p.check != nil {
		p.check(count)
		return
	}
	p.alive.Store(true)
}

func (p *recoveryTestProvider) HealthCheckURL() string { return "https://health.invalid/" }

func (p *recoveryTestProvider) Proxies() []C.Proxy {
	return []C.Proxy{&recoveryTestProxy{alive: &p.alive}}
}

type recoveryTestProxy struct {
	C.Proxy
	alive *atomic.Bool
}

func (p *recoveryTestProxy) AliveForTestUrl(string) bool                  { return p.alive.Load() }
func (p *recoveryTestProxy) ExtraDelayHistories() map[string]C.ProxyState { return nil }

const recoveredAndroidNetwork = `{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","index":12,"mtu":1500,"networkHandle":101,"validated":true,"addresses":["192.0.2.2/24"],"dnsServers":["192.0.2.1"]}]}`
const offlineAndroidNetwork = `{"defaultInterface":"","interfaces":[]}`

func TestAndroidNetworkRecoveryRefreshesProviderHealth(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		provider := &recoveryTestProvider{}
		startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"test": provider})

		if err := SetAndroidNetworkInfo(offlineAndroidNetwork); err != nil {
			t.Fatal(err)
		}
		if err := SetAndroidNetworkInfo(recoveredAndroidNetwork); err != nil {
			t.Fatal(err)
		}
		time.Sleep(5 * time.Second)
		synctest.Wait()
		if !provider.alive.Load() {
			t.Fatalf("provider health stayed false after Android network recovery; health checks = %d", provider.checks.Load())
		}
		assertRecoveryChecks(t, provider, 1)
	})
}

func startRecoveryTestMonitor(t *testing.T, providers map[string]P.ProxyProvider) *androidNetworkHealthMonitor {
	t.Helper()
	previous := androidNetworkHealth
	monitor := &androidNetworkHealthMonitor{}
	androidNetworkHealth = monitor
	t.Cleanup(func() {
		monitor.stop()
		androidNetworkHealth = previous
	})
	// This is the same configuration hook and activation used by Start, without
	// opening a real TUN device in a host unit test.
	syncRuntimeConfigStateFromAppliedConfig(&config.Config{Providers: providers})
	monitor.start()
	return monitor
}

func updateRecoveryTestNetwork(t *testing.T, monitor *androidNetworkHealthMonitor, raw string) {
	t.Helper()
	info, err := parseAndroidNetworkInfo(raw)
	if err != nil {
		t.Fatal(err)
	}
	monitor.update(info)
	synctest.Wait()
}

func assertRecoveryChecks(t *testing.T, provider *recoveryTestProvider, want int32) {
	t.Helper()
	synctest.Wait()
	if got := provider.checks.Load(); got != want {
		t.Fatalf("health check count = %d, want %d", got, want)
	}
}

func TestAndroidNetworkRecoveryDeduplicatesEquivalentSnapshots(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		provider := &recoveryTestProvider{}
		monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"test": provider})
		first := `{"defaultInterface":"wlan0","interfaces":[{"name":"wlan0","index":12,"mtu":1500,"addresses":["192.0.2.2/24","2001:db8::2/64"],"dnsServers":["192.0.2.1","2001:db8::1"]},{"name":"rmnet0","addresses":["198.51.100.2/24"]}]}`
		equivalent := `{"interfaces":[{"addresses":["198.51.100.2/24"],"name":"rmnet0"},{"dnsServers":["2001:db8:0::1","192.0.2.1","192.0.2.1"],"addresses":["2001:db8:0::2/64","192.0.2.2/24"],"mtu":1500,"index":12,"name":"wlan0"}],"defaultInterface":"wlan0"}`
		updateRecoveryTestNetwork(t, monitor, first)
		time.Sleep(time.Second)
		for range 20 {
			updateRecoveryTestNetwork(t, monitor, equivalent)
		}
		// Equivalent callbacks must not postpone the original two-second check.
		time.Sleep(time.Second)
		assertRecoveryChecks(t, provider, 1)
		for range 20 {
			updateRecoveryTestNetwork(t, monitor, equivalent)
		}
		time.Sleep(time.Minute)
		assertRecoveryChecks(t, provider, 1)
	})
}

func TestAndroidNetworkRecoveryDetectsPhysicalNetworkChanges(t *testing.T) {
	for name, replacement := range map[string][2]string{
		"interface":  {"wlan0", "rmnet0"},
		"index":      {`"index":12`, `"index":13`},
		"mtu":        {`"mtu":1500`, `"mtu":1400`},
		"address":    {"192.0.2.2/24", "192.0.2.3/24"},
		"dns":        {"192.0.2.1", "192.0.2.53"},
		"network":    {`"networkHandle":101`, `"networkHandle":102`},
		"validation": {`"validated":true`, `"validated":false`},
	} {
		t.Run(name, func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				provider := &recoveryTestProvider{}
				monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"test": provider})
				updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
				time.Sleep(2 * time.Second)
				assertRecoveryChecks(t, provider, 1)
				changed := strings.ReplaceAll(recoveredAndroidNetwork, replacement[0], replacement[1])
				updateRecoveryTestNetwork(t, monitor, changed)
				time.Sleep(2 * time.Second)
				assertRecoveryChecks(t, provider, 2)
				updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
				time.Sleep(2 * time.Second)
				assertRecoveryChecks(t, provider, 3)
			})
		})
	}
}

func TestAndroidNetworkRecoveryCoalescesBurstAndCancelsOfflineWork(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		provider := &recoveryTestProvider{}
		monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"test": provider})
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		time.Sleep(time.Second)
		updateRecoveryTestNetwork(t, monitor, strings.ReplaceAll(recoveredAndroidNetwork, "192.0.2.2/24", "192.0.2.3/24"))
		time.Sleep(time.Second)
		assertRecoveryChecks(t, provider, 0)
		updateRecoveryTestNetwork(t, monitor, offlineAndroidNetwork)
		time.Sleep(10 * time.Second)
		assertRecoveryChecks(t, provider, 0)
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, provider, 1)
	})
}

func TestAndroidNetworkRecoveryRetriesFailedProviderOnlyOnce(t *testing.T) {
	for _, recovers := range []bool{true, false} {
		t.Run(map[bool]string{true: "recovers", false: "still_offline"}[recovers], func(t *testing.T) {
			synctest.Test(t, func(t *testing.T) {
				failed, healthy := &recoveryTestProvider{}, &recoveryTestProvider{}
				failed.check = func(count int32) { failed.alive.Store(recovers && count > 1) }
				monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"failed": failed, "healthy": healthy})
				updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
				time.Sleep(2 * time.Second)
				assertRecoveryChecks(t, failed, 1)
				time.Sleep(time.Second)
				assertRecoveryChecks(t, failed, 1)
				time.Sleep(time.Second)
				assertRecoveryChecks(t, failed, 2)
				time.Sleep(time.Minute)
				assertRecoveryChecks(t, failed, 2)
				assertRecoveryChecks(t, healthy, 1)
			})
		})
	}
}

func TestAndroidNetworkRecoverySlowProviderDoesNotDelayOthersOrTheirRetry(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		blocked, failed, healthy := &recoveryTestProvider{}, &recoveryTestProvider{}, &recoveryTestProvider{}
		release := make(chan struct{})
		defer close(release)
		blocked.check = func(int32) { <-release }
		failed.check = func(count int32) { failed.alive.Store(count > 1) }
		monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"a-slow": blocked, "b-failed": failed, "c-healthy": healthy})
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, blocked, 1)
		assertRecoveryChecks(t, healthy, 1)
		assertRecoveryChecks(t, failed, 1)
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, failed, 2)
		assertRecoveryChecks(t, blocked, 1)
		updateRecoveryTestNetwork(t, monitor, strings.ReplaceAll(recoveredAndroidNetwork, "192.0.2.2/24", "192.0.2.3/24"))
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, healthy, 2)
		assertRecoveryChecks(t, failed, 3)
		assertRecoveryChecks(t, blocked, 1)
	})
}

func TestAndroidNetworkRecoveryStopDoesNotWaitForOldChecksOrReuseTheirProviders(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		blocked, oldRemainder, replacement := &recoveryTestProvider{}, &recoveryTestProvider{}, &recoveryTestProvider{}
		release := make(chan struct{})
		blocked.check = func(int32) { <-release }
		blocked2 := &recoveryTestProvider{check: blocked.check}
		blocked3 := &recoveryTestProvider{check: blocked.check}
		blocked4 := &recoveryTestProvider{check: blocked.check}
		monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{
			"a-blocked": blocked, "b-blocked": blocked2, "c-blocked": blocked3,
			"d-blocked": blocked4, "e-old": oldRemainder,
		})
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, blocked, 1)
		assertRecoveryChecks(t, blocked2, 1)
		assertRecoveryChecks(t, blocked3, 1)
		assertRecoveryChecks(t, blocked4, 1)
		assertRecoveryChecks(t, oldRemainder, 0)
		// An in-flight HealthCheck has no cancellation API. Stop must invalidate
		// this worker without waiting for every proxy's configured timeout.
		monitor.stop()
		monitor.setProviders(map[string]P.ProxyProvider{"new": replacement})
		monitor.start()
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, replacement, 1)
		close(release)
		time.Sleep(time.Minute)
		assertRecoveryChecks(t, oldRemainder, 0)
		assertRecoveryChecks(t, blocked, 1)
		assertRecoveryChecks(t, replacement, 1)
	})
}

func TestAndroidNetworkRecoveryStopAndConfigReloadInvalidatePendingChecks(t *testing.T) {
	synctest.Test(t, func(t *testing.T) {
		old, replacement := &recoveryTestProvider{}, &recoveryTestProvider{}
		monitor := startRecoveryTestMonitor(t, map[string]P.ProxyProvider{"old": old})
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		time.Sleep(time.Second)
		syncRuntimeConfigStateFromAppliedConfig(&config.Config{Providers: map[string]P.ProxyProvider{"new": replacement}})
		time.Sleep(2 * time.Second)
		assertRecoveryChecks(t, old, 0)
		assertRecoveryChecks(t, replacement, 1)
		updateRecoveryTestNetwork(t, monitor, offlineAndroidNetwork)
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		// Exercise the public Stop path even though this test has no real TUN.
		Stop()
		time.Sleep(time.Minute)
		assertRecoveryChecks(t, replacement, 1)
		updateRecoveryTestNetwork(t, monitor, recoveredAndroidNetwork)
		time.Sleep(time.Minute)
		assertRecoveryChecks(t, replacement, 1)
	})
}
