import { describe, expect, it } from 'vitest';
import {
  auditBusinessSummary,
  auditBusinessChanges,
  auditRetainedSnapshots,
  businessValue,
  businessLabel,
  safeAuditValue,
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

describe('Snapshot evidence versus genuine mutations', () => {
  it('does not turn observed login, unlock, create or delete facts into a rename or role change', () => {
    for (const action of ['login_success','login_failed','audit_unlock_success','audit_unlock_failed','create']) {
      const row = {...event({}),action,before:null,after:{name:'Recorded staff',roles:['staff']}};
      expect(auditBusinessChanges(row)).toEqual([]);
      expect(auditRetainedSnapshots(row).map(item=>item.key)).toEqual(['name','roles']);
    }
    const row={...event({}),action:'delete',before:{name:'Deleted staff'},after:null};
    expect(auditBusinessChanges(row)).toEqual([]);
    expect(auditRetainedSnapshots(row)[0].value).toBe('Deleted staff');
  });
  it('normalizes the explicit role pair once and preserves known empty versus missing', () => {
    const row={...event({}),action:'staff_access_changed',before:{before_roles:[]},after:{after_roles:['owner']}};
    expect(auditBusinessChanges(row)).toEqual([{key:'roles',before:[],after:['owner']}]);
    expect(businessValue('roles',auditBusinessChanges(row)[0].before)).toBe('No roles');
    expect(auditRetainedSnapshots(row)).toEqual([]);
    expect(auditBusinessChanges({...row,before:{}})).toEqual([]);
    expect(auditRetainedSnapshots({...row,before:{}})[0].key).toBe('after_roles');
    expect(auditBusinessSummary(event({after_roles:['owner']}))).not.toContain('→');
  });
  it('shows only two-sided update fields as transitions and retains unmatched facts', () => {
    const row={...event({}),action:'update',before:{status:'open'},after:{status:'closed',name:'Observed'}};
    expect(auditBusinessChanges(row)).toEqual([{key:'status',before:'open',after:'closed'}]);
    expect(auditRetainedSnapshots(row)).toEqual([{side:'Retained after action',key:'name',value:'Observed'}]);
  });
});


describe('Retained finance evidence vocabulary', () => {
  it('labels only known field and value pairs without changing their meaning', () => {
    const pairs = [
      ['source_allocation', 'unallocated_owner_attestation', 'Owner-confirmed totals; not linked to an individual payment'],
      ['source_allocation', 'identified_payment', 'Linked to an identified payment'],
      ['evidence_kind', 'owner_attestation', 'Owner confirmation'],
      ['kind', 'applied', 'Applied'],
      ['kind', 'unresolved', 'Unresolved'],
    ];
    for (const [key, value, label] of pairs) {
      expect(businessValue(key, value)).toBe(label);
      expect(businessValue('note', value)).toBe(value);
    }
    expect(businessLabel('cash_minor')).toBe('Cash');
    expect(businessLabel('upi_minor')).toBe('UPI');
  });

  it('preserves unknown values and distinguishes missing evidence', () => {
    expect(businessValue('source_allocation', 'future_allocation')).toBe('future_allocation');
    expect(businessValue('evidence_kind', 'identified_payment')).toBe('identified_payment');
    expect(businessValue('kind', 'future_result')).toBe('future_result');
    expect(businessValue('source_allocation', null)).toBe('Not recorded');
    expect(businessValue('evidence_kind', undefined)).toBe('Not recorded');
    expect(businessValue('kind', '')).toBe('Not recorded');
  });

  it('keeps the retained technical values after business formatting', () => {
    const raw = {
      source_allocation: 'unallocated_owner_attestation',
      evidence_kind: 'owner_attestation',
      kind: 'applied',
      before: { cash_minor: 3000, upi_minor: 0 },
    };
    const original = JSON.stringify(raw);
    expect(businessValue('facts', raw)).toContain('Owner confirmation');
    expect(JSON.stringify(raw)).toBe(original);
    expect(JSON.stringify(safeAuditValue(raw))).toBe(original);
  });
});
