package delivery

import (
	"bufio"
	"bytes"
	"errors"
	"io"
)

const head = 512

var bom = []byte{0xEF, 0xBB, 0xBF}

// ErrNotDelivered means the address answered, but what it answered with cannot
// be a subscription at all.
var ErrNotDelivered = errors.New("the address answered with a web page instead of the subscription")

type readCloser struct {
	io.Reader
	io.Closer
}

// NotAConfiguration reports whether the beginning of a response body cannot be
// a subscription at all: nothing was sent, or the address answered with a web
// page. A configuration never starts with an angle bracket.
func NotAConfiguration(start []byte) bool {
	trimmed := bytes.TrimLeft(bytes.TrimPrefix(start, bom), " \t\r\n\v\f")

	if len(trimmed) == 0 {
		return true
	}

	return trimmed[0] == '<'
}

// Guard looks at the beginning of the body and returns a reader over the whole
// of it, first byte included. A body that cannot be a subscription is reported
// as ErrNotDelivered, so the caller can move on to the spare addresses of the
// provider. The body is left to the caller to close in every case.
func Guard(body io.ReadCloser) (io.ReadCloser, error) {
	buffered := bufio.NewReader(body)

	start, err := buffered.Peek(head)
	if len(start) == 0 && err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}

	if NotAConfiguration(start) {
		return nil, ErrNotDelivered
	}

	return readCloser{Reader: buffered, Closer: body}, nil
}
