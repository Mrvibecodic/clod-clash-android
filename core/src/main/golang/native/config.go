package main

//#include "bridge.h"
import "C"

import (
	"runtime"
	"unsafe"

	"cfa/native/config"
)

type remoteValidCallback struct {
	callback unsafe.Pointer
}

func (r *remoteValidCallback) reportStatus(json string) {
	C.fetch_report(r.callback, marshalString(json))
}

type ageKeyPair struct {
	SecretKey string `json:"secretKey"`
	PublicKey string `json:"publicKey"`
}

//export fetchAndValid
func fetchAndValid(callback unsafe.Pointer, path, url C.c_string, force C.int) {
	go func(path, url string, callback unsafe.Pointer) {
		cb := &remoteValidCallback{callback: callback}

		err := config.FetchAndValid(path, url, force != 0, cb.reportStatus)

		C.fetch_complete(callback, marshalError(err))

		C.release_object(callback)

		runtime.GC()
	}(C.GoString(path), C.GoString(url), callback)
}

//export setSecureChannel
func setSecureChannel(enabled C.int) {
	config.SetSecureChannel(enabled != 0)
}

//export useLocalControllerAccess
func useLocalControllerAccess() C.int {
	if err := config.RotateExternalControllerSecret(); err != nil {
		return controllerConfigurationEntropyUnavailable
	}
	return controllerConfigurationSucceeded
}

//export useDiagnosticsControllerAccess
func useDiagnosticsControllerAccess(secret C.c_string) C.int {
	if secret == nil {
		return controllerConfigurationSecretInvalid
	}
	if err := config.SetExternalControllerSecret(C.GoString(secret)); err != nil {
		return controllerConfigurationSecretInvalid
	}
	return controllerConfigurationSucceeded
}

const (
	controllerConfigurationSucceeded C.int = iota
	controllerConfigurationEntropyUnavailable
	controllerConfigurationSecretInvalid
)

//export load
func load(completable unsafe.Pointer, path C.c_string) {
	go func(path string) {
		C.complete(completable, marshalError(config.Load(path)))

		C.release_object(completable)

		runtime.GC()
	}(C.GoString(path))
}

//export readOverride
func readOverride(slot C.int) *C.char {
	return C.CString(config.ReadOverride(config.OverrideSlot(slot)))
}

//export writeOverride
func writeOverride(slot C.int, content C.c_string) {
	c := C.GoString(content)

	config.WriteOverride(config.OverrideSlot(slot), c)
}

//export clearOverride
func clearOverride(slot C.int) {
	config.ClearOverride(config.OverrideSlot(slot))
}

//export setAgeSecretKey
func setAgeSecretKey(key C.c_string) {
	if key == nil {
		config.SetGlobalSecretKeys()
		return
	}

	k := C.GoString(key)
	config.SetGlobalSecretKeys(k)
}

//export genX25519KeyPair
func genX25519KeyPair() *C.char {
	secretKey, publicKey, err := config.GenX25519KeyPair()
	if err != nil {
		return nil
	}

	return marshalJson(ageKeyPair{SecretKey: secretKey, PublicKey: publicKey})
}

//export genHybridKeyPair
func genHybridKeyPair() *C.char {
	secretKey, publicKey, err := config.GenHybridKeyPair()
	if err != nil {
		return nil
	}

	return marshalJson(ageKeyPair{SecretKey: secretKey, PublicKey: publicKey})
}

//export veritySecretKeys
func veritySecretKeys(secretKeys C.c_string) C.int {
	if config.VeritySecretKeys(C.GoString(secretKeys)) != nil {
		return 0
	}

	return 1
}

//export toPublicKeys
func toPublicKeys(secretKeys C.c_string) *C.char {
	publicKeys, err := config.ToPublicKeys(C.GoString(secretKeys))
	if err != nil {
		return nil
	}

	return marshalJson(publicKeys)
}

//export verityPublicKeys
func verityPublicKeys(publicKeys C.c_string) C.int {
	if config.VerityPublicKeys(C.GoString(publicKeys)) != nil {
		return 0
	}

	return 1
}
