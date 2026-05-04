package clashmicore

import (
	"errors"
	"os"
	"path/filepath"
	"syscall"
	"testing"

	"github.com/metacubex/mihomo/component/dialer"
	"gopkg.in/yaml.v3"
)

func TestBuildRuntimeConfigDefaultsToSystemTunStack(t *testing.T) {
	configFile := writeTempFile(t, "config.yaml", "mixed-port: 7890\nipv6: true\n")

	out, err := buildRuntimeConfig(configFile, "", "", 123)
	if err != nil {
		t.Fatal(err)
	}

	tun := readTunMapping(t, out)
	assertScalar(t, tun, "enable", "true")
	assertScalar(t, tun, "file-descriptor", "123")
	assertScalar(t, tun, "stack", defaultAndroidTunStack)
	assertScalar(t, tun, "auto-route", "false")
	assertScalar(t, tun, "auto-detect-interface", "false")
	assertSequence(t, tun, "inet6-address", []string{androidTunIPv6Address})

	dns := readMapping(t, out, "dns")
	assertScalar(t, dns, "fake-ip-range", androidTunFakeIPRange)
}

func TestBuildRuntimeConfigDoesNotForceIPv6WhenDisabled(t *testing.T) {
	configFile := writeTempFile(t, "config.yaml", "mixed-port: 7890\nipv6: false\n")

	out, err := buildRuntimeConfig(configFile, "", "", 321)
	if err != nil {
		t.Fatal(err)
	}

	tun := readTunMapping(t, out)
	if value := findValue(tun, "inet6-address"); value != nil {
		t.Fatalf("inet6-address = %v, want nil when ipv6 is disabled", value.Value)
	}
}

func TestBuildRuntimeConfigPreservesRequestedTunStack(t *testing.T) {
	configFile := writeTempFile(t, "config.yaml", "mixed-port: 7890\ndns:\n  fake-ip-range: 198.18.0.8/16\n")
	patchFile := writeTempFile(t, "patch.yaml", "tun:\n  stack: gvisor\n")

	out, err := buildRuntimeConfig(configFile, "", patchFile, 456)
	if err != nil {
		t.Fatal(err)
	}

	tun := readTunMapping(t, out)
	assertScalar(t, tun, "stack", "gvisor")
	assertScalar(t, tun, "file-descriptor", "456")

	dns := readMapping(t, out, "dns")
	assertScalar(t, dns, "fake-ip-range", androidTunFakeIPRange)
}

func TestProtectSocketUsesRawFd(t *testing.T) {
	protector := &recordingProtector{ok: true}
	conn := fakeRawConn{fd: 42}

	if err := protectSocket(protector, "tcp", "example.com:443", conn); err != nil {
		t.Fatal(err)
	}
	if protector.fd != 42 {
		t.Fatalf("protected fd = %d, want 42", protector.fd)
	}
}

func TestSetSocketProtectorInstallsDialerHook(t *testing.T) {
	oldHook := dialer.DefaultSocketHook
	t.Cleanup(func() {
		dialer.DefaultSocketHook = oldHook
	})

	protector := &recordingProtector{ok: true}
	SetSocketProtector(protector)

	if dialer.DefaultSocketHook == nil {
		t.Fatal("expected mihomo dialer socket hook to be installed")
	}
	if err := dialer.DefaultSocketHook("tcp", "example.com:443", fakeRawConn{fd: 99}); err != nil {
		t.Fatal(err)
	}
	if protector.fd != 99 {
		t.Fatalf("protected fd = %d, want 99", protector.fd)
	}

	SetSocketProtector(nil)
	if dialer.DefaultSocketHook != nil {
		t.Fatal("expected mihomo dialer socket hook to be cleared")
	}
}

func TestProtectTailscaleSocketUsesRawFd(t *testing.T) {
	protector := &recordingProtector{ok: true}

	if err := protectTailscaleSocketFD(protector, 55); err != nil {
		t.Fatal(err)
	}
	if protector.fd != 55 {
		t.Fatalf("protected fd = %d, want 55", protector.fd)
	}
}

func TestProtectSocketReportsFalseResult(t *testing.T) {
	protector := &recordingProtector{ok: false}

	err := protectSocket(protector, "tcp", "example.com:443", fakeRawConn{fd: 7})
	if err == nil {
		t.Fatal("expected protect error")
	}
}

func TestProtectSocketReportsControlError(t *testing.T) {
	wantErr := errors.New("control failed")
	err := protectSocket(&recordingProtector{ok: true}, "udp", "1.1.1.1:53", fakeRawConn{err: wantErr})
	if !errors.Is(err, wantErr) {
		t.Fatalf("error = %v, want wrapping %v", err, wantErr)
	}
}

func writeTempFile(t *testing.T, name string, content string) string {
	t.Helper()
	path := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return path
}

func readTunMapping(t *testing.T, data []byte) *yaml.Node {
	return readMapping(t, data, "tun")
}

func readMapping(t *testing.T, data []byte, key string) *yaml.Node {
	t.Helper()
	var doc yaml.Node
	if err := yaml.Unmarshal(data, &doc); err != nil {
		t.Fatal(err)
	}
	root := doc.Content[0]
	mapping := findValue(root, key)
	if mapping == nil || mapping.Kind != yaml.MappingNode {
		t.Fatalf("%s mapping missing: %s", key, string(data))
	}
	return mapping
}

func assertScalar(t *testing.T, root *yaml.Node, key string, want string) {
	t.Helper()
	value := findValue(root, key)
	if value == nil {
		t.Fatalf("missing key %q", key)
	}
	if value.Value != want {
		t.Fatalf("%s = %q, want %q", key, value.Value, want)
	}
}

type recordingProtector struct {
	fd int64
	ok bool
}

func (p *recordingProtector) Protect(fd int64) bool {
	p.fd = fd
	return p.ok
}

type fakeRawConn struct {
	fd  uintptr
	err error
}

func (c fakeRawConn) Control(fn func(fd uintptr)) error {
	if c.err != nil {
		return c.err
	}
	fn(c.fd)
	return nil
}

func (c fakeRawConn) Read(func(fd uintptr) bool) error {
	return syscall.ENOSYS
}

func (c fakeRawConn) Write(func(fd uintptr) bool) error {
	return syscall.ENOSYS
}

func assertSequence(t *testing.T, root *yaml.Node, key string, want []string) {
	t.Helper()
	value := findValue(root, key)
	if value == nil {
		t.Fatalf("missing key %q", key)
	}
	if value.Kind != yaml.SequenceNode {
		t.Fatalf("%s kind = %v, want sequence", key, value.Kind)
	}
	if len(value.Content) != len(want) {
		t.Fatalf("%s length = %d, want %d", key, len(value.Content), len(want))
	}
	for i, item := range value.Content {
		if item.Value != want[i] {
			t.Fatalf("%s[%d] = %q, want %q", key, i, item.Value, want[i])
		}
	}
}
