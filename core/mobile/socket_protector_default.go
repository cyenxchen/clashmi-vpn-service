//go:build !android

package clashmicore

func setTailscaleSocketProtector(SocketProtector) {}

func setTailscaleAndroidDNSServers(androidNetworkInfo) bool { return false }

func setTailscaleControlHTTPProxy(string) {}

func androidPhysicalDNSServers() []string { return nil }
