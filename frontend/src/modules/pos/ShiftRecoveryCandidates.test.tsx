import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import type { ShiftDTO, ShiftRecoveryCandidateDTO } from '@/lib/erp-api';
import {
  AndroidShiftRecoveryCandidatesPanel,
  canReviewAndroidShiftRecoveryCandidates,
  isShiftRecoveryAuthorizationRejection,
  recoveryFailureRequiresExactReplay,
  refreshShiftRecoverySurfaces,
} from './OrdersAndShiftsScreen';

function candidate(
  overrides: Partial<ShiftRecoveryCandidateDTO> = {},
): ShiftRecoveryCandidateDTO {
  return {
    id: 'shift-other',
    branch_id: 'branch-1',
    terminal_id: 'terminal-2',
    terminal_name: 'Gaming tablet',
    terminal_device_id: 'tablet-device-2',
    terminal_is_active: true,
    opened_at: '2026-09-21T09:00:00Z',
    opened_by: 'sameer-user-id',
    opened_by_name: 'Sameer',
    opening_float_minor: 50_000,
    expected_minor: 82_000,
    opening_protocol_revision: 1,
    opening_client_platform: 'android',
    opening_client_installation_recorded: true,
    ...overrides,
  };
}

describe('other-terminal Android shift recovery authorization', () => {
  it('requires both audit access and the exact close-shift permission', () => {
    expect(canReviewAndroidShiftRecoveryCandidates({
      audit_access: true,
      effective_permissions: ['pos.shift.close'],
    })).toBe(true);
    expect(canReviewAndroidShiftRecoveryCandidates({
      audit_access: true,
      effective_permissions: ['pos.read'],
    })).toBe(false);
    expect(canReviewAndroidShiftRecoveryCandidates({
      audit_access: false,
      effective_permissions: ['pos.shift.close'],
    })).toBe(false);
  });

  it('renders no protected surface for an unauthorized user', () => {
    const markup = renderToStaticMarkup(
      <AndroidShiftRecoveryCandidatesPanel
        authorized={false}
        currentTerminalId="terminal-1"
        candidates={[candidate()]}
        onRecover={vi.fn()}
      />,
    );

    expect(markup).toBe('');
  });
});

describe('other-terminal Android shift recovery presentation', () => {
  it('shows only other-terminal candidates with exact opener, time, workspace and source warnings', () => {
    const markup = renderToStaticMarkup(
      <AndroidShiftRecoveryCandidatesPanel
        authorized
        currentTerminalId="terminal-1"
        candidates={[
          candidate({
            id: 'shift-current',
            terminal_id: 'terminal-1',
            terminal_name: 'Current register',
          }),
          candidate(),
        ]}
        onRecover={vi.fn()}
      />,
    );

    expect(markup).toContain('Other-terminal Android shifts need review');
    expect(markup).toContain('Other terminal');
    expect(markup).toContain('Gaming tablet');
    expect(markup).toContain('Opened by <strong class="text-fg">Sameer</strong>');
    expect(markup).toContain('21 Sept 2026');
    expect(markup).toContain('2:30 pm IST');
    expect(markup).toContain('Source Android app');
    expect(markup).toContain('Installation identity recorded');
    expect(markup).toContain('Registered device tablet-device-2');
    expect(markup).toContain('another terminal&#x27;s shift');
    expect(markup).toContain('₹500.00');
    expect(markup).toContain('₹820.00');
    expect(markup).toContain('aria-label="Recover other-terminal Android shift on Gaming tablet opened by Sameer"');
    expect(markup).not.toContain('Current register');
  });

  it('surfaces candidate-load failures instead of looking like an empty result', () => {
    const markup = renderToStaticMarkup(
      <AndroidShiftRecoveryCandidatesPanel
        authorized
        currentTerminalId="terminal-1"
        candidates={[]}
        error="Recovery candidates could not be refreshed."
        onRecover={vi.fn()}
      />,
    );

    expect(markup).toContain('role="alert"');
    expect(markup).toContain('Recovery candidates could not be refreshed.');
  });

  it('blocks recovery from a stale candidate while its refresh failure is visible', () => {
    const markup = renderToStaticMarkup(
      <AndroidShiftRecoveryCandidatesPanel
        authorized
        currentTerminalId="terminal-1"
        candidates={[candidate()]}
        error="Recovery candidates could not be refreshed."
        onRecover={vi.fn()}
      />,
    );

    const actionLabel = 'Recover Android shift</button>';
    const labelIndex = markup.lastIndexOf(actionLabel);
    const buttonStart = markup.lastIndexOf('<button', labelIndex);
    expect(labelIndex).toBeGreaterThan(-1);
    expect(markup.slice(buttonStart, markup.indexOf('>', buttonStart)))
      .toContain('disabled=""');
  });
});

describe('shift recovery refresh orchestration', () => {
  it('refreshes terminal history and the protected candidate list together', async () => {
    const history = [] as ShiftDTO[];
    const candidates = [candidate()];
    const refreshHistory = vi.fn(async () => history);
    const refreshCandidates = vi.fn(async () => candidates);

    await expect(refreshShiftRecoverySurfaces(
      refreshHistory,
      refreshCandidates,
      true,
    )).resolves.toEqual({ history, candidates });
    expect(refreshHistory).toHaveBeenCalledOnce();
    expect(refreshCandidates).toHaveBeenCalledOnce();
  });

  it('never invokes the protected candidate loader for an ordinary user', async () => {
    const history = [] as ShiftDTO[];
    const refreshHistory = vi.fn(async () => history);
    const refreshCandidates = vi.fn(async () => [candidate()]);

    await expect(refreshShiftRecoverySurfaces(
      refreshHistory,
      refreshCandidates,
      false,
    )).resolves.toEqual({ history, candidates: [] });
    expect(refreshHistory).toHaveBeenCalledOnce();
    expect(refreshCandidates).not.toHaveBeenCalled();
  });

  it('discards protected state only for server-authoritative auth rejection', () => {
    const unauthorized = new Error('Session expired') as Error & { status?: number };
    unauthorized.status = 401;
    const forbidden = new Error('Permission revoked') as Error & { status?: number };
    forbidden.status = 403;
    const serverFailure = new Error('Gateway unavailable') as Error & { status?: number };
    serverFailure.status = 503;

    expect(isShiftRecoveryAuthorizationRejection(unauthorized)).toBe(true);
    expect(isShiftRecoveryAuthorizationRejection(forbidden)).toBe(true);
    expect(isShiftRecoveryAuthorizationRejection(serverFailure)).toBe(false);
    expect(isShiftRecoveryAuthorizationRejection(new Error('Network request failed'))).toBe(false);
  });

  it('locks an ambiguous recovery for exact replay instead of inferring success from shift history', () => {
    const gatewayTimeout = new Error('Gateway timeout') as Error & { status?: number };
    gatewayTimeout.status = 504;
    const definitiveConflict = new Error('Shift already closed') as Error & { status?: number };
    definitiveConflict.status = 409;

    expect(recoveryFailureRequiresExactReplay(gatewayTimeout)).toBe(true);
    expect(recoveryFailureRequiresExactReplay(new Error('Network request failed'))).toBe(true);
    expect(recoveryFailureRequiresExactReplay(definitiveConflict)).toBe(false);
  });
});
