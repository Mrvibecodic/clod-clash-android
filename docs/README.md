# Документация Clod Clash

Сайт документации на [Fumadocs](https://fumadocs.dev) + Next.js, собирается статикой
(`output: 'export'`) и публикуется на GitHub Pages workflow'ом `.github/workflows/docs.yml`.

Опубликованная версия: <https://mrvibecodic.github.io/clod-clash-android/>

Ссылки на Telegram-группу и чат живут в `lib/shared.ts` (`community`) — правятся там,
а шапка сайта и главная берут их оттуда.

## Локальный запуск

```bash
npm ci
npm run dev     # http://localhost:3000/clod-clash-android/ru/
npm run build   # статика в out/
npm start       # раздать out/
```

## Что где лежит

| Путь | Что это |
|---|---|
| `content/docs/ru`, `content/docs/en` | страницы в MDX, по одной ветке на язык |
| `content/docs/*/meta.json` | порядок пунктов в боковом меню |
| `public/screenshots/{ru,en}` | скриншоты приложения |
| `public/promo.png` | обложка для og:image и шапки корневого README |
| `lib/i18n.ts` | список языков и перевод интерфейса Fumadocs |
| `lib/shared.ts` | имя приложения, адрес сайта, ссылки на репозиторий |
| `components/shot.tsx` | компоненты `<Shot>` и `<Shots>` для вставки скриншотов |

## Скриншоты

Скриншоты не снимаются с устройства — они рендерятся из кода приложения на JVM
модулем `:screenshots` (Robolectric + Roborazzi) и содержат только демонстрационные данные.

```bash
./gradlew :screenshots:testDebugUnitTest
```

Готовые PNG появляются в `screenshots/build/screenshots/{ru,en}` — оттуда их
переносят в `docs/public/screenshots`.

## Добавить страницу

1. Создать `content/docs/ru/<name>.mdx` и `content/docs/en/<name>.mdx`
   с заголовком `title` и `description` в кавычках.
2. Дописать `<name>` в оба `meta.json`.
3. `npm run build` — проверить, что сборка проходит.
