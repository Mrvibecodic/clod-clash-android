package config

import (
	"net"
	U "net/url"
	"strconv"

	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel"
)

func addressPort(parsed *U.URL) (uint16, bool) {
	value := parsed.Port()

	if value == "" {
		if parsed.Scheme == "http" {
			return 80, true
		}

		return 443, true
	}

	number, err := strconv.ParseUint(value, 10, 16)
	if err != nil {
		return 0, false
	}

	return uint16(number), true
}

// keptOutOfTunnel reports whether the rules of the loaded profile send the
// address to REJECT. The request is described the way the core itself
// describes an inner one, through SetRemoteAddress and with the same process
// name, so a rule sees here exactly what it would see during the request.
// A name is never resolved: as soon as a rule asks for an address, the order
// of the rules can no longer be reproduced faithfully, so there is no verdict
// and the caller keeps its usual path through the tunnel.
func keptOutOfTunnel(raw string) bool {
	parsed, err := U.Parse(raw)
	if err != nil || parsed.Hostname() == "" {
		return false
	}

	port, ok := addressPort(parsed)
	if !ok {
		return false
	}

	metadata := &C.Metadata{
		NetWork: C.TCP,
		Type:    C.INNER,
		DNSMode: C.DNSNormal,
		Process: C.MihomoName,
	}

	address := net.JoinHostPort(parsed.Hostname(), strconv.FormatUint(uint64(port), 10))
	if err := metadata.SetRemoteAddress(address); err != nil {
		return false
	}

	unresolved := false

	helper := C.RuleMatchHelper{
		ResolveIP:     func() { unresolved = true },
		FindProcess:   func() {},
		CheckPassRule: func(string) bool { return false },
	}

	for _, rule := range tunnel.Rules() {
		matched, adapter := rule.Match(metadata, helper)

		if unresolved {
			return false
		}

		if !matched {
			continue
		}

		proxy, known := tunnel.Proxies()[adapter]
		if !known {
			return false
		}

		return proxy.Type() == C.Reject || proxy.Type() == C.RejectDrop
	}

	return false
}
