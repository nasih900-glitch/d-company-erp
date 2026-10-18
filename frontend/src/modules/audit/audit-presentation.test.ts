import { describe, expect, it } from 'vitest';
import {
  auditBusinessSummary,
  businessValue,
  auditActor,
} from './audit-presentation';
import type { BusinessAuditEntryDTO } from '@/lib/erp-api';
const event = (details: Record<string, unknown>) =>
  ({
    business_details: details,
    summary: 'Payment recorded',
    action_label: 'Payment recorded',
  }) as BusinessAuditEntryDTO;
describe('Source-backed business language', () => {
  it('summarizes retained amount and method without inventing a result', () => {
    expect(
      auditBusinessSummary(event({ amount_minor: 27000, method: 'upi' })),
    ).toBe('₹270.00 · UPI');
    expect(
      auditBusinessSummary(
        event({
          amount_minor: 27000,
          original_method: 'cash',
          effective_method: 'upi',
        }),
      ),
    ).toContain('Cash → UPI');
  });
  it('uses retained stock quantity/unit and makes missing names explicit', () => {
    expect(
      auditBusinessSummary(event({ qty_delta: 2, base_unit: 'bottle' })),
    ).toBe('Item name not retained · +2 bottle');
    expect(
      auditBusinessSummary(
        event({
          qty_delta: -1,
          base_unit: 'bottle',
          ingredient_name_snapshot: 'Retained water',
        }),
      ),
    ).toBe('Retained water · -1 bottle');
  });
  it('renders structured role changes as readable before and after values', () => {
    expect(
      auditBusinessSummary(
        event({
          before_roles: ['cashier'],
          after_roles: ['cashier', 'manager'],
        }),
      ),
    ).toBe('Roles: Cashier → Cashier · Manager');
    expect(businessValue('before_roles', [])).toBe('No roles');
  });
  it('formats play duration and retained timestamps explicitly in the supplied shop zone', () => {
    expect(businessValue('play_elapsed_ms', 3661000)).toBe(
      '1 hr 1 min 1 sec playing time',
    );
    expect(
      businessValue('joined_at', '2026-10-18T12:00:00Z', 'Asia/Kolkata'),
    ).toContain('5:30:00 pm · Asia/Kolkata');
  });
  it('never fills missing actor identity or additional facts from a current lookup', () => {
    expect(
      auditActor({
        actor_user_id: null,
        actor_kind: 'unknown',
        actor_name: 'Target account',
      } as BusinessAuditEntryDTO),
    ).toBe('Requester not verified');
    expect(auditBusinessSummary(event({}))).toBe(
      'No additional business facts retained',
    );
  });
});
