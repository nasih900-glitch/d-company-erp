import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';

interface TauriSecurityConfig {
  app: {
    security: {
      csp: string;
    };
  };
}

function directive(csp: string, name: string): string[] {
  return csp
    .split(';')
    .map((entry) => entry.trim().split(/\s+/))
    .find(([directiveName]) => directiveName === name)
    ?.slice(1) ?? [];
}

describe('Tauri content security policy', () => {
  it('allows redacted frame object URLs only as image sources', () => {
    const path = new URL('../../src-tauri/tauri.conf.json', import.meta.url);
    const config = JSON.parse(readFileSync(path, 'utf8')) as TauriSecurityConfig;
    const csp = config.app.security.csp;

    expect(directive(csp, 'img-src')).toContain('blob:');
    expect(directive(csp, 'connect-src')).not.toContain('blob:');
    expect(directive(csp, 'script-src')).not.toContain('blob:');
  });
});
