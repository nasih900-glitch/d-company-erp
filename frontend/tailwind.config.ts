import type { Config } from 'tailwindcss';

export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  darkMode: 'class',
  theme: {
    extend: {
      colors: {
        // D Company black/gold brand theme.
        bg: {
          DEFAULT: '#050403',
          surface: '#0d0a06',
          raised: '#17110a',
          border: '#3a2d17',
        },
        fg: {
          DEFAULT: '#f7f1df',
          muted: '#b9a471',
          subtle: '#7f714b',
        },
        accent: {
          DEFAULT: '#d2b36d',
          warm: '#f0c977',
          good: '#72d79a',
          bad: '#ff7d8f',
          gold: '#d6b46b',
          purple: '#9bb7d4',
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
        glow: '0 0 0 1px rgba(210,179,109,0.22), 0 10px 34px rgba(0,0,0,0.5)',
      },
    },
  },
  plugins: [],
} satisfies Config;
