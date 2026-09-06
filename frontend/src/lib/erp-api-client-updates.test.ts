import { beforeEach, describe, expect, it, vi } from 'vitest';

import { api } from './api';
import {
  androidReleases,
  clientInstallations,
  type AndroidReleaseDTO,
  type ClientInstallationListDTO,
} from './erp-api';

vi.mock('./api', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
  },
}));

const installations: ClientInstallationListDTO = {
  server_time: '2026-08-29T05:00:00Z',
  stale_after_hours: 24,
  total: 1,
  items: [{
    installation_id: '11111111-1111-4111-8111-111111111111',
    platform: 'android',
    distribution_channel: 'direct',
    version_name: '3.1.3',
    version_code: 14,
    pending_outbox_count: 0,
    last_successful_sync_at: '2026-08-29T04:59:00Z',
    update_state: 'idle',
    update_error_code: null,
    last_seen_at: '2026-08-29T05:00:00Z',
    is_stale: false,
    last_user_id: 'user-1',
    last_user_name: 'Rafi',
    terminal_id: 'terminal-1',
    terminal_name: 'Hybrid',
  }],
};

const release: AndroidReleaseDTO = {
  id: 'release-15',
  channel: 'direct',
  version_code: 15,
  version_name: '3.1.4',
  update_url: 'https://updates.example.test/d-company-3.1.4.apk',
  release_notes: 'Update reliability improvements',
  apk_sha256: 'ab'.repeat(32),
  apk_size_bytes: 42_000_000,
  apk_signing_cert_sha256: 'cd'.repeat(32),
  manifest_sha256: 'ef'.repeat(32),
  source_git_sha: '12'.repeat(20),
  source_release_ref: 'v3.1.4',
  source_workflow_run_id: '18446744073709551615',
  source_workflow_run_attempt: 2,
  status: 'staged',
  registered_at: '2026-08-29T04:00:00Z',
  activated_at: null,
  activated_by: null,
  withdrawn_at: null,
  withdrawn_by: null,
  updated_at: '2026-08-29T04:00:00Z',
};

describe('native client update owner API contract', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists bounded company installation health with the selected stale window', async () => {
    vi.mocked(api.get).mockResolvedValue({ data: installations });

    await expect(clientInstallations.list({
      stale_after_hours: 24,
      limit: 200,
      offset: 0,
    })).resolves.toEqual(installations);

    expect(api.get).toHaveBeenCalledWith('/client-installations', {
      params: { stale_after_hours: 24, limit: 200, offset: 0 },
    });
  });

  it('lists protected Android release evidence without an upload operation', async () => {
    const response = { total: 1, items: [release] };
    vi.mocked(api.get).mockResolvedValue({ data: response });

    await expect(androidReleases.list(200)).resolves.toEqual(response);
    expect(api.get).toHaveBeenCalledWith('/client-updates/android/releases', {
      params: { limit: 200 },
    });
    expect(Object.keys(androidReleases).sort()).toEqual(['activate', 'list', 'withdraw']);
    expect(response.items[0]?.source_workflow_run_id).toBe('18446744073709551615');
  });

  it('offers and withdraws only an existing staged release id', async () => {
    vi.mocked(api.post).mockResolvedValue({ data: release });

    await expect(androidReleases.activate(release.id)).resolves.toEqual(release);
    expect(api.post).toHaveBeenNthCalledWith(
      1,
      '/client-updates/android/releases/release-15/activate',
    );

    await expect(androidReleases.withdraw(release.id)).resolves.toEqual(release);
    expect(api.post).toHaveBeenNthCalledWith(
      2,
      '/client-updates/android/releases/release-15/withdraw',
    );
  });
});
