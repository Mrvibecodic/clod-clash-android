package delivery

import (
	"bufio"
	"errors"
	"io"
	"net"
	"net/http"
	"strconv"
	"strings"
	"testing"
)

func TestNotAConfiguration(t *testing.T) {
	for _, row := range []struct {
		name  string
		start string
		want  bool
	}{
		{"пустое тело", "", true},
		{"одни пробелы и переводы строк", "  \r\n\t ", true},
		{"страница входа панели", "<!DOCTYPE html>\n<html lang=\"ru\">", true},
		{"страница с отступом", "\n  <html>", true},
		{"страница под BOM", "\xEF\xBB\xBF<html>", true},
		{"обычная подписка", "proxies:\n  - name: одиночный\n", false},
		{"подписка под BOM", "\xEF\xBB\xBFproxies:\n", false},
		{"подписка с комментария", "# clod\nproxies:\n", false},
		{"подписка одной строкой json", "{\"proxies\": []}", false},
		{"начало документа yaml", "---\nproxies:\n", false},
	} {
		if got := NotAConfiguration([]byte(row.start)); got != row.want {
			t.Fatalf("%s: NotAConfiguration(%q) = %v, хотели %v", row.name, row.start, got, row.want)
		}
	}
}

type answer struct {
	raw string
}

func (a answer) serve(t *testing.T) *http.Response {
	t.Helper()

	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("слушатель: %v", err)
	}

	t.Cleanup(func() { _ = listener.Close() })

	go func() {
		conn, err := listener.Accept()
		if err != nil {
			return
		}

		_, _ = bufio.NewReader(conn).ReadString('\n')
		_, _ = conn.Write([]byte(a.raw))
		_ = conn.Close()
	}()

	// Своя транспортная настройка, а не умолчание: у умолчания прокси берётся из
	// переменных окружения, и тогда исход теста зависит от машины, на которой он идёт.
	client := http.Client{Transport: &http.Transport{}}

	response, err := client.Get("http://" + listener.Addr().String() + "/sub")
	if err != nil {
		t.Fatalf("запрос: %v", err)
	}

	t.Cleanup(func() { _ = response.Body.Close() })

	return response
}

func TestGuardPassesTheWholeBody(t *testing.T) {
	body := "proxies:\n" + strings.Repeat("  - name: узел\n", 400)

	response := answer{raw: "HTTP/1.1 200 OK\r\nContent-Length: " + strconv.Itoa(len(body)) + "\r\n\r\n" + body}.serve(t)

	guarded, err := Guard(response.Body)
	if err != nil {
		t.Fatalf("Guard вернул ошибку на исправной подписке: %v", err)
	}

	read, err := io.ReadAll(guarded)
	if err != nil {
		t.Fatalf("чтение: %v", err)
	}

	if string(read) != body {
		t.Fatalf("тело прочитано не целиком: %d байт из %d", len(read), len(body))
	}
}

func TestGuardRejectsAWebPage(t *testing.T) {
	page := "<!DOCTYPE html><html><body>Войдите в личный кабинет</body></html>"

	response := answer{raw: "HTTP/1.1 200 OK\r\nContent-Length: " + strconv.Itoa(len(page)) + "\r\n\r\n" + page}.serve(t)

	if _, err := Guard(response.Body); !errors.Is(err, ErrNotDelivered) {
		t.Fatalf("страница принята за подписку: err = %v", err)
	}
}

func TestGuardRejectsAnEmptyBody(t *testing.T) {
	response := answer{raw: "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n"}.serve(t)

	if _, err := Guard(response.Body); !errors.Is(err, ErrNotDelivered) {
		t.Fatalf("пустой ответ принят за подписку: err = %v", err)
	}
}

func TestGuardKeepsTheTransportError(t *testing.T) {
	response := answer{raw: "HTTP/1.1 200 OK\r\nContent-Length: 4096\r\n\r\n"}.serve(t)

	_, err := Guard(response.Body)
	if err == nil || errors.Is(err, ErrNotDelivered) {
		t.Fatalf("обрыв до первого байта выдан за страницу: err = %v", err)
	}
}
