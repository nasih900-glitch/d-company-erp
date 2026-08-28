import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { hasAdminSystemAccess, hasAuditAccess } from '@/lib/admin-access';
import type { BugReportDTO } from '@/lib/erp-api';
import { ReportDetail } from './BugReportsScreen';
import {
  BugReportDetailRequestGate,
  EMPTY_BUG_REPORT_FILTERS,
  buildBugReportListParams,
  bugReportStatusOptionsFor,
  resolutionNoteError,
} from './bug-report-ui';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

const report: BugReportDTO = {
  id: '0bb197be-8c9f-4d14-8726-098841af0276',
  category: 'usability',
  severity: 'high',
  title: 'Void reason field cannot be completed',
  description: '<script>alert("unsafe")</script> Keyboard does not open.',
  reproduction_steps: 'Open an order and tap Void.',
  expected_behavior: 'A text keyboard opens.',
  actual_behavior: 'The field does not accept input.',
  client_context: {
    platform: 'android',
    app_version: '3.0.5',
    version_code: 3000500,
    device_model: 'API 35 tablet',
    os_version: 'Android 15',
    current_screen: 'POS / void dialog',
    last_action: 'Tapped Void item',
    error_code: 'VOID_REASON_INPUT_BLOCKED',
    branch_id: '59511f4f-3a07-40b6-9f9b-90aa78df15ea',
    branch_name: 'Main branch',
    terminal_id: '787833ae-d942-48b8-81eb-b14b8d77c19f',
    terminal_name: 'Main POS',
    connectivity: 'online',
    occurred_at: '2026-08-28T12:30:00Z',
  },
  status: 'open',
  internal_resolution_note: null,
  public_replies: [{
    id: '5fb4c21b-aea0-42bc-8c80-ad7e76f24f24',
    author_name: 'Nasih',
    message: 'The input fix is ready. Restart the app and try again.',
    created_at: '2026-08-28T12:40:00Z',
  }],
  attachments: [],
  reporter: {
    user_id: '1703098e-9f86-47dc-a668-0655e89cff96',
    name: '<img src=x onerror=alert(1)>',
    email: 'staff@example.com',
  },
  status_changed_at: '2026-08-28T12:30:00Z',
  status_changed_by: '1703098e-9f86-47dc-a668-0655e89cff96',
  resolved_at: null,
  resolved_by: null,
  created_at: '2026-08-28T12:30:00Z',
  updated_at: '2026-08-28T12:30:00Z',
};

describe('bug report inbox access and filtering', () => {
  it('uses the exact audit_access signal for admin.system route and navigation access', () => {
    expect(hasAuditAccess({ audit_access: true })).toBe(true);
    expect(hasAuditAccess({ audit_access: false })).toBe(false);
    expect(hasAuditAccess({ protected_access: true })).toBe(false);

    expect(hasAdminSystemAccess({ audit_access: true })).toBe(true);
    expect(hasAdminSystemAccess({ audit_access: false })).toBe(false);
    expect(hasAdminSystemAccess(null)).toBe(false);

    const coOwnerWithoutSystemAccess = { protected_access: true, audit_access: false };
    expect(hasAdminSystemAccess(coOwnerWithoutSystemAccess)).toBe(false);
  });

  it('trims search text and omits blank filters from the list request', () => {
    expect(buildBugReportListParams({
      ...EMPTY_BUG_REPORT_FILTERS,
      q: '  payment failed  ',
      severity: 'critical',
      platform: 'android',
    }, 25, 25)).toEqual({
      q: 'payment failed',
      status: undefined,
      severity: 'critical',
      category: undefined,
      platform: 'android',
      offset: 25,
      limit: 25,
    });
  });
});

describe('bug report lifecycle editing', () => {
  it('offers only the backend-supported next states plus the current state', () => {
    expect(bugReportStatusOptionsFor('open').map((option) => option.value)).toEqual([
      'open',
      'acknowledged',
      'in_progress',
      'resolved',
      'rejected',
    ]);
    expect(bugReportStatusOptionsFor('resolved').map((option) => option.value)).toEqual([
      'in_progress',
      'resolved',
      'closed',
    ]);
    expect(bugReportStatusOptionsFor('closed').map((option) => option.value)).toEqual([
      'in_progress',
      'closed',
    ]);
  });

  it('requires a meaningful internal note for terminal states', () => {
    expect(resolutionNoteError('resolved', '  ')).toContain('at least 3 characters');
    expect(resolutionNoteError('closed', 'ok')).toContain('at least 3 characters');
    expect(resolutionNoteError('rejected', 'Not reproducible')).toBeNull();
    expect(resolutionNoteError('in_progress', '')).toBeNull();
  });
});

describe('bug report detail request generations', () => {
  it('ignores a late detail response after closing and reopening the same report', async () => {
    const gate = new BugReportDetailRequestGate();
    const firstSession = gate.openSession();
    const response = deferred<string>();
    let applied = '';
    const consume = response.promise.then((value) => {
      if (gate.isCurrentSession(firstSession)) applied = value;
    });

    gate.closeSession();
    const reopenedSession = gate.openSession();
    response.resolve('stale detail');
    await consume;

    expect(gate.isCurrentSession(reopenedSession)).toBe(true);
    expect(applied).toBe('');
  });

  it('rejects a late save from an earlier modal lifetime and synchronously blocks double submit', async () => {
    const gate = new BugReportDetailRequestGate();
    const firstSession = gate.openSession();
    const firstSave = gate.startSave(firstSession);
    expect(firstSave).not.toBeNull();
    expect(gate.startSave(firstSession)).toBeNull();

    const response = deferred<string>();
    let applied = '';
    const consume = response.promise.then((value) => {
      if (firstSave && gate.isCurrentSave(firstSave)) applied = value;
    });

    gate.closeSession();
    const reopenedSession = gate.openSession();
    const reopenedSave = gate.startSave(reopenedSession);
    response.resolve('stale save');
    await consume;

    expect(applied).toBe('');
    expect(firstSave && gate.finishSave(firstSave)).toBe(false);
    expect(reopenedSave && gate.isCurrentSave(reopenedSave)).toBe(true);
  });
});

describe('bug report detail rendering', () => {
  it('renders report text as escaped content and only the allowlisted client context', () => {
    const markup = renderToStaticMarkup(
      <ReportDetail
        report={report}
        detailLoading={false}
        detailReady
        error={null}
        statusDraft="open"
        noteDraft=""
        noteTooLong={false}
        noteValidationError={null}
        saving={false}
        hasChanges={false}
        onStatusChange={() => undefined}
        onNoteChange={() => undefined}
        onSave={() => undefined}
        replyDraft=""
        replying={false}
        onReplyChange={() => undefined}
        onReply={() => undefined}
        onOpenAttachment={() => undefined}
      />,
    );

    expect(markup).toContain('&lt;script&gt;alert(&quot;unsafe&quot;)&lt;/script&gt;');
    expect(markup).toContain('&lt;img src=x onerror=alert(1)&gt;');
    expect(markup).not.toContain('<script>');
    expect(markup).not.toContain('<img src=x');
    expect(markup).toContain('Main branch');
    expect(markup).toContain('Main POS');
    expect(markup).toContain('VOID_REASON_INPUT_BLOCKED');
    expect(markup).toContain('The input fix is ready');
    expect(markup).toContain('Do not paste passwords, tokens, payment credentials');
  });

  it('keeps triage controls locked until the current detail request completes', () => {
    const markup = renderToStaticMarkup(
      <ReportDetail
        report={report}
        detailLoading
        detailReady={false}
        error={null}
        statusDraft="acknowledged"
        noteDraft="Reviewing the report"
        noteTooLong={false}
        noteValidationError={null}
        saving={false}
        hasChanges
        onStatusChange={() => undefined}
        onNoteChange={() => undefined}
        onSave={() => undefined}
        replyDraft=""
        replying={false}
        onReplyChange={() => undefined}
        onReply={() => undefined}
        onOpenAttachment={() => undefined}
      />,
    );

    expect(markup).toMatch(/id="bug-report-detail-status"[^>]*disabled/);
    expect(markup).toMatch(/id="bug-report-resolution-note"[^>]*disabled/);
    expect(markup).toMatch(/type="submit"[^>]*disabled/);
    expect(markup).toContain('Refreshing details');
  });
});
