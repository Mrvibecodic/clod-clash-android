import type { Metadata } from 'next';
import { basePath } from '@/lib/shared';
import { i18n } from '@/lib/i18n';

const home = `${basePath}/${i18n.defaultLanguage}/`;

export const metadata: Metadata = {
  title: '404',
  robots: { index: false, follow: false },
};

export default function NotFound() {
  return (
    <html lang={i18n.defaultLanguage}>
      <body className="flex flex-col min-h-screen items-center justify-center gap-2 text-center">
        <h1 className="text-2xl font-bold">404</h1>
        <p>
          Страница не найдена / Page not found — <a href={home}>{home}</a>
        </p>
      </body>
    </html>
  );
}
