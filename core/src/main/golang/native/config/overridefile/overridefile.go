package overridefile

import (
	"errors"
	"io/fs"
	"os"
	"sync"
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

// Store — файл с зеркалом в памяти: диск читается один раз, дальше отдаётся то,
// что записали сами. Единственный писатель файла — этот же процесс, поэтому
// зеркало не расходится с диском, а ошибка чтения держится до следующей записи.
type Store struct {
	path    string
	factory string

	mu      sync.Mutex
	loaded  bool
	content string
	err     error
}

func NewStore(path, factory string) *Store {
	return &Store{path: path, factory: factory}
}

func (s *Store) Read() (string, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if !s.loaded {
		s.content, s.err = Read(s.path, s.factory)
		s.loaded = true
	}

	return s.content, s.err
}

func (s *Store) Write(content string) error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := Write(s.path, content); err != nil {
		return err
	}

	s.content, s.err, s.loaded = content, nil, true

	return nil
}

func (s *Store) Remove() error {
	s.mu.Lock()
	defer s.mu.Unlock()

	if err := Remove(s.path); err != nil {
		return err
	}

	s.content, s.err, s.loaded = s.factory, nil, true

	return nil
}
