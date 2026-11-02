package overlay

import (
	"reflect"
	"testing"
)

func TestDifferencesSniffer(t *testing.T) {
	runDifferences(t, []differencesCase{
		{
			name:     "порты числами и строками равны",
			template: "sniffer:\n  enable: true\n  sniff:\n    HTTP:\n      ports: [80, 8080-8880]\n",
			persist:  `{"sniffer":{"enable":true,"sniff":{"HTTP":{"ports":["80","8080-8880"]}}}}`,
			want:     map[string][]string{},
		},
		{
			name:     "протокола нет в шаблоне — строки нет",
			template: "sniffer:\n  sniff:\n    TLS:\n      ports: [443]\n",
			persist:  `{"sniffer":{"sniff":{"QUIC":{"ports":["443"]}}}}`,
			want:     map[string][]string{},
		},
		{
			name:     "имя протокола в шаблоне без учёта регистра",
			template: "sniffer:\n  sniff:\n    tls:\n      ports: [443]\n",
			persist:  `{"sniffer":{"sniff":{"TLS":{"ports":["8443"]}}}}`,
			want:     map[string][]string{GroupFeatures: {"sniffer.sniff.TLS.ports"}},
		},
		{
			name:     "своя запись протокола снимает подмену адреса из шаблона",
			template: "sniffer:\n  override-destination: false\n  sniff:\n    TLS:\n      ports: [443]\n      override-destination: true\n",
			persist:  `{"sniffer":{"sniff":{"TLS":{"ports":["443"]}}}}`,
			want:     map[string][]string{GroupFeatures: {"sniffer.sniff.TLS.override-destination"}},
		},
		{
			name:     "общая подмена своя — после снятия записи протокола она же",
			template: "sniffer:\n  sniff:\n    TLS:\n      ports: [443]\n",
			persist:  `{"sniffer":{"override-destination":false,"sniff":{"TLS":{"ports":["8443"]}}}}`,
			want:     map[string][]string{GroupFeatures: {"sniffer.sniff.TLS.ports"}},
		},
		{
			name:     "без своей подмены у протокола действует общая",
			template: "sniffer:\n  override-destination: false\n  sniff:\n    TLS:\n      ports: [443]\n",
			persist:  `{"sniffer":{"sniff":{"TLS":{"ports":["443"],"override-destination":false}}}}`,
			want:     map[string][]string{},
		},
	})
}

func TestDifferencesSnifferResetsTheWholeEntry(t *testing.T) {
	values, err := Differences(
		[]byte("sniffer:\n  sniff:\n    TLS:\n      ports: [443]\n      override-destination: true\n"),
		`{"sniffer":{"sniff":{"TLS":{"ports":["8443"]}}}}`,
		Mode{},
	)
	if err != nil {
		t.Fatal(err)
	}

	if len(values.Groups) != 1 {
		t.Fatalf("получено %+v", values.Groups)
	}

	for _, line := range values.Groups[0].Lines {
		if !reflect.DeepEqual(line.Reset, []string{"sniffer.sniff.TLS"}) {
			t.Fatalf("%s снимает %v", line.Key, line.Reset)
		}
	}
}
