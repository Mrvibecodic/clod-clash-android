package config

import (
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"
	"sync/atomic"
)

var errExternalControllerSecretBlank = errors.New("external controller secret is blank")

type externalControllerAccess struct {
	secret string
}

var currentExternalControllerAccess atomic.Pointer[externalControllerAccess]

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
