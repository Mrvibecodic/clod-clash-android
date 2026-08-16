package config

import (
	"context"
	"io"
	"net/http"
	"os"
	P "path"
	"strings"
	"time"

	"cfa/native/app"

	clashHttp "github.com/metacubex/mihomo/component/http"
)

const (
	logoTimeout  = 10 * time.Second
	logoMaxBytes = 2 << 20
	logoBaseName = "logo"
)

var logoExtensions = map[string]string{
	"image/png":                ".png",
	"image/jpeg":               ".jpg",
	"image/webp":               ".webp",
	"image/avif":               ".avif",
	"image/gif":                ".gif",
	"image/svg+xml":            ".svg",
	"image/bmp":                ".bmp",
	"image/x-icon":             ".ico",
	"image/vnd.microsoft.icon": ".ico",
}

func fetchLogo(dir string, rawURL string) string {
	removeLogos(dir)

	if rawURL == "" {
		return ""
	}

	ctx, cancel := context.WithTimeout(context.Background(), logoTimeout)
	defer cancel()

	response, err := clashHttp.HttpRequest(ctx, rawURL, http.MethodGet, http.Header{
		"User-Agent": {"ClodClash/" + app.VersionName() + " (Android)"},
		"Accept":     {"image/*"},
	}, nil)
	if err != nil {
		return ""
	}

	defer response.Body.Close()

	if response.StatusCode/100 != 2 {
		return ""
	}

	extension, ok := logoExtensions[strings.TrimSpace(strings.Split(response.Header.Get("Content-Type"), ";")[0])]
	if !ok {
		return ""
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, logoMaxBytes+1))
	if err != nil || len(body) > logoMaxBytes || len(body) == 0 {
		return ""
	}

	name := logoBaseName + extension
	if err := os.WriteFile(P.Join(dir, name), body, 0o644); err != nil {
		return ""
	}

	return name
}

func removeLogos(dir string) {
	for _, extension := range logoExtensions {
		_ = os.Remove(P.Join(dir, logoBaseName+extension))
	}
}
