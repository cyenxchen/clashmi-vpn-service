package clashmicore

import (
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"syscall"

	"github.com/metacubex/mihomo/adapter/outbound"
	"github.com/metacubex/mihomo/component/dialer"
	"github.com/metacubex/mihomo/config"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/hub"
	"github.com/metacubex/mihomo/hub/executor"
	"github.com/metacubex/mihomo/listener"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
	"gopkg.in/yaml.v3"
)

var (
	mu       sync.Mutex
	running  bool
	lastHome string
)

type SocketProtector interface {
	Protect(fd int64) bool
}

const androidTunMTU = 4064
const defaultAndroidTunStack = "system"
const androidTunFakeIPRange = "172.19.0.1/16"
const androidTunIPv6Address = "fdfe:dcbe:9876::1/126"

func SetSocketProtector(protector SocketProtector) {
	mu.Lock()
	defer mu.Unlock()

	if protector == nil {
		dialer.DefaultSocketHook = nil
		setTailscaleSocketProtector(nil)
		log.Warnln("[ClashMiCore] Android socket protector cleared")
		return
	}
	dialer.DefaultSocketHook = func(network, address string, conn syscall.RawConn) error {
		return protectSocket(protector, network, address, conn)
	}
	setTailscaleSocketProtector(protector)
	log.Infoln("[ClashMiCore] Android socket protector installed for mihomo dialer and Tailscale netns")
}

func SetAndroidNetworkInfo(raw string) error {
	if err := outbound.SetAndroidTailscaleNetworkInfo(raw); err != nil {
		log.Warnln("[ClashMiCore] update Android network info failed: %v", err)
		return err
	}
	log.Infoln("[ClashMiCore] Android network info updated")
	return nil
}

func Start(configFile string, patchFile string, finalPatchFile string, homeDir string, tunFd int, externalController string, secret string) error {
	mu.Lock()
	defer mu.Unlock()

	if tunFd <= 0 {
		return errors.New("invalid tun fd")
	}
	ownsTunFd := true
	closeOwnedTunFd := func(reason string) {
		if !ownsTunFd {
			return
		}
		ownsTunFd = false
		if closeErr := syscall.Close(tunFd); closeErr != nil {
			log.Warnln("[ClashMiCore] close tun fd failed reason=%s fd=%d error=%v", reason, tunFd, closeErr)
			return
		}
		log.Warnln("[ClashMiCore] closed tun fd reason=%s fd=%d", reason, tunFd)
	}
	if configFile == "" {
		closeOwnedTunFd("empty config file")
		return errors.New("empty config file")
	}
	if homeDir == "" {
		homeDir = filepath.Dir(configFile)
	}
	if running {
		shutdownLocked()
	}
	if dialer.DefaultSocketHook == nil {
		log.Warnln("[ClashMiCore] Android socket protector is not installed; outbound sockets may route back into VPN")
	}

	log.Infoln("[ClashMiCore] start config=%s patch=%s finalPatch=%s home=%s fd=%d controller=%s", configFile, patchFile, finalPatchFile, homeDir, tunFd, externalController)
	configBytes, err := buildRuntimeConfig(configFile, patchFile, finalPatchFile, tunFd)
	if err != nil {
		closeOwnedTunFd("build runtime config failed")
		return err
	}

	if err = os.Setenv("SAFE_PATHS", homeDir); err != nil {
		closeOwnedTunFd("set safe paths failed")
		return err
	}
	if err = os.Setenv("SKIP_SAFE_PATH_CHECK", "true"); err != nil {
		closeOwnedTunFd("set skip safe path check failed")
		return err
	}

	C.SetHomeDir(homeDir)
	C.SetConfig(configFile)
	if err = config.Init(C.Path.HomeDir()); err != nil {
		closeOwnedTunFd("init config dir failed")
		return fmt.Errorf("init mihomo config dir: %w", err)
	}

	options := []hub.Option{}
	if externalController != "" {
		options = append(options, hub.WithExternalController(externalController))
	}
	if secret != "" {
		options = append(options, hub.WithSecret(secret))
	}
	ownsTunFd = false
	if err = hub.Parse(configBytes, options...); err != nil {
		executor.Shutdown()
		return fmt.Errorf("parse/apply mihomo config: %w", err)
	}
	tunConf := listener.GetTunConf()
	if !tunConf.Enable {
		executor.Shutdown()
		return fmt.Errorf("mihomo TUN listener did not start (stack=%s fd=%d); make sure the Android core supports the requested stack", tunConf.Stack.String(), tunFd)
	}

	statistic.DefaultManager.ResetStatistic()
	running = true
	lastHome = homeDir
	log.Infoln("[ClashMiCore] started stack=%s fd=%d address=%v", tunConf.Stack.String(), tunConf.FileDescriptor, tunConf.Inet4Address)
	return nil
}

func Stop() {
	mu.Lock()
	defer mu.Unlock()
	shutdownLocked()
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

func Traffic() string {
	up, down := statistic.DefaultManager.Now()
	body, _ := json.Marshal(map[string]int64{
		"up":   up,
		"down": down,
	})
	return string(body)
}

func Connections(withConnectionsList bool) string {
	snapshot := statistic.DefaultManager.Snapshot()
	if !withConnectionsList {
		snapshot.Connections = nil
	}
	body, err := json.Marshal(snapshot)
	if err != nil {
		return `{"uploadTotal":0,"downloadTotal":0,"memory":0,"connections":[]}`
	}
	return string(body)
}

func TunInfo() string {
	tunConf := listener.GetTunConf()
	body, err := json.Marshal(map[string]any{
		"enable":         tunConf.Enable,
		"stack":          tunConf.Stack.String(),
		"fileDescriptor": tunConf.FileDescriptor,
		"inet4Address":   fmt.Sprint(tunConf.Inet4Address),
		"mtu":            tunConf.MTU,
	})
	if err != nil {
		return `{"enable":false}`
	}
	return string(body)
}

func HomeDir() string {
	mu.Lock()
	defer mu.Unlock()
	return lastHome
}

func shutdownLocked() {
	if !running {
		return
	}
	log.Warnln("[ClashMiCore] stopping")
	executor.Shutdown()
	statistic.DefaultManager.ResetStatistic()
	running = false
	log.Warnln("[ClashMiCore] stopped")
}

func protectSocket(protector SocketProtector, network string, address string, conn syscall.RawConn) error {
	var protectErr error
	if err := conn.Control(func(fd uintptr) {
		protectErr = protectSocketFD(protector, fd, network, address)
	}); err != nil {
		return fmt.Errorf("protect socket control failed network=%s address=%s: %w", network, address, err)
	}
	return protectErr
}

func protectSocketFD(protector SocketProtector, fd uintptr, network string, address string) error {
	if !protector.Protect(int64(fd)) {
		protectErr := fmt.Errorf("VpnService.protect returned false for fd=%d network=%s address=%s", fd, network, address)
		log.Warnln("[ClashMiCore] %v", protectErr)
		return protectErr
	}
	return nil
}

func protectTailscaleSocketFD(protector SocketProtector, fd int) error {
	protectErr := protectSocketFD(protector, uintptr(fd), "tailscale-netns", "")
	if protectErr != nil {
		return fmt.Errorf("protect Tailscale socket: %w", protectErr)
	}
	return nil
}

func buildRuntimeConfig(configFile string, patchFile string, finalPatchFile string, tunFd int) ([]byte, error) {
	root, err := readYamlMapping(configFile, true)
	if err != nil {
		return nil, fmt.Errorf("read config %s: %w", configFile, err)
	}
	if patchFile != "" {
		patch, patchErr := readYamlMapping(patchFile, false)
		if patchErr != nil {
			return nil, fmt.Errorf("read patch %s: %w", patchFile, patchErr)
		}
		if patch != nil {
			mergeMapping(root, patch)
		}
	}
	if finalPatchFile != "" {
		patch, patchErr := readYamlMapping(finalPatchFile, false)
		if patchErr != nil {
			return nil, fmt.Errorf("read final patch %s: %w", finalPatchFile, patchErr)
		}
		if patch != nil {
			mergeMapping(root, patch)
		}
	}

	tun := ensureMapping(root, "tun")
	setBool(tun, "enable", true)
	setInt(tun, "file-descriptor", tunFd)
	setBool(tun, "auto-route", false)
	setBool(tun, "auto-detect-interface", false)
	setInt(tun, "mtu", androidTunMTU)
	if findValue(tun, "stack") == nil {
		setScalar(tun, "stack", defaultAndroidTunStack)
	}
	if findValue(tun, "dns-hijack") == nil {
		setSequence(tun, "dns-hijack", []string{"0.0.0.0:53"})
	}
	if scalarBool(findValue(root, "ipv6")) && findValue(tun, "inet6-address") == nil {
		setSequence(tun, "inet6-address", []string{androidTunIPv6Address})
	}

	dns := ensureMapping(root, "dns")
	setScalar(dns, "fake-ip-range", androidTunFakeIPRange)

	out, err := yaml.Marshal(root)
	if err != nil {
		return nil, err
	}
	return out, nil
}

func scalarBool(node *yaml.Node) bool {
	if node == nil || node.Kind != yaml.ScalarNode {
		return false
	}
	return node.Value == "true"
}

func readYamlMapping(path string, required bool) (*yaml.Node, error) {
	info, err := os.Stat(path)
	if err != nil {
		if required || !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
		return nil, nil
	}
	if info.IsDir() {
		return nil, fmt.Errorf("path is directory")
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if len(data) == 0 && !required {
		return nil, nil
	}
	var doc yaml.Node
	if err = yaml.Unmarshal(data, &doc); err != nil {
		return nil, err
	}
	if len(doc.Content) == 0 || doc.Content[0].Kind == 0 {
		if required {
			return nil, errors.New("empty yaml")
		}
		return nil, nil
	}
	node := doc.Content[0]
	if node.Kind != yaml.MappingNode {
		return nil, fmt.Errorf("root is not a mapping")
	}
	return node, nil
}

func mergeMapping(dst *yaml.Node, patch *yaml.Node) {
	for i := 0; i+1 < len(patch.Content); i += 2 {
		key := patch.Content[i]
		value := patch.Content[i+1]
		if shouldSkipPatchKey(key.Value) {
			continue
		}
		current := findValue(dst, key.Value)
		if current != nil && current.Kind == yaml.MappingNode && value.Kind == yaml.MappingNode {
			mergeMapping(current, value)
			continue
		}
		setNode(dst, key.Value, cloneNode(value))
	}
}

func shouldSkipPatchKey(key string) bool {
	switch key {
	case "overwrite-rule-providers", "overwrite-rules", "overwrite-sub-rules", "overwrite-proxy-groups", "overwrite-hosts", "extension":
		return true
	default:
		return false
	}
}

func ensureMapping(root *yaml.Node, key string) *yaml.Node {
	if node := findValue(root, key); node != nil && node.Kind == yaml.MappingNode {
		return node
	}
	node := &yaml.Node{Kind: yaml.MappingNode}
	setNode(root, key, node)
	return node
}

func setScalar(root *yaml.Node, key string, value string) {
	setNode(root, key, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: value})
}

func setBool(root *yaml.Node, key string, value bool) {
	setNode(root, key, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!bool", Value: fmt.Sprintf("%t", value)})
}

func setInt(root *yaml.Node, key string, value int) {
	setNode(root, key, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!int", Value: fmt.Sprintf("%d", value)})
}

func setSequence(root *yaml.Node, key string, values []string) {
	node := &yaml.Node{Kind: yaml.SequenceNode}
	for _, value := range values {
		node.Content = append(node.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: value})
	}
	setNode(root, key, node)
}

func setNode(root *yaml.Node, key string, value *yaml.Node) {
	for i := 0; i+1 < len(root.Content); i += 2 {
		if root.Content[i].Value == key {
			root.Content[i+1] = value
			return
		}
	}
	root.Content = append(root.Content, &yaml.Node{Kind: yaml.ScalarNode, Tag: "!!str", Value: key}, value)
}

func findValue(root *yaml.Node, key string) *yaml.Node {
	for i := 0; i+1 < len(root.Content); i += 2 {
		if root.Content[i].Value == key {
			return root.Content[i+1]
		}
	}
	return nil
}

func cloneNode(node *yaml.Node) *yaml.Node {
	if node == nil {
		return nil
	}
	clone := *node
	if len(node.Content) > 0 {
		clone.Content = make([]*yaml.Node, len(node.Content))
		for i, child := range node.Content {
			clone.Content[i] = cloneNode(child)
		}
	}
	return &clone
}
