import { TenderReceiptFacts } from '@/modules/finance/TenderCorrections';
import type { TenderReceiptDTO } from '@/lib/erp-api';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { IncidentReport } from './IncidentReports';
import { InstallationCard } from './DevicesUpdatesTab';
import type {
  ClientDiagnosticEventDTO,
  ClientInstallationDTO,
  ShiftDTO,
} from '@/lib/erp-api';
import { ShiftTenderEvidence } from '@/modules/pos/OrdersAndShiftsScreen';

describe('Build 51 truthful evidence labels', () => {
  it('shows reported occurrence and server receipt as distinct times, with exact reported build', () => {
    const markup = renderToStaticMarkup(
      <IncidentReport
        row={
          {
            id: 'event',
            installation_id: 'installation',
            event_type: 'anr',
            component: 'gaming',
            severity: 'error',
            occurred_at: '2026-10-17T01:00:00Z',
            received_at: '2026-10-19T02:00:00Z',
            version_code: 50,
            version_name: '3.1.42',
            reason_code: 'main_thread_stall',
            connectivity: 'offline',
          } as ClientDiagnosticEventDTO
        }
      />,
    );
    expect(markup).toContain('Build 50');
    expect(markup).toContain('unverified device clock');
    expect(markup).toContain('Server received');
    expect(markup).toContain('17 Oct');
    expect(markup).toContain('19 Oct');
    expect(markup).toContain('UTC');
  });
  it('does not turn a stale zero queue into a current clear queue or an installed-version claim', () => {
    const markup = renderToStaticMarkup(
      <InstallationCard
        installation={
          {
            installation_id: 'install-1',
            version_name: '3.1.42',
            version_code: 50,
            distribution_channel: 'direct',
            is_stale: true,
            pending_outbox_count: 0,
            update_state: 'idle',
            last_seen_at: '2026-10-17T10:00:00Z',
            last_successful_sync_at: null,
            last_user_name: 'Synthetic employee',
            terminal_name: 'Synthetic register',
          } as ClientInstallationDTO
        }
        serverTime="2026-10-19T10:00:00Z"
      />,
    );
    expect(markup).toContain('Last reported app version');
    expect(markup).toContain('current queue unknown');
    expect(markup).not.toContain('Queue clear');
    expect(markup).not.toContain('Installed version');
    expect(markup).toContain('historical telemetry');
  });
  it('separates original drawer records from reconstructed bill tender and daily actuals', () => {
    const markup = renderToStaticMarkup(
      <ShiftTenderEvidence
        shift={
          {
            tender_correction_minor: 27000,
            reconstructed_cash_collections_minor: 6000,
            reconstructed_upi_collections_minor: 35000,
            reconstructed_expected_minor: 6000,
          } as ShiftDTO
        }
      />,
    );
    expect(markup).toContain('Recorded close preserved');
    expect(markup).toContain('physical count and variance stay unchanged');
    expect(markup).toContain('daily unallocated actuals are separate');
    expect(markup).toContain('₹60.00');
    expect(markup).toContain('₹350.00');
  });
  it('shows exact daily final actuals and residual even when unresolved financial deltas are null', () => {
    const receipt = {
      operation: 'daily_actuals_recorded',
      financial_effect: false,
      before_cash_minor: 33000,
      before_upi_minor: 8000,
      actual_cash_minor: 30000,
      actual_upi_minor: 8000,
      cash_delta_minor: null,
      upi_delta_minor: null,
      total_difference_minor: -3000,
    } as TenderReceiptDTO;
    const markup = renderToStaticMarkup(
      <TenderReceiptFacts receipt={receipt} />,
    );
    expect(markup).toContain('₹330.00');
    expect(markup).toContain('₹300.00');
    expect(markup).toContain('₹80.00');
    expect(markup).toContain('₹-30.00');
    expect(markup).toContain('No financial posting');
    expect(markup).toContain('Total difference');
    expect(markup).not.toContain('Applied unallocated classification');
  });
  it('shows an applied daily classification separately from the unchanged total', () => {
    const receipt = {
      operation: 'daily_collections_reconciled',
      financial_effect: true,
      before_cash_minor: 33000,
      before_upi_minor: 8000,
      actual_cash_minor: 31000,
      actual_upi_minor: 10000,
      cash_delta_minor: -2000,
      upi_delta_minor: 2000,
      total_difference_minor: 0,
    } as TenderReceiptDTO;
    const markup = renderToStaticMarkup(
      <TenderReceiptFacts receipt={receipt} />,
    );
    expect(markup).toContain('Applied unallocated classification');
    expect(markup).toContain('+₹20.00');
    expect(markup).toContain('revenue unchanged');
    expect(markup).toContain('₹0.00');
  });
});
