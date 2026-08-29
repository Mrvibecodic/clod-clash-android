export const appName = 'Clod Clash';
export const docsRoute = '/docs';
export const docsImageRoute = '/og/docs';
export const docsContentRoute = '/llms.mdx/docs';

export const basePath = process.env.NEXT_PUBLIC_BASE_PATH ?? '';

export const gitConfig = {
  user: 'Mrvibecodic',
  repo: 'clod-clash-android',
  branch: 'main',
};

export const community = {
  group: 'https://t.me/+2lmP1yhxpCE3MDcy',
  chat: 'https://t.me/+8BJQXYXYLqM4YWYy',
};

export const siteOrigin = `https://${gitConfig.user.toLowerCase()}.github.io`;
export const siteUrl = `${siteOrigin}${basePath}`;
export const promoUrl = `${basePath}/promo.png`;
export const releasesUrl = `https://github.com/${gitConfig.user}/${gitConfig.repo}/releases/latest`;

export const siteMeta = {
  ru: {
    title: 'Clod Clash — документация',
    description:
      'Клиент для Android на ядре mihomo: как установить, добавить подписку, подключиться и настроить.',
  },
  en: {
    title: 'Clod Clash — documentation',
    description:
      'An Android client powered by the mihomo core: install it, add a subscription, connect and tune the settings.',
  },
} as const;

export type SiteLang = keyof typeof siteMeta;

export function metaFor(lang: string) {
  return siteMeta[lang as SiteLang] ?? siteMeta.ru;
}
