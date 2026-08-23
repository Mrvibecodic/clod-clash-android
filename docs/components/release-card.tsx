'use client';

import { useEffect, useState } from 'react';
import { latestAssetUrl, latestReleaseApiUrl, latestReleaseUrl } from '@/lib/downloads';

export interface ReleaseAsset {
  file: string;
  title: string;
  note: string;
  featured?: boolean;
}

export interface ReleaseCardCopy {
  pending: string;
  date: string;
  notes: string;
  download: string;
  featured: string;
  extras: string;
}

interface ReleaseCardProps {
  lang: string;
  copy: ReleaseCardCopy;
  assets: ReleaseAsset[];
  extras: { file: string; note: string }[];
}

interface ReleaseInfo {
  version: string;
  published: string;
  sizes: Record<string, number>;
}

function readRelease(payload: unknown): ReleaseInfo | null {
  if (typeof payload !== 'object' || payload === null) return null;

  const data = payload as { tag_name?: unknown; published_at?: unknown; assets?: unknown };
  if (typeof data.tag_name !== 'string') return null;

  const sizes: Record<string, number> = {};
  if (Array.isArray(data.assets)) {
    for (const item of data.assets) {
      const asset = item as { name?: unknown; size?: unknown };
      if (typeof asset.name === 'string' && typeof asset.size === 'number') {
        sizes[asset.name] = asset.size;
      }
    }
  }

  return {
    version: data.tag_name.replace(/^android-v/, ''),
    published: typeof data.published_at === 'string' ? data.published_at : '',
    sizes,
  };
}

function formatSize(bytes: number | undefined, locale: string): string {
  if (!bytes) return '';
  const value = new Intl.NumberFormat(locale, { maximumFractionDigits: 1 }).format(
    bytes / 1024 / 1024,
  );
  return `${value} MB`;
}

function formatDate(value: string, locale: string): string {
  if (!value) return '';
  const parsed = new Date(value);
  if (Number.isNaN(parsed.getTime())) return '';
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: 'long',
    year: 'numeric',
  }).format(parsed);
}

export function ReleaseCard({ lang, copy, assets, extras }: ReleaseCardProps) {
  const [release, setRelease] = useState<ReleaseInfo | null>(null);
  const locale = lang === 'ru' ? 'ru-RU' : 'en-US';

  useEffect(() => {
    let active = true;

    fetch(latestReleaseApiUrl, { headers: { Accept: 'application/vnd.github+json' } })
      .then((response) => (response.ok ? response.json() : null))
      .then((payload) => {
        const info = readRelease(payload);
        if (active && info) setRelease(info);
      })
      .catch(() => undefined);

    return () => {
      active = false;
    };
  }, []);

  const published = release ? formatDate(release.published, locale) : '';

  return (
    <section className="w-full overflow-hidden rounded-3xl border border-fd-border bg-fd-card">
      <header className="flex flex-wrap items-center justify-between gap-3 border-b border-fd-border px-6 py-4">
        <div className="flex flex-wrap items-center gap-3">
          <span className="rounded-full bg-fd-primary px-3 py-1 text-sm font-semibold tabular-nums text-fd-primary-foreground">
            {release ? release.version : copy.pending}
          </span>
          {published ? (
            <span className="text-sm text-fd-muted-foreground">
              {copy.date} {published}
            </span>
          ) : null}
        </div>
        <a href={latestReleaseUrl} className="text-sm font-medium text-fd-primary hover:underline">
          {copy.notes}
        </a>
      </header>

      <ul className="divide-y divide-fd-border">
        {assets.map((asset) => (
          <li key={asset.file} className="flex flex-wrap items-center gap-x-4 gap-y-3 px-6 py-4">
            <div className="min-w-56 flex-1">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{asset.title}</span>
                {asset.featured ? (
                  <span className="rounded-full border border-fd-primary px-2 py-0.5 text-xs text-fd-primary">
                    {copy.featured}
                  </span>
                ) : null}
              </div>
              <div className="mt-1 font-mono text-xs text-fd-muted-foreground">{asset.file}</div>
              <div className="mt-1 text-sm text-fd-muted-foreground">{asset.note}</div>
            </div>
            <span className="w-20 text-right text-sm tabular-nums text-fd-muted-foreground">
              {formatSize(release?.sizes[asset.file], locale)}
            </span>
            <a
              href={latestAssetUrl(asset.file)}
              className={
                asset.featured
                  ? 'rounded-full bg-fd-primary px-5 py-2 text-sm font-medium text-fd-primary-foreground'
                  : 'rounded-full border border-fd-border px-5 py-2 text-sm font-medium'
              }
            >
              {copy.download}
            </a>
          </li>
        ))}
      </ul>

      <footer className="flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-fd-border px-6 py-4">
        <span className="text-sm text-fd-muted-foreground">{copy.extras}</span>
        {extras.map((extra) => (
          <a
            key={extra.file}
            href={latestAssetUrl(extra.file)}
            title={extra.note}
            className="font-mono text-xs text-fd-primary hover:underline"
          >
            {extra.file}
          </a>
        ))}
      </footer>
    </section>
  );
}
