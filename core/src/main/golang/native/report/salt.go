package report

import (
	"crypto/sha256"
	"encoding/hex"
	"sync/atomic"
)

// salt — соль ключа сети этой установки; служба передаёт её с каждой просьбой.
// Без соли ключ мобильной сети — хеш кода оператора, и перебором по паре тысяч
// кодов он выдаёт оператора и страну.
var salt atomic.Value

func SetSalt(value string) {
	salt.Store(value)
}

func saltOf() string {
	value, _ := salt.Load().(string)

	return value
}

// NetKey — ключ сети в накопленном и в отчёте: хеш признаков сети с солью, той
// же длины. Без признаков или без соли — пусто.
func NetKey(raw string) string {
	value := saltOf()
	if raw == "" || value == "" {
		return ""
	}

	sum := sha256.Sum256([]byte(value + ";" + raw))
	key := hex.EncodeToString(sum[:])

	if len(raw) < len(key) {
		key = key[:len(raw)]
	}

	return key
}
