//go:build android

package clashmicore

import (
	"github.com/metacubex/mihomo/log"
	"tailscale.com/net/netns"
)

func setTailscaleSocketProtector(protector SocketProtector) {
	if protector == nil {
		netns.SetAndroidProtectFunc(nil)
		log.Warnln("[ClashMiCore] Tailscale Android socket protector cleared")
		return
	}
	netns.SetAndroidProtectFunc(func(fd int) error {
		return protectTailscaleSocketFD(protector, fd)
	})
	log.Infoln("[ClashMiCore] Tailscale Android socket protector installed")
}
