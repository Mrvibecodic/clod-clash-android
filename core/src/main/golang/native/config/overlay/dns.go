package overlay

import (
	"github.com/metacubex/mihomo/common/orderedmap"
	"github.com/metacubex/mihomo/common/utils"
)

// Значение записи ядро читает строкой или списком строк одинаково, а пустую
// запись принять не может
func stringLists(entries map[string]any) map[string]any {
	result := make(map[string]any, len(entries))

	for key, value := range entries {
		if value == nil {
			continue
		}

		result[key] = value

		if list, err := utils.ToStringSlice(value); err == nil {
			result[key] = list
		}
	}

	return result
}

func policyLists(policy *orderedmap.OrderedMap[string, any]) map[string]any {
	entries := map[string]any{}

	if policy != nil {
		for pair := policy.Oldest(); pair != nil; pair = pair.Next() {
			entries[pair.Key] = pair.Value
		}
	}

	return stringLists(entries)
}

var dnsFields = []field{
	{group: GroupDNS, key: "dns.enable", template: "dns", value: func(s side) any { return s.dnsEnable }},
	{group: GroupDNS, key: "dns.prefer-h3", value: func(s side) any { return s.cfg.DNS.PreferH3 }},
	{group: GroupDNS, key: "dns.listen", value: func(s side) any { return s.cfg.DNS.Listen }},
	{group: GroupDNS, key: "dns.ipv6", value: func(s side) any { return s.cfg.DNS.IPv6 }},
	{group: GroupDNS, key: "dns.use-hosts", value: func(s side) any { return s.cfg.DNS.UseHosts }},
	{group: GroupDNS, key: "dns.enhanced-mode", value: func(s side) any { return s.cfg.DNS.EnhancedMode }},
	{group: GroupDNS, key: "dns.nameserver", value: func(s side) any { return s.cfg.DNS.NameServer }},
	{group: GroupDNS, key: "dns.fallback", value: func(s side) any { return s.cfg.DNS.Fallback }},
	{group: GroupDNS, key: "dns.default-nameserver", value: func(s side) any { return s.cfg.DNS.DefaultNameserver }},
	{group: GroupDNS, key: "dns.fake-ip-filter", value: func(s side) any { return s.cfg.DNS.FakeIPFilter }},
	{group: GroupDNS, key: "dns.fake-ip-filter-mode", value: func(s side) any { return s.cfg.DNS.FakeIPFilterMode }},
	{group: GroupDNS, key: "dns.fallback-filter.geoip", value: func(s side) any { return s.cfg.DNS.FallbackFilter.GeoIP }},
	{group: GroupDNS, key: "dns.fallback-filter.geoip-code", value: func(s side) any { return s.cfg.DNS.FallbackFilter.GeoIPCode }},
	{group: GroupDNS, key: "dns.fallback-filter.ipcidr", value: func(s side) any { return s.cfg.DNS.FallbackFilter.IPCIDR }},
	{group: GroupDNS, key: "dns.fallback-filter.domain", value: func(s side) any { return s.cfg.DNS.FallbackFilter.Domain }},
	{group: GroupDNS, key: "dns.nameserver-policy", value: func(s side) any { return policyLists(s.cfg.DNS.NameServerPolicy) }},
	{group: GroupDNS, key: "hosts", value: func(s side) any { return stringLists(s.cfg.Hosts) }},
}
