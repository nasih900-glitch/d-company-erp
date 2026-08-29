import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, describe, expect, it, vi } from 'vitest';

import { gaming } from '@/lib/erp-api';
import {
  createGamingWriteDispatcher,
  GAMING_WRITE_OPERATION_NAMES,
  GamingMutationButton,
  GamingWriteOnly,
} from './gaming-write-controls';

const notificationMocks = vi.hoisted(() => ({
  success: vi.fn(() => 'success'),
  info: vi.fn(() => 'info'),
  warning: vi.fn(() => 'warning'),
  error: vi.fn(() => 'error'),
  dismiss: vi.fn(),
  clear: vi.fn(),
}));

vi.mock('@/modules/auth/AuthContext', () => ({
  useAuth: () => ({
    me: {
      user_id: 'read-only-user',
      email: 'viewer@example.test',
      name: 'Gaming Viewer',
      roles: ['partner'],
      protected_access: false,
      audit_access: false,
      company_id: 'company-1',
      branch_id: 'branch-1',
      accessible_modules: ['gaming'],
      effective_permissions: ['gaming.read'],
    },
    terminalId: null,
    terminalReady: false,
    terminalOptions: [],
  }),
}));

vi.mock('@/components/ui/Notifications', () => ({
  useNotifications: () => notificationMocks,
}));

afterEach(() => {
  vi.restoreAllMocks();
});

describe('gaming write access boundary', () => {
  it('renders the real Gaming screen as view-only and omits management controls', async () => {
    const { default: GamingScreen } = await import('./GamingScreen');
    const markup = renderToStaticMarkup(<GamingScreen />);

    expect(markup).toContain('Gaming is view-only');
    expect(markup).toContain('does not have gaming.write');
    expect(markup).not.toContain('>Manage<');
    expect(markup).not.toContain('New station');
  });

  it('omits write-only controls and disables visible mutation controls for read-only users', () => {
    const hidden = renderToStaticMarkup(
      <GamingWriteOnly allowed={false}>
        <button type="button">Manage stations</button>
      </GamingWriteOnly>,
    );
    const disabled = renderToStaticMarkup(
      <GamingMutationButton canManageSessions={false} type="button">
        Start session
      </GamingMutationButton>,
    );

    expect(hidden).toBe('');
    expect(disabled).toMatch(/<button[^>]*disabled/);
    expect(disabled).toContain('Start session');

    const allowed = renderToStaticMarkup(
      <GamingMutationButton canManageSessions type="button">
        Start session
      </GamingMutationButton>,
    );
    expect(allowed).not.toContain('disabled');
  });

  it('does not dispatch any Gaming write API when gaming.write is absent', () => {
    const denied = vi.fn();
    const writeSpies = GAMING_WRITE_OPERATION_NAMES.map((operation) =>
      vi.spyOn(gaming, operation),
    );
    const dispatcher = createGamingWriteDispatcher(false, denied, gaming);

    expect(dispatcher.allowed).toBe(false);
    if (dispatcher.allowed) throw new Error('expected a denied dispatcher');
    dispatcher.dispatch('createStation', {
      code: 'RO-1',
      name: 'Read-only station',
      type: 'ps5',
      rate_per_hour_minor: 20_000,
    });
    dispatcher.dispatch('updateStation', 'station-1', { name: 'Changed' });
    dispatcher.dispatch('deleteStation', 'station-1');
    dispatcher.dispatch('startSession', {
      station_id: 'station-1',
      shift_id: 'shift-1',
      expected_rate_per_hour_minor: 20_000,
    }, 'start-key');
    dispatcher.dispatch('setSessionTimer', 'session-1', 30);
    dispatcher.dispatch('extendSessionTimer', 'session-1', 30, 15, 'timer-key');
    dispatcher.dispatch('extendSessionWithPackage', 'session-1', {
      id: 'package-1',
      price_minor: 5_000,
      duration_minutes: 30,
      variant: 'single',
    }, {
      timer_minutes: 30,
      amount_minor: 10_000,
    }, 'extension-key');
    dispatcher.dispatch('stopSession', 'session-1', 'stop-key');
    dispatcher.dispatch('repairSessionBilling', 'session-1', 10_000, 'Verified bill', 'repair-key');
    dispatcher.dispatch('cancelSession', 'session-1', 'Customer left');
    dispatcher.dispatch('sendToPos', 'session-1');
    dispatcher.dispatch('handoffToPos', 'session-1', 'target-shift-1');
    dispatcher.dispatch('reconcileToPos', 'session-1', 'shift-2', 'Recovered bill');

    expect(denied).toHaveBeenCalledOnce();
    expect(writeSpies).toHaveLength(GAMING_WRITE_OPERATION_NAMES.length);
    for (const writeSpy of writeSpies) {
      expect(writeSpy).not.toHaveBeenCalled();
    }
  });

  it('dispatches the selected API operation after gaming.write is authorized', async () => {
    const denied = vi.fn();
    const sendToPos = vi.spyOn(gaming, 'sendToPos').mockResolvedValue({
      order_id: 'order-1',
      amount_minor: 10_000,
    });
    const dispatcher = createGamingWriteDispatcher(true, denied, gaming);

    expect(dispatcher.allowed).toBe(true);
    if (!dispatcher.allowed) throw new Error('expected an authorized dispatcher');
    await dispatcher.dispatch('sendToPos', 'session-1');

    expect(denied).not.toHaveBeenCalled();
    expect(sendToPos).toHaveBeenCalledOnce();
    expect(sendToPos).toHaveBeenCalledWith('session-1');
  });
});
