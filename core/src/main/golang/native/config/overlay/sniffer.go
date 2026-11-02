package overlay

func sniffPorts(protocol string) func(s side) any {
	return func(s side) any {
		entry, ok := s.cfg.Sniffer.Sniff[protocol]
		if !ok {
			return nil
		}

		return entry.Ports
	}
}

// Запись протокола заменяется целиком: без своего override-destination действует общий
func sniffOverride(protocol string) func(s side) any {
	return func(s side) any {
		entry, ok := s.cfg.Sniffer.Sniff[protocol]
		if !ok {
			return nil
		}

		if entry.OverrideDest != nil {
			return *entry.OverrideDest
		}

		return s.cfg.Sniffer.OverrideDest
	}
}

var sniffFields = []field{
	{group: GroupFeatures, key: "sniffer.sniff.HTTP.ports", path: "sniffer.sniff.HTTP", fold: true, value: sniffPorts("HTTP")},
	{group: GroupFeatures, key: "sniffer.sniff.HTTP.override-destination", path: "sniffer.sniff.HTTP", fold: true, value: sniffOverride("HTTP")},
	{group: GroupFeatures, key: "sniffer.sniff.TLS.ports", path: "sniffer.sniff.TLS", fold: true, value: sniffPorts("TLS")},
	{group: GroupFeatures, key: "sniffer.sniff.TLS.override-destination", path: "sniffer.sniff.TLS", fold: true, value: sniffOverride("TLS")},
	{group: GroupFeatures, key: "sniffer.sniff.QUIC.ports", path: "sniffer.sniff.QUIC", fold: true, value: sniffPorts("QUIC")},
	{group: GroupFeatures, key: "sniffer.sniff.QUIC.override-destination", path: "sniffer.sniff.QUIC", fold: true, value: sniffOverride("QUIC")},
}
