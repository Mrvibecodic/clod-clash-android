import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { Logo } from '@/components/logo';
import { appName, gitConfig } from './shared';

const downloadTitle: Record<string, string> = {
  ru: 'Скачать',
  en: 'Download',
};

export function baseOptions(lang: string): BaseLayoutProps {
  return {
    nav: {
      title: (
        <span className="flex items-center gap-2 font-semibold">
          <Logo className="size-6" />
          {appName}
        </span>
      ),
      url: `/${lang}`,
    },
    links: [
      {
        text: downloadTitle[lang] ?? downloadTitle.ru,
        url: `/${lang}/download`,
      },
    ],
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
  };
}
