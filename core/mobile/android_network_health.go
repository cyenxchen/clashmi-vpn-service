package clashmicore

import (
	"slices"
	"sync"
	"time"

	P "github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
)

const (
	androidNetworkHealthDebounce = 2 * time.Second
	androidNetworkHealthWorkers  = 4
	// Mihomo's provider HealthCheck shares in-flight calls and caches their
	// result for one second. A failed call may therefore predate recovery.
	androidNetworkHealthRetryDelay = 1500 * time.Millisecond
)

var androidNetworkHealth = &androidNetworkHealthMonitor{}

type androidNetworkHealthMonitor struct {
	mu          sync.Mutex
	fingerprint string
	available   bool
	revision    uint64
	providers   []P.ProxyProvider
	run         *androidNetworkHealthRun
}

type androidNetworkHealthRun struct {
	wake chan struct{}
	stop chan struct{}
}

type androidNetworkHealthSnapshot struct {
	revision  uint64
	available bool
	providers []P.ProxyProvider
}

type androidNetworkHealthJob struct {
	revision uint64
	provider P.ProxyProvider
}

func (m *androidNetworkHealthMonitor) update(info androidNetworkInfo) {
	fingerprint, available := info.healthCheckFingerprint()
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.fingerprint == fingerprint {
		return
	}
	m.fingerprint = fingerprint
	m.available = available
	m.revision++
	m.wakeLocked()
}

func (m *androidNetworkHealthMonitor) setProviders(providers map[string]P.ProxyProvider) {
	// Copy from ApplyConfigHook instead of reading tunnel.Providers while a
	// controller config reload may replace its unsynchronized global map.
	names := make([]string, 0, len(providers))
	for name := range providers {
		names = append(names, name)
	}
	slices.Sort(names)
	snapshot := make([]P.ProxyProvider, 0, len(names))
	for _, name := range names {
		if provider := providers[name]; provider != nil {
			snapshot = append(snapshot, provider)
		}
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	m.providers = snapshot
	m.revision++
	m.wakeLocked()
}

func (m *androidNetworkHealthMonitor) start() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.run != nil {
		close(m.run.stop)
	}
	run := &androidNetworkHealthRun{wake: make(chan struct{}, 1), stop: make(chan struct{})}
	m.run = run
	m.revision++
	m.wakeLocked()
	go m.checkLoop(run)
}

func (m *androidNetworkHealthMonitor) stop() {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.run != nil {
		close(m.run.stop)
		m.run = nil
	}
	m.providers = nil
	m.revision++
	// HealthCheck has no caller cancellation API. Do not wait for its whole
	// proxy batch here: an already-entered call retains only the old provider,
	// and the retired worker cannot launch its next provider or retry. A new
	// core gets a separate worker and immutable provider snapshot.
}

func (m *androidNetworkHealthMonitor) wakeLocked() {
	if m.run != nil {
		select {
		case m.run.wake <- struct{}{}:
		default:
		}
	}
}

func (m *androidNetworkHealthMonitor) snapshot(run *androidNetworkHealthRun) (androidNetworkHealthSnapshot, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return androidNetworkHealthSnapshot{m.revision, m.available, m.providers}, m.run == run
}

func (m *androidNetworkHealthMonitor) current(run *androidNetworkHealthRun, revision uint64) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.run == run && m.available && m.revision == revision
}

func (m *androidNetworkHealthMonitor) checkLoop(run *androidNetworkHealthRun) {
	jobs := make(chan androidNetworkHealthJob)
	completed := make(chan P.ProxyProvider)
	for range androidNetworkHealthWorkers {
		go m.checkWorker(run, jobs, completed)
	}
	// Mihomo provider implementations are pointers. Keep each provider in at
	// most one worker, even if another network change arrives during its test.
	inFlight := make(map[P.ProxyProvider]bool)
	var queue []P.ProxyProvider
	var timer *time.Timer
	var due <-chan time.Time
	var pending androidNetworkHealthSnapshot
	stopTimer := func() {
		if timer != nil {
			timer.Stop()
		}
		due = nil
	}
	arm := func(delay time.Duration) {
		stopTimer()
		timer = time.NewTimer(delay)
		due = timer.C
	}
	defer stopTimer()
	for {
		var dispatch chan androidNetworkHealthJob
		var next androidNetworkHealthJob
		var nextIndex int
		for i, provider := range queue {
			if !inFlight[provider] {
				dispatch, next, nextIndex = jobs, androidNetworkHealthJob{pending.revision, provider}, i
				break
			}
		}
		select {
		case <-run.stop:
			return
		case dispatch <- next:
			inFlight[next.provider] = true
			queue = slices.Delete(queue, nextIndex, nextIndex+1)
		case provider := <-completed:
			delete(inFlight, provider)
		case <-run.wake:
			snapshot, active := m.snapshot(run)
			if !active {
				return
			}
			if snapshot.revision == pending.revision {
				continue
			}
			pending, queue = snapshot, nil
			stopTimer()
			if pending.available && len(pending.providers) > 0 {
				arm(androidNetworkHealthDebounce)
			}
		case <-due:
			due = nil
			if !m.current(run, pending.revision) {
				continue
			}
			queue = slices.Clone(pending.providers)
			log.Infoln("[ClashMiCore] Android network recovery health check providers=%d retry=false", len(queue))
		}
	}
}

func (m *androidNetworkHealthMonitor) checkWorker(run *androidNetworkHealthRun, jobs <-chan androidNetworkHealthJob, completed chan<- P.ProxyProvider) {
	for {
		select {
		case <-run.stop:
			return
		case job := <-jobs:
			m.checkProvider(run, job)
			select {
			case <-run.stop:
				return
			case completed <- job.provider:
			}
		}
	}
}

func (m *androidNetworkHealthMonitor) checkProvider(run *androidNetworkHealthRun, job androidNetworkHealthJob) {
	if !m.current(run, job.revision) {
		return
	}
	job.provider.HealthCheck()
	if !m.current(run, job.revision) || !providerHasFailedHealth(job.provider) {
		return
	}
	// A slow subscription must not delay another provider's recovery or its
	// one retry. Each worker respects the core's existing per-provider limits.
	timer := time.NewTimer(androidNetworkHealthRetryDelay)
	defer timer.Stop()
	select {
	case <-run.stop:
		return
	case <-timer.C:
	}
	if m.current(run, job.revision) {
		log.Infoln("[ClashMiCore] Android network recovery health check providers=1 retry=true")
		job.provider.HealthCheck()
	}
}

func providerHasFailedHealth(provider P.ProxyProvider) bool {
	for _, proxy := range provider.Proxies() {
		if !proxy.AliveForTestUrl(provider.HealthCheckURL()) {
			return true
		}
		// A group can register an additional test URL on a provider; refresh its
		// failed result as well as the provider's default URL.
		for _, state := range proxy.ExtraDelayHistories() {
			if !state.Alive {
				return true
			}
		}
	}
	return false
}
