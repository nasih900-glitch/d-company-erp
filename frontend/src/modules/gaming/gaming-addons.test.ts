import { describe, expect, it, vi } from 'vitest';

import type { GameSessionDTO, GamingSessionAddonDTO, MenuItemDTO } from '@/lib/erp-api';
import {
  availableGamingAddonItems,
  catalogUnitPriceMinor,
  createClientLineId,
  gridVisibleGamingSessions,
  isGamingAddonLedgerAuthoritative,
  reconcileAddonCreateAttempt,
  reconcileAddonVoidAttempt,
  resolveAddonCreateAttempt,
  resolveAddonVoidAttempt,
  stagedAddonTotalMinor,
  validateGamingAddonDraft,
} from './gaming-addons';

const drink: MenuItemDTO = {
  id: 'drink-1',
  category_id: 'category-1',
  sku: 'CAN-1',
  name: 'Cold can',
  type: 'drink',
  base_price_minor: 1_000,
  tax_rate: 0.05,
  hsn_code: '2202',
  price_includes_tax: true,
  is_available: true,
  variants: [
    { id: 'large', name: 'Large', price_delta_minor: 200, sort_order: 1, is_active: true },
  ],
  modifier_groups: [{
    id: 'extras',
    name: 'Extras',
    min_select: 1,
    max_select: 2,
    sort_order: 0,
    is_active: true,
    options: [{
      id: 'ice',
      modifier_group_id: 'extras',
      name: 'Ice',
      price_delta_minor: 25,
      max_quantity: 2,
      sort_order: 0,
      is_active: true,
    }],
  }],
};

function addon(overrides: Partial<GamingSessionAddonDTO> = {}): GamingSessionAddonDTO {
  return {
    id: 'addon-1',
    gaming_session_id: 'session-1',
    client_line_id: '11111111-1111-4111-8111-111111111111',
    menu_item_id: 'drink-1',
    menu_item_name: 'Cold can',
    menu_item_type: 'drink',
    variant_id: null,
    variant_snapshot: null,
    modifiers: [],
    qty: 1,
    catalog_unit_price_minor: 1_000,
    unit_price_minor: 1_000,
    line_total_minor: 1_000,
    discount_minor: 0,
    hsn_or_sac: '2202',
    tax_rate: 0.05,
    taxable_value_minor: 952,
    cgst_minor: 24,
    sgst_minor: 24,
    igst_minor: 0,
    cess_minor: 0,
    note: null,
    created_by: 'user-1',
    created_terminal_id: 'terminal-1',
    created_at: '2026-08-29T10:00:00Z',
    voided_at: null,
    voided_by: null,
    void_reason: null,
    ...overrides,
  };
}

function gamingSession(overrides: Partial<GameSessionDTO> = {}): GameSessionDTO {
  return {
    id: 'session-1',
    station_id: 'station-1',
    shift_id: 'shift-1',
    status: 'active',
    start_at: '2026-08-29T10:00:00Z',
    end_at: null,
    timer_minutes: null,
    timer_ends_at: null,
    paused_minutes: 0,
    billable_minutes: null,
    amount_minor: 0,
    rate_per_hour_minor: 20_000,
    order_id: null,
    cancel_reason: null,
    billing_mode: 'hourly',
    package_id: null,
    package_price_minor_snapshot: null,
    package_duration_minutes_snapshot: null,
    package_variant_snapshot: null,
    package_station_type_snapshot: null,
    extra_controllers: 0,
    ...overrides,
  };
}

describe('Gaming session add-ons', () => {
  it('keeps financial completion blocked after a mutation receipt until a full ledger reload succeeds', () => {
    const authoritative = new Set<string>();
    const loadErrors = { 'session-1': 'Full ledger request failed' };

    // Receiving one create/void response does not change either proof input.
    expect(isGamingAddonLedgerAuthoritative('session-1', authoritative, loadErrors)).toBe(false);

    const reloaded = new Set(['session-1']);
    expect(isGamingAddonLedgerAuthoritative('session-1', reloaded, loadErrors)).toBe(false);
    expect(isGamingAddonLedgerAuthoritative('session-1', reloaded, {})).toBe(true);
  });

  it('shows only available food, drink, and dessert products in name order', () => {
    const food = { ...drink, id: 'food-1', name: 'Crisps', type: 'food' };
    const gaming = { ...drink, id: 'gaming-1', name: 'PS5 time', type: 'gaming' };
    const unavailable = { ...drink, id: 'drink-2', name: 'Water', is_available: false };

    expect(availableGamingAddonItems([drink, gaming, unavailable, food]).map((item) => item.id))
      .toEqual(['drink-1', 'food-1']);
  });

  it('loads add-ons only for the one session actually visible on each station', () => {
    const active = gamingSession();
    const paused = gamingSession({
      id: 'paused-2',
      station_id: 'station-2',
      status: 'paused',
    });
    const ended = Array.from({ length: 500 }, (_, index) => gamingSession({
      id: `ended-${index}`,
      station_id: index === 0 ? 'station-3' : 'station-1',
      status: 'ended',
      end_at: '2026-08-29T11:00:00Z',
    }));

    expect(gridVisibleGamingSessions([active], [paused], ended).map((session) => session.id))
      .toEqual(['session-1', 'paused-2', 'ended-0']);
  });

  it('calculates the catalogue snapshot from base, variant, and modifier deltas', () => {
    expect(catalogUnitPriceMinor(drink, 'large', [{ modifier_id: 'ice', qty: 2 }]))
      .toBe(1_250);
  });

  it('validates required customisation before a write', () => {
    const missingModifier = {
      menu_item_id: drink.id,
      variant_id: 'large',
      modifiers: [],
      qty: 1,
      expected_unit_price_minor: 1_200,
      note: null,
    };
    expect(validateGamingAddonDraft(drink, missingModifier)).toBe(
      'Extras: select between 1 and 2.',
    );

    const valid = {
      ...missingModifier,
      modifiers: [{ modifier_id: 'ice', qty: 1 }],
      expected_unit_price_minor: 1_225,
    };
    expect(validateGamingAddonDraft(drink, valid)).toBeNull();
  });

  it('rejects an inactive modifier instead of silently pricing it at zero', () => {
    const item = {
      ...drink,
      modifier_groups: [{
        ...drink.modifier_groups![0],
        min_select: 0,
        options: [{ ...drink.modifier_groups![0].options[0], is_active: false }],
      }],
    };
    expect(validateGamingAddonDraft(item, {
      menu_item_id: item.id,
      variant_id: 'large',
      modifiers: [{ modifier_id: 'ice', qty: 1 }],
      qty: 1,
      expected_unit_price_minor: 1_200,
      note: null,
    })).toBe('A selected modifier is no longer available. Select the item again.');
  });

  it('excludes soft-voided lines from the staged total without hiding history', () => {
    expect(stagedAddonTotalMinor([
      addon(),
      addon({ id: 'addon-2', line_total_minor: 250, voided_at: '2026-08-29T10:05:00Z' }),
    ])).toBe(1_000);
  });

  it('reuses the exact create payload and key while an outcome is unresolved', () => {
    const clientLineId = vi.fn(() => '11111111-1111-4111-8111-111111111111');
    const idempotencyKey = vi.fn(() => 'gaming-addon-add:attempt-1');
    const draft = {
      menu_item_id: drink.id,
      variant_id: 'large',
      modifiers: [{ modifier_id: 'ice', qty: 1 }],
      qty: 1,
      expected_unit_price_minor: 1_225,
      note: '  At station  ',
    };

    const first = resolveAddonCreateAttempt(null, 'session-1', draft, {
      clientLineId,
      idempotencyKey,
    });
    draft.modifiers[0].qty = 2;
    const retry = resolveAddonCreateAttempt(first, 'session-1', draft, {
      clientLineId,
      idempotencyKey,
    });

    expect(retry).toBe(first);
    expect(retry.body.modifiers).toEqual([{ modifier_id: 'ice', qty: 1 }]);
    expect(retry.body.note).toBe('At station');
    expect(clientLineId).toHaveBeenCalledTimes(1);
    expect(idempotencyKey).toHaveBeenCalledTimes(1);
  });

  it('requires a meaningful void reason and retains it for a retry', () => {
    expect(() => resolveAddonVoidAttempt(
      null,
      'session-1',
      'addon-1',
      ' x ',
      () => 'gaming-addon-void:attempt-1',
    )).toThrow('between 3 and 500');

    const first = resolveAddonVoidAttempt(
      null,
      'session-1',
      'addon-1',
      ' Wrong can selected ',
      () => 'gaming-addon-void:attempt-1',
    );
    expect(resolveAddonVoidAttempt(
      first,
      'session-1',
      'addon-1',
      'changed reason',
      () => 'new-key',
    )).toBe(first);
    expect(first.reason).toBe('Wrong can selected');
  });

  it('always creates a backend-compatible UUID for the client line', () => {
    expect(createClientLineId()).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
    );
  });

  it('drops stale mutation receipts when a successful refresh proves the session is gone', () => {
    const createAttempt = resolveAddonCreateAttempt(null, 'session-1', {
      menu_item_id: drink.id,
      variant_id: 'large',
      modifiers: [{ modifier_id: 'ice', qty: 1 }],
      qty: 1,
      expected_unit_price_minor: 1_225,
      note: null,
    }, {
      clientLineId: () => '11111111-1111-4111-8111-111111111111',
      idempotencyKey: () => 'gaming-addon-add:attempt-1',
    });
    const voidAttempt = resolveAddonVoidAttempt(
      null,
      'session-1',
      'addon-1',
      'Wrong can selected',
      () => 'gaming-addon-void:attempt-1',
    );

    expect(reconcileAddonCreateAttempt(createAttempt, new Set(), {})).toBeNull();
    expect(reconcileAddonVoidAttempt(voidAttempt, new Set(), {})).toBeNull();
    expect(reconcileAddonCreateAttempt(
      createAttempt,
      new Set(['session-1']),
      { 'session-1': [] },
    )).toBe(createAttempt);
  });

  it('clears mutation receipts when the refreshed ledger contains their result', () => {
    const createAttempt = resolveAddonCreateAttempt(null, 'session-1', {
      menu_item_id: drink.id,
      variant_id: 'large',
      modifiers: [{ modifier_id: 'ice', qty: 1 }],
      qty: 1,
      expected_unit_price_minor: 1_225,
      note: null,
    }, {
      clientLineId: () => '11111111-1111-4111-8111-111111111111',
      idempotencyKey: () => 'gaming-addon-add:attempt-1',
    });
    const voidAttempt = resolveAddonVoidAttempt(
      null,
      'session-1',
      'addon-1',
      'Wrong can selected',
      () => 'gaming-addon-void:attempt-1',
    );
    const visible = new Set(['session-1']);
    const ledger = {
      'session-1': [addon({ voided_at: '2026-08-29T10:05:00Z' })],
    };

    expect(reconcileAddonCreateAttempt(createAttempt, visible, ledger)).toBeNull();
    expect(reconcileAddonVoidAttempt(voidAttempt, visible, ledger)).toBeNull();
  });
});
