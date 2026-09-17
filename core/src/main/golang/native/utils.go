package main

import "C"

import (
	"encoding/json"
	"fmt"
	"reflect"

	"cfa/native/common/safego"
	"cfa/native/redact"
)

// Паника в экспорте убила бы фоновый процесс вместе с туннелем. Под guard
// паника со стеком уходит в журнал, а вызов возвращает нулевое значение: у
// экспорта с результатом оно обязано означать на стороне Kotlin «не получилось»,
// экспорт без результата о сбое не сообщает ничем, кроме журнала.
func guard(name string, onPanic func()) func() {
	return safego.Guard(name, onPanic)
}

func panicError(name string, r any) error {
	return fmt.Errorf("%s: internal error: %v", name, r)
}

func marshalJson(obj any) *C.char {
	res, err := json.Marshal(obj)
	if err != nil {
		panic(err.Error())
	}

	return C.CString(string(res))
}

func marshalError(err error) *C.char {
	if err == nil {
		return nil
	}

	return C.CString(redact.Text(err.Error()))
}

func marshalString(obj any) *C.char {
	if obj == nil {
		return nil
	}

	switch o := obj.(type) {
	case string:
		return C.CString(o)
	}

	panic("invalid marshal type " + reflect.TypeOf(obj).Name())
}
