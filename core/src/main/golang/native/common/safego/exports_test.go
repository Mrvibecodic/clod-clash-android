package safego

import (
	"go/ast"
	"go/parser"
	"go/token"
	"os"
	"strings"
	"testing"
)

// Экспорты моста без защиты — с причиной, по которой она им вредна.
var unguardedExports = map[string]string{
	"coreInit":         "инициализация, не дошедшая до конца, не должна выглядеть живым ядром; сброс внутри неё защищён сам",
	"queryTunnelState": "нулевого значения нет: режим по умолчанию вместо настоящего был бы ложью; тело — чтение переменной",
	"readOverride":     "нулевого значения нет: пустые настройки следующая запись сохранила бы поверх настоящих; тело — чтение файла",
}

// Паника в экспорте убивает фоновый процесс вместе с туннелем, а защита ставится
// руками на каждый: новый экспорт без неё сюда и попадает. guard обязан быть
// первым defer — иначе он отработает раньше остальных и их паника пройдёт мимо.
func TestEveryBridgeExportIsGuarded(t *testing.T) {
	pkgs, err := parser.ParseDir(token.NewFileSet(), "../..", func(info os.FileInfo) bool {
		return !strings.HasSuffix(info.Name(), "_test.go")
	}, parser.ParseComments)
	if err != nil {
		t.Fatal(err)
	}

	exports := 0

	for _, pkg := range pkgs {
		for _, file := range pkg.Files {
			for _, decl := range file.Decls {
				fn, ok := decl.(*ast.FuncDecl)
				if !ok || !isExport(fn) {
					continue
				}

				exports++

				if _, skip := unguardedExports[fn.Name.Name]; skip {
					continue
				}

				if !defersGuardFirst(fn) {
					t.Errorf("export %s: the first defer is not `defer guard(...)()`", fn.Name.Name)
				}
			}
		}
	}

	if exports < 30 {
		t.Fatalf("found %d exports, the bridge has more: the test no longer sees the bridge", exports)
	}
}

func isExport(fn *ast.FuncDecl) bool {
	if fn.Doc == nil {
		return false
	}

	for _, c := range fn.Doc.List {
		if strings.HasPrefix(c.Text, "//export ") {
			return true
		}
	}

	return false
}

func defersGuardFirst(fn *ast.FuncDecl) bool {
	for _, stmt := range fn.Body.List {
		d, ok := stmt.(*ast.DeferStmt)
		if !ok {
			continue
		}

		inner, ok := d.Call.Fun.(*ast.CallExpr)
		if !ok {
			return false
		}

		name, ok := inner.Fun.(*ast.Ident)

		return ok && name.Name == "guard"
	}

	return false
}
