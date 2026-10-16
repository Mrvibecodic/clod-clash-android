package common

import "os"

// WriteFile заменяет файл целиком через временный: читатель не застанет его
// недописанным. durable — дождаться записи на диск до подмены.
func WriteFile(path string, data []byte, durable bool) error {
	tmp := path + ".tmp"

	file, err := os.OpenFile(tmp, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0o600)
	if err != nil {
		return err
	}

	if _, err = file.Write(data); err == nil && durable {
		err = file.Sync()
	}

	if cerr := file.Close(); err == nil {
		err = cerr
	}

	if err == nil {
		err = os.Rename(tmp, path)
	}

	if err != nil {
		_ = os.Remove(tmp)
	}

	return err
}
