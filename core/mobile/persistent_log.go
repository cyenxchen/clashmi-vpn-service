package clashmicore

import (
	"regexp"
	"strings"
	"sync"

	"github.com/metacubex/mihomo/log"
)

const persistentCoreLogMaxMessageBytes = 8 * 1024

var (
	persistentLogWriterMu sync.RWMutex
	persistentLogWriter   PersistentLogWriter
	persistentSecretRE    = regexp.MustCompile(`(?i)(auth-?key|secret|password|token)=([^\s,&}]+)`)
)

// PersistentLogWriter is implemented by the platform bridge. Mihomo keeps its
// own logging system; this subscriber only forwards a small diagnostic subset
// into the bridge's existing rotating file.
type PersistentLogWriter interface {
	Write(level string, message string)
}

func init() {
	subscription := log.Subscribe()
	go func() {
		for event := range subscription {
			if !shouldPersistCoreLog(event) {
				continue
			}
			persistentLogWriterMu.RLock()
			writer := persistentLogWriter
			persistentLogWriterMu.RUnlock()
			if writer == nil {
				continue
			}
			writePersistentCoreLog(writer, event.LogLevel.String(), sanitizePersistentCoreLog(event.Payload))
		}
	}()
}

// SetPersistentLogWriter enables the filtered persistent diagnostic stream.
func SetPersistentLogWriter(writer PersistentLogWriter) {
	persistentLogWriterMu.Lock()
	persistentLogWriter = writer
	persistentLogWriterMu.Unlock()
}

// ClearPersistentLogWriter releases the platform callback after native stop.
func ClearPersistentLogWriter() {
	persistentLogWriterMu.Lock()
	persistentLogWriter = nil
	persistentLogWriterMu.Unlock()
}

func shouldPersistCoreLog(event log.Event) bool {
	payload := event.Payload
	if strings.HasPrefix(payload, "[ClashMiCore]") {
		return true
	}
	if !strings.Contains(payload, "[Tailscale]") {
		return false
	}
	if event.LogLevel >= log.WARNING {
		return true
	}
	for _, marker := range []string{
		"network change",
		"monitor:",
		"netcheck:",
		"Handshake did not complete",
		"derp.Recv",
		"derphttp.Client",
		"health(",
		"link state:",
	} {
		if strings.Contains(payload, marker) {
			return true
		}
	}
	return strings.Contains(payload, "SystemDialer: finish") && !strings.Contains(payload, "err: <nil>")
}

func sanitizePersistentCoreLog(message string) string {
	message = strings.NewReplacer("\r", " ", "\n", " ").Replace(message)
	message = persistentSecretRE.ReplaceAllString(message, "$1=[REDACTED]")
	if len(message) > persistentCoreLogMaxMessageBytes {
		message = strings.ToValidUTF8(message[:persistentCoreLogMaxMessageBytes], "�") + " [truncated]"
	}
	return message
}

func writePersistentCoreLog(writer PersistentLogWriter, level string, message string) {
	// A Java/Kotlin callback can panic across the gomobile boundary. Contain it
	// here so logging can never terminate Mihomo's shared observable goroutine.
	defer func() {
		_ = recover()
	}()
	writer.Write(level, message)
}
