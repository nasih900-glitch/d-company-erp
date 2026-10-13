import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // Semantic channels are provided by the appearance root so every
        // existing utility follows day, night, or the operating-system theme.
        bg: {
          DEFAULT: 'rgb(var(--color-bg) / <alpha-value>)',
          surface: 'rgb(var(--color-bg-surface) / <alpha-value>)',
          raised: 'rgb(var(--color-bg-raised) / <alpha-value>)',
          border: 'rgb(var(--color-bg-border) / <alpha-value>)',
        },
        fg: {
          DEFAULT: 'rgb(var(--color-fg) / <alpha-value>)',
          muted: 'rgb(var(--color-fg-muted) / <alpha-value>)',
          subtle: 'rgb(var(--color-fg-subtle) / <alpha-value>)',
        },
        accent: {
          DEFAULT: 'rgb(var(--color-accent) / <alpha-value>)',
          warm: 'rgb(var(--color-accent-warm) / <alpha-value>)',
          good: 'rgb(var(--color-good) / <alpha-value>)',
          bad: 'rgb(var(--color-bad) / <alpha-value>)',
          gold: 'rgb(var(--color-brand-gold) / <alpha-value>)',
          purple: 'rgb(var(--color-accent-secondary) / <alpha-value>)',
        },
      },
      fontFamily: {
        sans: [
          '-apple-system',
          'BlinkMacSystemFont',
          'Inter',
          'system-ui',
          'sans-serif',
        ],
        mono: ['SFMono-Regular', 'ui-monospace', 'Menlo', 'monospace'],
      },
      borderRadius: { xl2: '1.25rem' },
      boxShadow: {
        glow: '0 10px 32px rgb(var(--color-shadow) / 0.16)',
      },
    },
  },
  plugins: [],
} satisfies Config;
