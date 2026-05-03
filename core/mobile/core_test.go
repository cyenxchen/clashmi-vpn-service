package clashmicore

import (
	"os"
	"path/filepath"
	"testing"

	"gopkg.in/yaml.v3"
)

func TestBuildRuntimeConfigDefaultsToSystemTunStack(t *testing.T) {
	configFile := writeTempFile(t, "config.yaml", "mixed-port: 7890\n")

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

	dns := readMapping(t, out, "dns")
	assertScalar(t, dns, "fake-ip-range", androidTunFakeIPRange)
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
