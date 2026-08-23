import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { appName, gitConfig } from './shared';

const downloadTitle: Record<string, string> = {
  ru: 'Скачать',
  en: 'Download',
};

export function baseOptions(lang: string): BaseLayoutProps {
  return {
    nav: {
      title: appName,
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
