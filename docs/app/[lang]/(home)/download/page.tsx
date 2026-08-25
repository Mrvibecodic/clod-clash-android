import type { Metadata } from 'next';
import Link from 'next/link';
import { ReleaseCard } from '@/components/release-card';
import { allReleasesUrl, apkFiles, latestAssetUrl } from '@/lib/downloads';
import { appName, basePath, promoUrl, siteOrigin } from '@/lib/shared';

const copy = {
  ru: {
    title: 'Скачать',
    lead:
      'Последняя обычная версия для Android. Ссылки постоянные: когда выходит новая версия, ' +
      'они начинают вести на неё, менять закладку не нужно.',
    card: {
      pending: 'последняя версия',
      date: 'от',
      notes: 'Что нового →',
      download: 'Скачать',
      featured: 'подойдёт большинству',
      extras: 'Ещё в релизе:',
    },
    assets: [
      {
        file: 'clodclash-arm64-v8a.apk',
        title: '64-разрядные ARM',
        note: 'Почти все телефоны, купленные после 2019 года.',
        featured: true,
      },
      {
        file: 'clodclash-armeabi-v7a.apk',
        title: '32-разрядные ARM',
        note: 'Старые устройства и часть ТВ-приставок.',
      },
      {
        file: 'clodclash-x86_64.apk',
        title: 'Intel и AMD',
        note: 'Эмуляторы и редкие планшеты.',
      },
      {
        file: 'clodclash-universal.apk',
        title: 'Универсальный',
        note: 'Подходит всем, но весит вдвое больше остальных.',
      },
    ],
    extras: [
      { file: 'SHA256SUMS.txt', note: 'Контрольные суммы файлов релиза' },
      { file: 'abis.txt', note: 'Список собранных архитектур' },
      { file: 'latest.json', note: 'Описание релиза для встроенного обновления' },
    ],
    stepsTitle: 'Что дальше',
    steps: [
      'Откройте скачанный файл на телефоне.',
      'Разрешите установку приложений из этого источника, когда Android спросит.',
      'Вставьте ссылку на подписку и подключайтесь.',
    ],
    docsLink: 'Подробная инструкция по установке',
    linksTitle: 'Постоянные ссылки',
    linksLead:
      'В адресе нет номера версии: GitHub сам отдаёт файл из последнего обычного релиза. ' +
      'Предварительные сборки под эту ссылку не попадают.',
    updatesTitle: 'Обновления',
    updatesLead:
      'Приложение проверяет обновления само и показывает список изменений. ' +
      'Вручную: Ещё → О программе → Проверить обновление.',
    allReleases: 'Все релизы на GitHub',
  },
  en: {
    title: 'Download',
    lead:
      'The latest regular Android build. The links are permanent: once a new version ships they ' +
      'point at it, so a bookmark never goes stale.',
    card: {
      pending: 'latest version',
      date: 'released',
      notes: 'What is new →',
      download: 'Download',
      featured: 'fits most phones',
      extras: 'Also in the release:',
    },
    assets: [
      {
        file: 'clodclash-arm64-v8a.apk',
        title: '64-bit ARM',
        note: 'Almost every phone bought after 2019.',
        featured: true,
      },
      {
        file: 'clodclash-armeabi-v7a.apk',
        title: '32-bit ARM',
        note: 'Older devices and some TV boxes.',
      },
      {
        file: 'clodclash-x86_64.apk',
        title: 'Intel and AMD',
        note: 'Emulators and the rare tablet.',
      },
      {
        file: 'clodclash-universal.apk',
        title: 'Universal',
        note: 'Fits everything, twice the size of the others.',
      },
    ],
    extras: [
      { file: 'SHA256SUMS.txt', note: 'Checksums of the release files' },
      { file: 'abis.txt', note: 'Architectures built for this release' },
      { file: 'latest.json', note: 'Release description for the built-in updater' },
    ],
    stepsTitle: 'What next',
    steps: [
      'Open the downloaded file on your phone.',
      'Allow installing apps from this source when Android asks.',
      'Paste your subscription link and connect.',
    ],
    docsLink: 'Full installation guide',
    linksTitle: 'Permanent links',
    linksLead:
      'The address carries no version number: GitHub serves the file from the latest regular ' +
      'release. Pre-release builds are never picked up by it.',
    updatesTitle: 'Updates',
    updatesLead:
      'The app checks for updates on its own and shows the changelog. ' +
      'Manually: More → About → Check for update.',
    allReleases: 'All releases on GitHub',
  },
} as const;

function textFor(lang: string) {
  return copy[lang as keyof typeof copy] ?? copy.ru;
}

export async function generateMetadata({
  params,
}: PageProps<'/[lang]/download'>): Promise<Metadata> {
  const { lang } = await params;
  const text = textFor(lang);
  const title = `${text.title} — ${appName}`;

  return {
    metadataBase: new URL(siteOrigin),
    title: text.title,
    description: text.lead,
    alternates: {
      canonical: `${basePath}/${lang}/download/`,
      languages: {
        ru: `${basePath}/ru/download/`,
        en: `${basePath}/en/download/`,
      },
    },
    openGraph: {
      type: 'website',
      siteName: appName,
      locale: lang === 'ru' ? 'ru_RU' : 'en_US',
      url: `${basePath}/${lang}/download/`,
      title,
      description: text.lead,
      images: [{ url: promoUrl, width: 1200, height: 630, alt: appName }],
    },
    twitter: {
      card: 'summary_large_image',
      title,
      description: text.lead,
      images: [promoUrl],
    },
  };
}

export default async function DownloadPage({ params }: PageProps<'/[lang]/download'>) {
  const { lang } = await params;
  const text = textFor(lang);

  return (
    <main className="flex flex-1 flex-col items-center px-4 py-16">
      <div className="flex w-full max-w-3xl flex-col gap-10">
        <div className="flex flex-col gap-4">
          <h1 className="text-4xl font-bold tracking-tight">{text.title}</h1>
          <p className="text-lg text-fd-muted-foreground">{text.lead}</p>
        </div>

        <ReleaseCard
          lang={lang}
          copy={text.card}
          assets={[...text.assets]}
          extras={[...text.extras]}
        />

        <div className="grid gap-6 md:grid-cols-2">
          <div className="rounded-2xl border border-fd-border p-6">
            <h2 className="mb-3 font-semibold">{text.stepsTitle}</h2>
            <ol className="flex list-decimal flex-col gap-2 pl-5 text-sm text-fd-muted-foreground">
              {text.steps.map((step) => (
                <li key={step}>{step}</li>
              ))}
            </ol>
            <Link
              href={`/${lang}/docs/install`}
              className="mt-4 inline-block text-sm font-medium text-fd-primary hover:underline"
            >
              {text.docsLink} →
            </Link>
          </div>

          <div className="rounded-2xl border border-fd-border p-6">
            <h2 className="mb-3 font-semibold">{text.updatesTitle}</h2>
            <p className="text-sm text-fd-muted-foreground">{text.updatesLead}</p>
            <a
              href={allReleasesUrl}
              className="mt-4 inline-block text-sm font-medium text-fd-primary hover:underline"
            >
              {text.allReleases} →
            </a>
          </div>
        </div>

        <div className="rounded-2xl border border-fd-border p-6">
          <h2 className="mb-3 font-semibold">{text.linksTitle}</h2>
          <p className="mb-4 text-sm text-fd-muted-foreground">{text.linksLead}</p>
          <pre className="whitespace-pre-wrap break-all rounded-xl bg-fd-muted p-4 font-mono text-xs leading-6">
            {apkFiles.map((file) => `${latestAssetUrl(file)}\n`).join('')}
          </pre>
        </div>
      </div>
    </main>
  );
}
