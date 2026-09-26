package overridefile

import (
	"errors"
	"io/fs"
	"os"
)

// Read отдаёт содержимое файла постоянных настроек. Нет файла — это заводские
// настройки. Любую другую ошибку чтения возвращаем как есть: выданный за
// заводские нечитаемый файл экран настроек записал бы пустотой поверх настоящего.
func Read(path, factory string) (string, error) {
	buf, err := os.ReadFile(path)
	if errors.Is(err, fs.ErrNotExist) {
		return factory, nil
	}

	if err != nil {
		return "", err
	}

	return string(buf), nil
}

// Write заменяет файл целиком через временный: оборванная запись не оставляет
// половину настроек.
func Write(path, content string) error {
	tmp := path + ".tmp"

	file, err := os.OpenFile(tmp, os.O_WRONLY|os.O_TRUNC|os.O_CREATE, 0600)
	if err != nil {
		return err
	}

	if _, err := file.Write([]byte(content)); err != nil {
		_ = file.Close()
		_ = os.Remove(tmp)

		return err
	}

	if err := file.Sync(); err != nil {
		_ = file.Close()
		_ = os.Remove(tmp)

		return err
	}

	if err := file.Close(); err != nil {
		_ = os.Remove(tmp)

		return err
	}

	if err := os.Rename(tmp, path); err != nil {
		_ = os.Remove(tmp)

		return err
	}

	return nil
}

// Remove возвращает заводские настройки. Файла и так нет — это успех.
func Remove(path string) error {
	if err := os.Remove(path); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return err
	}

	return nil
}
