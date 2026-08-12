package tunnel

import (
	"github.com/metacubex/mihomo/component/iface"
	"github.com/metacubex/mihomo/component/resolver"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel/statistic"
)

// OnNetworkChanged — что делать, когда телефон переехал из одной сети в другую.
//
// ЗАЧЕМ ЭТО ВООБЩЕ НУЖНО. Ядро о смене сети не узнаёт: единственный монитор
// сетей живёт в листенере sing-tun и стартует только при `auto-route`
// или `auto-detect-interface`, а мы отдаём ядру готовый файловый дескриптор
// туннеля. И само по себе оно при смене сети не рвёт ничего: функции
// «закрыть все соединения» в ядре нет вовсе, есть только ручка REST, которую
// у нас никто не дёргает. Поэтому после переезда Wi-Fi → LTE соединения,
// поднятые через исчезнувший интерфейс, продолжают ждать таймаута ОС: TCP —
// минутами, UDP — до минуты. Человек это видит как «интернет появился,
// а всё висит».
//
// ПОЧЕМУ ЭТО НЕ ПРАВКА ЯДРА. Все три вызова ниже — публичные функции mihomo,
// и ровно их же зовёт само ядро в обработчике `DELETE /connections`
// (`hub/route/connections.go`). Мы для ядра такой же внешний потребитель,
// как его собственный HTTP-сервер; сабмодуль остаётся нетронутым пином.
//
// closeConnections — рвать ли живые соединения. Соединение через исчезнувший
// интерфейс всё равно мертво, и разница только в том, узнает ли приложение
// об этом сейчас или через таймаут. Но приложению без докачки «сейчас» —
// это оборванная загрузка, поэтому решение оставлено человеку тумблером.
func OnNetworkChanged(closeConnections bool) {
	// Кэш интерфейсов живёт двадцать секунд, и всё это время ядро считает
	// маршруты по интерфейсу, которого уже нет.
	iface.FlushCache()

	// Соединения к DNS-серверам держатся отдельно от пользовательских
	// и переживают смену сети так же плохо: имена перестают разрешаться
	// раньше, чем что-либо ещё.
	resolver.ResetConnection()

	if !closeConnections {
		log.Infoln("Network changed: interface cache and DNS connections reset")

		return
	}

	closed := 0

	statistic.DefaultManager.Range(func(c statistic.Tracker) bool {
		_ = c.Close()

		closed++

		return true
	})

	log.Infoln("Network changed: interface cache and DNS connections reset, %d connection(s) closed", closed)
}
