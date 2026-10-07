package panel

import (
	"bytes"
	"encoding/json"
	"errors"
	"io/fs"
	"os"
	P "path"
)

// staleSuffix совпадает с ProfileSwap.STALE_SUFFIX (service/.../util/ProfileSwap.kt).
const staleSuffix = ".old"

const ProfileConfigFile = "config.yaml"

const readAttempts = 3

// ReadProfileFile читает файл каталога профиля без замка подмены. Живой
// каталог не целый, а .old целый — подмена идёт или оборвалась, и ответ даёт
// только .old (то же решает repair). Ответ «файла нет» по живому каталогу
// даётся, когда .old не было ни до, ни после чтения, и подтверждается
// повторным чтением: две подмены подряд между ними не уместятся — их разделяет
// загрузка подписки. То же правило — ProfileSwap.read на стороне Kotlin.
func ReadProfileFile(dir string, name string) ([]byte, error) {
	dir = P.Clean(dir)
	stale := dir + staleSuffix

	var err error

	for i := 0; i < readAttempts; i++ {
		quiet := !exists(stale)

		if !isWhole(dir) && isWhole(stale) {
			var data []byte

			data, err = os.ReadFile(P.Join(stale, name))
			if !errors.Is(err, fs.ErrNotExist) {
				return data, err
			}

			if isWhole(stale) && !isWhole(dir) {
				return nil, err
			}
		}

		var data []byte

		data, err = os.ReadFile(P.Join(dir, name))
		if !errors.Is(err, fs.ErrNotExist) {
			return data, err
		}

		if quiet && !exists(stale) && isDir(dir) {
			return os.ReadFile(P.Join(dir, name))
		}
	}

	return nil, err
}

func exists(path string) bool {
	_, err := os.Stat(path)

	return err == nil
}

func isDir(path string) bool {
	info, err := os.Stat(path)

	return err == nil && info.IsDir()
}

func isWhole(dir string) bool {
	info, err := os.Stat(P.Join(dir, ProfileConfigFile))

	return isDir(dir) && err == nil && info.Mode().IsRegular()
}

// writeJSON пишет файл каталога профиля через временный и переименование:
// читатель без замка не застанет его недописанным. Тот же файл не
// переписывается — разбор конфига зовёт запись на каждой загрузке и пробе.
func writeJSON(path string, value any) {
	data, err := json.Marshal(value)
	if err != nil {
		return
	}

	if current, err := os.ReadFile(path); err == nil && bytes.Equal(current, data) {
		return
	}

	tmp := path + ".tmp"

	if err := os.WriteFile(tmp, data, 0o600); err != nil {
		return
	}

	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)
	}
}
