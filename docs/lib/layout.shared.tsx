import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { Logo } from '@/components/logo';
import { appName, community, gitConfig } from './shared';

const downloadTitle: Record<string, string> = {
  ru: 'Скачать',
  en: 'Download',
};

const groupTitle: Record<string, string> = {
  ru: 'Группа',
  en: 'Group',
};

const chatTitle: Record<string, string> = {
  ru: 'Чат',
  en: 'Chat',
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
      {
        text: groupTitle[lang] ?? groupTitle.ru,
        url: community.group,
      },
      {
        text: chatTitle[lang] ?? chatTitle.ru,
        url: community.chat,
      },
    ],
    githubUrl: `https://github.com/${gitConfig.user}/${gitConfig.repo}`,
  };
}
