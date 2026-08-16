package config

var (
	defaultNameServers = []string{
		"1.0.0.1",
		"8.8.4.4",
		"9.9.9.10",
	}
	defaultFakeIPFilter = []string{
		"+.stun.*.*",
		"+.stun.*.*.*",
		"+.stun.*.*.*.*",
		"+.stun.*.*.*.*.*",

		"lens.l.google.com",

		"*.n.n.srv.nintendo.net",

		"+.stun.playstation.net",

		"xbox.*.*.microsoft.com",
		"*.*.xboxlive.com",

		"*.msftncsi.com",
		"*.msftconnecttest.com",

		"WORKGROUP",
	}
	defaultFakeIPRange = "28.0.0.0/8"
)
