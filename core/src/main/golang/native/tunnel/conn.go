package tunnel

import (
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// CloseAllConnections закрывает все соединения и отвечает, сколько закрыто
func CloseAllConnections() int {
	return closeMatch(func(C.Connection) bool { return true })
}

func closeMatch(filter func(conn C.Connection) bool) int {
	closed := 0

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		if filter(c) {
			_ = c.Close()
			closed++
		}
		return true
	})

	return closed
}

func closeConnByGroup(name string) {
	closeMatch(func(conn C.Connection) bool {
		for _, c := range conn.Chains() {
			if c == name {
				return true
			}
		}

		return false
	})
}
