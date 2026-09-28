package config

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"
	"sync/atomic"

	mihomoConfig "github.com/metacubex/mihomo/config"
	"github.com/metacubex/mihomo/hub/route"
)

var errExternalControllerSecretBlank = errors.New("external controller secret is blank")

type externalControllerAccess struct {
	secret string
}

var currentExternalControllerAccess atomic.Pointer[externalControllerAccess]

var applyExternalControllerSecret = route.SetSecret

func init() {
	if err := RotateExternalControllerSecret(); err != nil {
		panic(err)
	}
}

func SetExternalControllerSecret(secret string) error {
	if strings.TrimSpace(secret) == "" {
		return errExternalControllerSecretBlank
	}
	currentExternalControllerAccess.Store(&externalControllerAccess{secret: secret})
	applyExternalControllerSecret(secret)
	return nil
}

func RotateExternalControllerSecret() error {
	secretBytes := make([]byte, 32)
	if _, err := rand.Read(secretBytes); err != nil {
		return err
	}
	return SetExternalControllerSecret(base64.RawURLEncoding.EncodeToString(secretBytes))
}

func ExternalControllerSecret() string {
	return currentExternalControllerAccess.Load().secret
}

func enforceExternalControllerAccess(cfg *mihomoConfig.RawConfig, _ string) error {
	cfg.AllowLan = false
	cfg.ExternalController = "127.0.0.1:9090"
	cfg.ExternalControllerTLS = ""
	cfg.ExternalControllerUnix = ""
	cfg.ExternalControllerPipe = ""
	cfg.Secret = ExternalControllerSecret()

	return nil
}
