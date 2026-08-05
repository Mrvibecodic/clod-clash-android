# Clod Clash для Android

Android-клиент [Clod Clash](https://github.com/Mrvibecodic/clod-clash) — white-label клиента для
пользователей панели Remnawave. Десктопная версия (Windows / macOS / Linux) живёт в отдельном
репозитории, здесь только Android.

> **Статус: ранняя разработка.** Сейчас в `main` лежит нетронутая история апстрима
> (ClashMetaForAndroid v2.11.32). Ребрендинг, свой интерфейс и логика Remnawave — впереди.

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
