import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import type { AndroidReleaseDTO } from '@/lib/erp-api';
import {
  canConfirmReleaseAction,
  hasReviewableReleaseEvidence,
  releaseEvidenceRows,
  ReleaseActionConfirmation,
  ReleaseEvidence,
} from './DevicesUpdatesTab';

const release: AndroidReleaseDTO = {
  id: 'release-15',
  channel: 'direct',
  version_code: 15,
  version_name: '3.1.4',
  update_url: 'https://updates.example.test/downloads/android/d-company-3.1.4.apk',
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

describe('Android release owner evidence', () => {
  it('keeps every approval field complete and copyable without losing a 64-bit run id', () => {
    const rows = releaseEvidenceRows(release);
    expect(rows.map((row) => row.key)).toEqual([
      'version_code',
      'source_release_ref',
      'update_url',
      'apk_sha256',
      'apk_signing_cert_sha256',
      'manifest_sha256',
      'source_git_sha',
      'source_workflow_run_id',
      'source_workflow_run_attempt',
    ]);
    expect(rows.find((row) => row.key === 'source_workflow_run_id')?.value)
      .toBe('18446744073709551615');

    const markup = renderToStaticMarkup(
      <ReleaseEvidence release={release} onCopy={vi.fn()} />,
    );
    for (const row of rows) {
      expect(markup).toContain(row.label);
      expect(markup).toContain(row.value);
      expect(markup).toContain(`aria-label="Copy ${row.label}"`);
    }
    expect(markup).toContain('select-all');
    expect(markup).toContain('break-all');
  });

  it('requires complete evidence and explicit review before an offer', () => {
    const pendingOffer = { action: 'activate' as const, release };
    expect(hasReviewableReleaseEvidence(release)).toBe(true);
    expect(canConfirmReleaseAction(pendingOffer, false, null)).toBe(false);
    expect(canConfirmReleaseAction(pendingOffer, true, null)).toBe(true);
    expect(canConfirmReleaseAction(pendingOffer, true, 'activate:release-15')).toBe(false);

    const incomplete = { ...release, source_git_sha: '' };
    expect(hasReviewableReleaseEvidence(incomplete)).toBe(false);
    expect(canConfirmReleaseAction({ action: 'activate', release: incomplete }, true, null))
      .toBe(false);

    // Withdrawal remains available for a bad legacy record so an unsafe offer
    // can always be removed from circulation.
    expect(canConfirmReleaseAction({ action: 'withdraw', release: incomplete }, false, null))
      .toBe(true);
  });

  it('shows the evidence disclaimer and explicit review acknowledgement in Offer confirmation', () => {
    const markup = renderToStaticMarkup(
      <ReleaseActionConfirmation
        pending={{ action: 'activate', release }}
        previousError={null}
        evidenceReviewed={false}
        onEvidenceReviewed={vi.fn()}
        onCopy={vi.fn()}
      />,
    );

    expect(markup).toContain('This browser has not');
    expect(markup).toContain('independently verified');
    expect(markup).toContain('I reviewed this release evidence');
    expect(markup).toContain('type="checkbox"');
    expect(markup).toContain(release.apk_sha256);
    expect(markup).toContain(release.apk_signing_cert_sha256);
    expect(markup).toContain(release.manifest_sha256);
    expect(markup).toContain(release.source_workflow_run_id);
  });

  it('keeps the Offer action disabled while a caller-owned review prerequisite is unmet', () => {
    const markup = renderToStaticMarkup(
      <ConfirmModal
        title="Offer this Android update?"
        message={<p>Review required</p>}
        confirmLabel="Offer update"
        confirmDisabled
        onCancel={vi.fn()}
        onConfirm={vi.fn()}
      />,
    );

    expect(markup).toContain('Review required');
    expect(markup).toMatch(
      /<button[^>]*class="btn btn-primary"[^>]*disabled=""[^>]*>\s*Offer update<\/button>/,
    );
  });
});
