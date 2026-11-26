import { defineConfig } from 'astro/config';
import starlight from '@astrojs/starlight';
import remarkGfm from 'remark-gfm';

const group = (label, zh, items, collapsed = false) => ({
  label,
  translations: { 'zh-CN': zh },
  items: items.map((slug) => ({ slug })),
  collapsed,
});

export default defineConfig({
  site: 'https://starfold.saya.love',
  trailingSlash: 'always',
  markdown: { remarkPlugins: [remarkGfm] },
  redirects: { '/': '/en/' },
  integrations: [
    starlight({
      title: {
        en: 'Starfold · Escape Velocity',
        'zh-CN': '星海折越 · Escape Velocity',
      },
      defaultLocale: 'en',
      favicon: '/favicon.png',
      locales: {
        en: { label: 'English', lang: 'en' },
        'zh-cn': { label: '简体中文', lang: 'zh-CN' },
      },
      customCss: [
        '@fontsource/space-grotesk/latin-500.css',
        '@fontsource/space-grotesk/latin-600.css',
        '@fontsource/space-grotesk/latin-700.css',
        '@fontsource/figtree/latin-400.css',
        '@fontsource/figtree/latin-500.css',
        '@fontsource/figtree/latin-600.css',
        '@fontsource/jetbrains-mono/latin-400.css',
        '@fontsource/jetbrains-mono/latin-500.css',
        './src/styles/handbook.css',
      ],
      components: {
        ThemeProvider: './src/components/ThemeProvider.astro',
        Head: './src/components/Head.astro',
        PageTitle: './src/components/PageTitle.astro',
      },
      pagination: true,
      sidebar: [
        { slug: 'start' },
        group(
          'Survival Guide',
          '七阶段攻略',
          'hearth workshop-fuel first-ship osmium-round-trip hydrogen-supply deuterium flesh-host interstellar-expedition'
            .split(' ')
            .map((stage) => `progression/${stage}`),
          false,
        ),
        group(
          'Worlds & Moons',
          '星球与卫星',
          [
            'worlds',
            'worlds/types',
            ...'hearth fleck anvil viridia pelagia saffron haven hush ivory amber cinder rime prism flesh'
              .split(' ')
              .map((world) => `worlds/${world}`),
          ],
          true,
        ),
        group('Ships & Schematics', '飞船与蓝图', ['ships', 'ships/c07', 'ships/s01', 'schematics'], false),
        group('Bosses', 'Boss 档案', ['bosses', 'bosses/host'], false),
        group(
          'Flight Reference',
          '航行速查',
          'flight-controls atlas oil troubleshooting preflight'.split(' ').map((topic) => `reference/${topic}`),
          false,
        ),
      ],
    }),
  ],
});
