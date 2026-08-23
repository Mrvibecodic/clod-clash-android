import { gitConfig, releasesUrl } from './shared';

export const repoUrl = `https://github.com/${gitConfig.user}/${gitConfig.repo}`;
export const allReleasesUrl = `${repoUrl}/releases`;
export const latestReleaseUrl = releasesUrl;
export const latestReleaseApiUrl = `https://api.github.com/repos/${gitConfig.user}/${gitConfig.repo}/releases/latest`;

export function latestAssetUrl(file: string) {
  return `${releasesUrl}/download/${file}`;
}

export const apkFiles = [
  'clodclash-arm64-v8a.apk',
  'clodclash-armeabi-v7a.apk',
  'clodclash-x86_64.apk',
  'clodclash-universal.apk',
] as const;

export const extraFiles = ['SHA256SUMS.txt', 'abis.txt', 'latest.json'] as const;
