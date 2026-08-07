# Clod Clash для Android

Android-клиент [Clod Clash](https://github.com/Mrvibecodic/clod-clash) — white-label клиента для
пользователей панели Remnawave. Десктопная версия (Windows / macOS / Linux) живёт в отдельном
репозитории, здесь только Android.

> **Статус: ранняя разработка.** База — история апстрима (ClashMetaForAndroid v2.11.32),
> поверх неё сделан ребрендинг. Свой интерфейс на Compose и логика Remnawave — впереди.

## На чём это основано

Проект собран из чужой работы. Ниже — что именно и откуда взято.

| Что | Откуда | Лицензия |
|---|---|---|
| **Оболочка приложения**: `VpnService`, JNI-мост Go↔Kotlin, многопроцессный сервисный слой, хранилище профилей, сборка Go-ядра, CI | [MetaCubeX/ClashMetaForAndroid](https://github.com/MetaCubeX/ClashMetaForAndroid) — база этого репозитория, история сохранена целиком | GPL-3.0 |
| **Ядро** | [MetaCubeX/mihomo](https://github.com/MetaCubeX/mihomo), подключено submodule'ом (ветка Alpha), **не форкается и не патчится** | GPL-3.0 |
| **Интерфейс на Jetpack Compose** | [fUS1ONd/Prizrak-Box-android](https://github.com/fUS1ONd/Prizrak-Box-android) — их миграция UI на Compose переносится к нам как основа (планируется) | GPL-3.0 |
| **Визуальный референс главного экрана** | [coolcoala/KoalaClash-Android](https://github.com/coolcoala/KoalaClash-Android) — идея раскрывающегося экрана вместо переключателя режимов | GPL-3.0 |
| **Логика подписок и заголовков Remnawave** | наш собственный Rust-крейт из [Mrvibecodic/clod-clash](https://github.com/Mrvibecodic/clod-clash), подключается через FFI | GPL-3.0 |

Отдельная благодарность авторам [ClashForAndroid](https://github.com/Kr328/ClashForAndroid)
(Kr328) — с него начинался апстрим, и его код до сих пор составляет заметную часть оболочки.

Список лицензий сторонних компонентов — в [NOTICE](NOTICE).

## Заголовки подписки

Полный справочник — [docs/HEADERS.md](https://github.com/Mrvibecodic/clod-clash/blob/main/docs/HEADERS.md)
в репозитории десктопного клиента; настройка панели — там же в `docs/REMNAWAVE.md`.
Здесь только то, чем Android отличается.

**Отправляем с каждым запросом подписки** (`core/…/config/fetch.go`): `User-Agent:
ClodClash/<версия> (Android)`, `Accept: */*` и, пока включено «Опознавать это
устройство», четыре заголовка опознания — `x-hwid` (SHA-256 от `ANDROID_ID`
с солью, 32 hex), `x-device-os: Android`, `x-ver-os`, `x-device-model`.
Выключили опознание — не уходит ни один из четырёх.

**Понимаем в ответе** (`core/…/config/panel.go`): `profile-title`, `profile-logo`,
`subscription-userinfo`, `subscription-refill-date`, `profile-update-interval`,
`announce` + `announce-url`, `clod-promo` + `clod-promo-url`, `clod-portal-url`,
`support-url`, `clod-hwid-limit`, всё семейство `x-hwid-*`.

Чего пока нет по сравнению с десктопом: смены адреса подписки (`new-url`,
`new-domain`, `fallback-url`, `fallback-domain`), порогов уведомлений
(`notify-*`), часов панели (`Date`) и заголовков режима интерфейса
(`clod-simple-mode`, `clod-lock-mode`).

Отличия в поведении:

* объявление и промо делят один слот — `clod-promo` показывается, только если
  `announce` пуст, — и обрезаются до 300 символов, а не до 500;
* `profile-update-interval` применяется только к новой подписке с пустым
  интервалом; у уже добавленной интервал остаётся тот, что стоит в свойствах;
* `subscription-refill-date` дополнительно понимает миллисекунды и `RFC3339`;
* `profile-web-page-url` разбирается, но пока нигде не показывается.

Правила разбора те же, что на десктопе: регистр не важен, ищется суффикс
(`x-amz-meta-announce` подойдёт), `base64:<payload>` декодируется четырьмя
алфавитами, а значение, объявившее себя base64 и им не оказавшееся, считается
отсутствующим. Все ссылки принимаются **только по `https`**; `support-url`
дополнительно понимает `tg:` и `mailto:`.

## Лицензия

GPL-3.0, как и у всех проектов выше. См. [LICENSE](LICENSE).

## Апстрим

Обновления вливаются из апстрима штатным мерджем:

```bash
git remote add upstream https://github.com/MetaCubeX/ClashMetaForAndroid.git
git fetch upstream
git merge upstream/main
```

Репозиторий намеренно сделан не GitHub-форком, а зеркалом с полной историей: так работают
Actions, репозиторий можно сделать приватным, и он не висит в fork-network апстрима.
На возможность вливать апстрим это не влияет.
