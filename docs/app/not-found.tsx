import type { Metadata } from 'next';
import { basePath } from '@/lib/shared';
import { i18n } from '@/lib/i18n';

const home = `${basePath}/${i18n.defaultLanguage}/`;

// Старые адреса страниц до деления документации на «Пользователю» и «Провайдеру»
const moved: Record<string, string> = {
  install: 'user/install',
  subscription: 'user/subscription',
  connect: 'user/connect',
  modes: 'user/modes',
  settings: 'user/settings',
  faq: 'user/faq',
  help: 'user/help',
  provider: 'provider/panel',
  headers: 'provider/headers',
  'clod-headers': 'provider/clod-headers',
};

const redirect = `(function(){var m=${JSON.stringify(moved)};var r=location.pathname.match(/^(.*\\/(?:ru|en)\\/docs\\/)([^/]+)\\/?$/);if(r&&m[r[2]])location.replace(r[1]+m[r[2]]+'/'+location.hash);})();`;

export const metadata: Metadata = {
  title: '404',
  robots: { index: false, follow: false },
};

export default function NotFound() {
  return (
    <html lang={i18n.defaultLanguage}>
      <head>
        {/* biome-ignore lint/security/noDangerouslySetInnerHtml: статичный скрипт переадресации */}
        <script dangerouslySetInnerHTML={{ __html: redirect }} />
      </head>
      <body className="flex flex-col min-h-screen items-center justify-center gap-2 text-center">
        <h1 className="text-2xl font-bold">404</h1>
        <p>
          Страница не найдена / Page not found — <a href={home}>{home}</a>
        </p>
      </body>
    </html>
  );
}
