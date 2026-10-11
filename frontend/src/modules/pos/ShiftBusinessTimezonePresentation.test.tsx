import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { NotificationProvider } from '@/components/ui/Notifications';
import type { ShiftDTO } from '@/lib/erp-api';
import {
  ExistingShiftFeedback,
  OpenShiftStatusPanel,
  RecoverAndroidShiftForm,
} from './OrdersAndShiftsScreen';

function shift(): ShiftDTO {
  return {
    id: 'shift-1',
    branch_id: 'branch-1',
    terminal_id: 'terminal-1',
    status: 'open',
    opened_at: '2026-09-21T21:06:59Z',
    closed_at: null,
    opening_float_minor: 50_000,
    expected_minor: 50_000,
    counted_minor: null,
    variance_minor: null,
    pos_sales_minor: 0,
    membership_sales_minor: 0,
    gross_collections_minor: 0,
    settled_pos_refunds_minor: 0,
    settled_membership_refunds_minor: 0,
    total_refunds_minor: 0,
    net_collections_minor: 0,
    total_sales_minor: 0,
    opened_by: 'owner-1',
    opened_by_name: 'Owner',
    opened_by_email: null,
    opening_protocol_revision: 1,
    opening_client_platform: 'android',
  };
}

function expectBusinessTime(markup: string): void {
  expect(markup).toContain('22 Sept 2026');
  expect(markup).toContain('IST');
  expect(markup).not.toContain('21 Sept 2026');
}

describe('shift lifecycle business-time presentation', () => {
  it('renders the open-shift and existing-shift notices in shop time', () => {
    expectBusinessTime(renderToStaticMarkup(
      <OpenShiftStatusPanel
        shift={shift()}
        workspaceName="Combined register"
        currentUserId="owner-1"
        canClose
        onClose={vi.fn()}
      />,
    ));
    expectBusinessTime(renderToStaticMarkup(
      <ExistingShiftFeedback
        shift={shift()}
        workspaceName="Combined register"
        onContinue={vi.fn()}
      />,
    ));
  });

  it('renders protected Android recovery context in the same shop time', () => {
    const markup = renderToStaticMarkup(
      <NotificationProvider>
        <RecoverAndroidShiftForm
          shift={shift()}
          currentUserId="owner-1"
          currentStaffName="Owner"
          workspaceName="Combined register"
          onClose={vi.fn()}
          onSuccess={vi.fn()}
          onError={vi.fn()}
        />
      </NotificationProvider>,
    );

    expectBusinessTime(markup);
    expect(markup).toContain('opened this shift from the Android tablet');
  });
});
