package redact

import (
	"strings"
	"testing"
)

var cases = map[string]string{
	"":                             "",
	"no links here":                "no links here",
	"https://panel.example.com":    "https://panel.example.com",
	"https://panel.example.com/":   "https://panel.example.com/",
	"https://panel.example.com/?a": "https://panel.example.com/***",
	`Get "https://panel.example.com/abcdef?token=1": dial tcp: i/o timeout`: `Get "https://panel.example.com/***": dial tcp: i/o timeout`,
	"https://user:pass@panel.example.com/sub":                               "https://panel.example.com/***",
	"https://user:pass@panel.example.com":                                   "https://panel.example.com",
	"see https://host.example/a/b/c, then retry":                            "see https://host.example/***, then retry",
	"https://dns.example.com/dns-query?token=abc":                           "https://dns.example.com/***",
	"tls://dot.example.com:853":                                             "tls://dot.example.com:853",
	"tg://resolve?domain=provider_bot":                                      "tg://resolve?domain=provider_bot",
	"content://com.android.providers/document/1":                            "content://com.android.providers/document/1",
	"mailto:support@example.com":                                            "mailto:support@example.com",
	"http://127.0.0.1:7890":                                                 "http://127.0.0.1:7890",
	"два https://a.b/c и https://d.e/f?g":                                   "два https://a.b/*** и https://d.e/***",
	"https://h/p%?token=SECRET":                                             "https://h/***",
	"https://h/p\x7f?token=SECRET":                                          "https://h/***",
	"https://[fe80::1]/p?token=SECRET":                                      "https://[fe80::1]/***",
	"HTTPS://Panel.Example.COM/sub?token=1":                                 "HTTPS://Panel.Example.COM/***",
	"https://пример.рф/sub?token=1":                                         "https://пример.рф/***",
	"line one https://a.b/c?d\nline two https://e.f/g?h":                    "line one https://a.b/***\nline two https://e.f/***",
	"https://h//": "https://h//",
}

func TestText(t *testing.T) {
	for input, expected := range cases {
		if got := Text(input); got != expected {
			t.Errorf("Text(%q) = %q, want %q", input, got, expected)
		}
	}
}

func TestTextIsIdempotent(t *testing.T) {
	for input := range cases {
		once := Text(input)

		if twice := Text(once); twice != once {
			t.Errorf("Text(Text(%q)) = %q, want %q", input, twice, once)
		}
	}
}

func TestTextKeepsNoSecretOnLongInput(t *testing.T) {
	long := "Get \"https://panel.example.com/" + strings.Repeat("a", 200000) + "?token=SECRET\": timeout"

	got := Text(long)

	if strings.Contains(got, "SECRET") || strings.Contains(got, "aaaa") {
		t.Fatalf("Text kept the secret part of a long url")
	}
}
