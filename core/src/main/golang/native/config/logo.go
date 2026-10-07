package config

import (
	"context"
	"io"
	"net/http"
	"os"
	P "path"
	"strings"
	"time"

	budgets "cfa/native/config/budget"

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

// Сбой загрузки оставляет прежний логотип: иначе он пропадал бы до следующего
// удачного обновления. Убирается логотип, только когда панель перестала его слать.
func fetchLogo(dir string, rawURL string, budget *budgets.Budget) string {
	if rawURL == "" {
		removeLogos(dir)

		return ""
	}

	kept := keptLogo(dir)

	limit, ok := budget.Window(time.Now(), logoTimeout)
	if !ok {
		return kept
	}

	ctx, cancel := context.WithTimeout(context.Background(), limit)
	defer cancel()

	response, err := clashHttp.HttpRequest(ctx, rawURL, http.MethodGet, http.Header{
		"User-Agent": {userAgent()},
		"Accept":     {"image/*"},
	}, nil)
	if err != nil {
		return kept
	}

	defer response.Body.Close()

	if response.StatusCode/100 != 2 {
		return kept
	}

	extension, ok := logoExtensions[strings.TrimSpace(strings.Split(response.Header.Get("Content-Type"), ";")[0])]
	if !ok {
		return kept
	}

	body, err := io.ReadAll(io.LimitReader(response.Body, logoMaxBytes+1))
	if err != nil || len(body) > logoMaxBytes || len(body) == 0 {
		return kept
	}

	removeLogos(dir)

	name := logoBaseName + extension
	if err := os.WriteFile(P.Join(dir, name), body, 0o600); err != nil {
		return ""
	}

	return name
}

func keptLogo(dir string) string {
	for _, extension := range logoExtensions {
		if _, err := os.Stat(P.Join(dir, logoBaseName+extension)); err == nil {
			return logoBaseName + extension
		}
	}

	return ""
}

func removeLogos(dir string) {
	for _, extension := range logoExtensions {
		_ = os.Remove(P.Join(dir, logoBaseName+extension))
	}
}
