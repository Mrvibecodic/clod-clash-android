import Link from 'next/link';
import { basePath } from '@/lib/shared';

const copy = {
  ru: {
    tagline: 'Клиент для Android: вставьте ссылку на подписку и подключайтесь',
    lead:
      'Ядро mihomo встроено в приложение. Список серверов, остаток трафика и срок действия ' +
      'приходят из вашей подписки, туннель поднимается одной кнопкой.',
    docs: 'Документация',
    download: 'Скачать APK',
    shot: 'ru/connected.png',
    shotAlt: 'Главный экран приложения в подключённом состоянии',
    points: [
      ['Одна кнопка', 'Туннель на всё устройство или локальный прокси без VPN'],
      ['Свои серверы', 'Выбор узла вручную, замер задержки, избранное'],
      ['Ничего лишнего', 'Тёмная тема, русский язык, виджет и плитка в шторке'],
    ],
  },
  en: {
    tagline: 'An Android client: paste your subscription link and connect',
    lead:
      'The mihomo core is bundled with the app. Server list, remaining traffic and expiry come ' +
      'from your subscription, and the tunnel starts with a single button.',
    docs: 'Documentation',
    download: 'Download APK',
    shot: 'en/connected.png',
    shotAlt: 'The app home screen while connected',
    points: [
      ['One button', 'A device-wide tunnel, or a local proxy without VPN'],
      ['Your servers', 'Pick a node by hand, measure latency, keep favourites'],
      ['Nothing extra', 'Dark theme, English and Russian, widget and quick tile'],
    ],
  },
} as const;

export default async function HomePage({ params }: PageProps<'/[lang]'>) {
  const { lang } = await params;
  const text = copy[lang as keyof typeof copy] ?? copy.ru;

  return (
    <main className="flex flex-1 flex-col items-center px-4 py-16">
      <div className="flex w-full max-w-5xl flex-col items-center gap-12 md:flex-row md:items-center md:justify-between">
        <div className="flex max-w-xl flex-col gap-6 text-center md:text-left">
          <h1 className="text-4xl font-bold tracking-tight md:text-5xl">Clod Clash</h1>
          <p className="text-xl text-fd-muted-foreground">{text.tagline}</p>
          <p className="text-fd-muted-foreground">{text.lead}</p>
          <div className="flex flex-row flex-wrap justify-center gap-3 md:justify-start">
            <Link
              href={`/${lang}/docs`}
              className="rounded-full bg-fd-primary px-6 py-2.5 font-medium text-fd-primary-foreground"
            >
              {text.docs}
            </Link>
            <Link
              href={`/${lang}/download`}
              className="rounded-full border border-fd-border px-6 py-2.5 font-medium"
            >
              {text.download}
            </Link>
          </div>
        </div>
        <img
          src={`${basePath}/screenshots/${text.shot}`}
          alt={text.shotAlt}
          width={260}
          className="rounded-3xl border border-fd-border shadow-2xl"
        />
      </div>

      <div className="mt-20 grid w-full max-w-5xl gap-6 md:grid-cols-3">
        {text.points.map(([title, description]) => (
          <div key={title} className="rounded-2xl border border-fd-border p-6">
            <h2 className="mb-2 font-semibold">{title}</h2>
            <p className="text-sm text-fd-muted-foreground">{description}</p>
          </div>
        ))}
      </div>
    </main>
  );
}
