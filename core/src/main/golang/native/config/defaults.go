package config

var (
	// clod: китайские резолверы (223.5.5.5 AliDNS, 119.29.29.29 DNSPod) убраны
	defaultNameServers = []string{
		"1.0.0.1",
		"8.8.4.4",
		"9.9.9.10",
	}
	defaultFakeIPFilter = []string{
		// Stun Services
		"+.stun.*.*",
		"+.stun.*.*.*",
		"+.stun.*.*.*.*",
		"+.stun.*.*.*.*.*",

		// Google Voices
		"lens.l.google.com",

		// Nintendo Switch STUN
		"*.n.n.srv.nintendo.net",

		// PlayStation STUN
		"+.stun.playstation.net",

		// XBox
		"xbox.*.*.microsoft.com",
		"*.*.xboxlive.com",

		// Microsoft Captive Portal
		"*.msftncsi.com",
		"*.msftconnecttest.com",

		// Windows Default LAN WorkGroup
		"WORKGROUP",
	}
	defaultFakeIPRange = "28.0.0.0/8"
)
