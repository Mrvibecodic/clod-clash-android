package localproviders

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/metacubex/mihomo/config"
	types "github.com/metacubex/mihomo/constant/provider"
)

// Initial проверяет провайдеры с файлом без ссылки: такой файл некому
// перекачать, а ядро при загрузке лишь отметит беду в журнале, оставив группу
// пустой. У набора серверов проверяется только наличие файла: его разбор ядром
// запускает проверки узлов и рвёт соединения одноимённого набора в работающем
// туннеле.
func Initial(cfg *config.Config) error {
	for name, p := range cfg.Providers {
		if err := present("proxy provider", name, p); err != nil {
			return err
		}
	}

	for name, p := range cfg.RuleProviders {
		if err := present("rule provider", name, p); err != nil {
			return err
		}

		if p.VehicleType() != types.File {
			continue
		}

		if err := p.Initial(); err != nil {
			return fmt.Errorf("rule provider %s: %w", name, err)
		}
	}

	return nil
}

func present(kind string, name string, p types.Provider) error {
	if p.VehicleType() != types.File {
		return nil
	}

	v, ok := p.(interface{ Vehicle() types.Vehicle })
	if !ok {
		return nil
	}

	path := v.Vehicle().Path()

	if _, err := os.Stat(path); err != nil {
		return fmt.Errorf("%s %s: file %s not found", kind, name, filepath.Base(path))
	}

	return nil
}
