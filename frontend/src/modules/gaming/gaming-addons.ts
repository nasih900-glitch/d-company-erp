import type {
  GameSessionDTO,
  GamingSessionAddonCreateDTO,
  GamingSessionAddonDTO,
  GamingSessionAddonModifierSelectionDTO,
  MenuCategoryDTO,
  MenuItemDTO,
} from '@/lib/erp-api';
import { profileOperationalCatalogItems } from '@/lib/product-profile';

export type GamingAddonDraft = Omit<GamingSessionAddonCreateDTO, 'client_line_id'>;

export interface GamingAddonCreateAttempt {
  sessionId: string;
  idempotencyKey: string;
  body: GamingSessionAddonCreateDTO;
}

export interface GamingAddonVoidAttempt {
  sessionId: string;
  addonId: string;
  idempotencyKey: string;
  reason: string;
}

export function availableGamingAddonItems(
  items: readonly MenuItemDTO[],
  categories: readonly MenuCategoryDTO[],
): MenuItemDTO[] {
  return profileOperationalCatalogItems(items, categories)
    .sort((left, right) => left.name.localeCompare(right.name));
}

/**
 * Match the Gaming station grid's one-session-per-station precedence before
 * loading child ledgers. Without this reduction, a history query containing
 * hundreds of stopped rows would fan out into hundreds of add-on requests on
 * every realtime refresh even though only one row can be rendered per station.
 */
export function gridVisibleGamingSessions(
  active: readonly GameSessionDTO[],
  paused: readonly GameSessionDTO[],
  endedUnbilled: readonly GameSessionDTO[],
): GameSessionDTO[] {
  const byStation = new Map<string, GameSessionDTO>();
  for (const session of [...active, ...paused]) {
    if (!session.order_id) byStation.set(session.station_id, session);
  }
  for (const session of endedUnbilled) {
    if (!session.order_id && !byStation.has(session.station_id)) {
      byStation.set(session.station_id, session);
    }
  }
  return [...byStation.values()];
}

export function stagedAddonTotalMinor(addons: readonly GamingSessionAddonDTO[]): number {
  return addons.reduce(
    (total, addon) => total + (addon.voided_at ? 0 : addon.line_total_minor),
    0,
  );
}

/**
 * Financial completion must use a complete server ledger. A mutation receipt
 * proves only that one line was accepted; it never repairs a failed list load.
 */
export function isGamingAddonLedgerAuthoritative(
  sessionId: string,
  authoritativeSessionIds: ReadonlySet<string>,
  loadErrors: Readonly<Record<string, string>>,
): boolean {
  return authoritativeSessionIds.has(sessionId) && !loadErrors[sessionId];
}

export function catalogUnitPriceMinor(
  item: MenuItemDTO,
  variantId: string | null,
  modifiers: readonly GamingSessionAddonModifierSelectionDTO[],
): number {
  const variantDelta = (item.variants ?? [])
    .find((variant) => variant.id === variantId && variant.is_active)
    ?.price_delta_minor ?? 0;
  const optionPrices = new Map(
    (item.modifier_groups ?? [])
      .filter((group) => group.is_active)
      .flatMap((group) => group.options)
      .filter((option) => option.is_active)
      .map((option) => [option.id, option.price_delta_minor] as const),
  );
  const modifierDelta = modifiers.reduce(
    (total, selection) => total + (optionPrices.get(selection.modifier_id) ?? 0) * selection.qty,
    0,
  );
  return item.base_price_minor + variantDelta + modifierDelta;
}

export function validateGamingAddonDraft(
  item: MenuItemDTO | undefined,
  draft: GamingAddonDraft,
  eligibleItemIds: ReadonlySet<string>,
): string | null {
  if (!item || !item.is_available || !eligibleItemIds.has(item.id)) {
    return 'Select an available drink or snack.';
  }
  if (!Number.isInteger(draft.qty) || draft.qty < 1 || draft.qty > 100) {
    return 'Quantity must be between 1 and 100.';
  }

  const variants = (item.variants ?? []).filter((variant) => variant.is_active);
  if (variants.length > 0 && !variants.some((variant) => variant.id === draft.variant_id)) {
    return 'Select an available size or variant.';
  }
  if (variants.length === 0 && draft.variant_id) {
    return 'This item no longer has that variant. Select the item again.';
  }

  const selections = new Map<string, number>();
  for (const selection of draft.modifiers) {
    if (
      selections.has(selection.modifier_id)
      || !Number.isInteger(selection.qty)
      || selection.qty < 1
      || selection.qty > 100
    ) {
      return 'One or more modifier quantities are invalid.';
    }
    selections.set(selection.modifier_id, selection.qty);
  }

  const groups = (item.modifier_groups ?? []).filter((group) => group.is_active);
  const knownOptions = new Set(groups.flatMap((group) => (
    group.options.filter((option) => option.is_active).map((option) => option.id)
  )));
  if (draft.modifiers.some((selection) => !knownOptions.has(selection.modifier_id))) {
    return 'A selected modifier is no longer available. Select the item again.';
  }
  for (const group of groups) {
    const activeOptions = group.options.filter((option) => option.is_active);
    const selected = activeOptions.reduce(
      (total, option) => total + (selections.get(option.id) ?? 0),
      0,
    );
    if (selected < group.min_select || selected > group.max_select) {
      return `${group.name}: select between ${group.min_select} and ${group.max_select}.`;
    }
    for (const option of activeOptions) {
      if ((selections.get(option.id) ?? 0) > option.max_quantity) {
        return `${option.name}: choose no more than ${option.max_quantity}.`;
      }
    }
  }

  const expected = catalogUnitPriceMinor(item, draft.variant_id ?? null, draft.modifiers);
  if (!Number.isInteger(expected) || expected < 0 || expected > 9_999_999_999) {
    return 'This item has invalid catalogue pricing. Ask an owner to review Products.';
  }
  if (draft.expected_unit_price_minor !== expected) {
    return 'The displayed catalogue price changed. Select the item again.';
  }
  const note = draft.note?.trim() ?? '';
  if (note.length > 500) return 'Item note must be 500 characters or fewer.';
  return null;
}

export function createClientLineId(): string {
  if (typeof globalThis.crypto?.randomUUID === 'function') {
    return globalThis.crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof globalThis.crypto?.getRandomValues === 'function') {
    globalThis.crypto.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function resolveAddonCreateAttempt(
  current: GamingAddonCreateAttempt | null,
  sessionId: string,
  draft: GamingAddonDraft,
  factories: { clientLineId: () => string; idempotencyKey: () => string },
): GamingAddonCreateAttempt {
  if (current) {
    if (current.sessionId !== sessionId) {
      throw new Error('Confirm the pending Gaming item before adding one to another session.');
    }
    return current;
  }
  return {
    sessionId,
    idempotencyKey: factories.idempotencyKey(),
    body: {
      ...draft,
      modifiers: draft.modifiers.map((selection) => ({ ...selection })),
      note: draft.note?.trim() || null,
      client_line_id: factories.clientLineId(),
    },
  };
}

export function resolveAddonVoidAttempt(
  current: GamingAddonVoidAttempt | null,
  sessionId: string,
  addonId: string,
  reason: string,
  idempotencyKey: () => string,
): GamingAddonVoidAttempt {
  if (current) {
    if (current.sessionId !== sessionId || current.addonId !== addonId) {
      throw new Error('Confirm the pending item void before voiding another item.');
    }
    return current;
  }
  const normalized = reason.trim();
  if (normalized.length < 3 || normalized.length > 500) {
    throw new Error('Enter a void reason between 3 and 500 characters.');
  }
  return {
    sessionId,
    addonId,
    reason: normalized,
    idempotencyKey: idempotencyKey(),
  };
}

export function reconcileAddonCreateAttempt(
  attempt: GamingAddonCreateAttempt | null,
  visibleSessionIds: ReadonlySet<string>,
  loadedAddons: Readonly<Record<string, readonly GamingSessionAddonDTO[]>>,
): GamingAddonCreateAttempt | null {
  if (!attempt || !visibleSessionIds.has(attempt.sessionId)) return null;
  return loadedAddons[attempt.sessionId]?.some(
    (addon) => addon.client_line_id === attempt.body.client_line_id,
  ) ? null : attempt;
}

export function reconcileAddonVoidAttempt(
  attempt: GamingAddonVoidAttempt | null,
  visibleSessionIds: ReadonlySet<string>,
  loadedAddons: Readonly<Record<string, readonly GamingSessionAddonDTO[]>>,
): GamingAddonVoidAttempt | null {
  if (!attempt || !visibleSessionIds.has(attempt.sessionId)) return null;
  return loadedAddons[attempt.sessionId]?.some(
    (addon) => addon.id === attempt.addonId && addon.voided_at,
  ) ? null : attempt;
}
