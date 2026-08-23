import defaultMdxComponents from 'fumadocs-ui/mdx';
import type { MDXComponents } from 'mdx/types';
import { Shot, Shots } from '@/components/shot';

export function getMDXComponents(components?: MDXComponents) {
  return {
    ...defaultMdxComponents,
    Shot,
    Shots,
    ...components,
  } satisfies MDXComponents;
}

export const useMDXComponents = getMDXComponents;

declare global {
  type MDXProvidedComponents = ReturnType<typeof getMDXComponents>;
}
