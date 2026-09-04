import { describe, expect, it } from 'vitest';
import viteConfig from '../../vite.config';

describe('local realtime proxy', () => {
  it('forwards WebSocket upgrades used by the operational event stream', () => {
    expect(viteConfig).toMatchObject({
      server: {
        proxy: {
          '/api': {
            target: 'http://localhost:8000',
            changeOrigin: true,
            ws: true,
          },
        },
      },
    });
  });
});
