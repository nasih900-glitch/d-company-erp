import { useMemo, useState } from 'react';
import { AlertCircle, Loader2, Minus, Plus, ShoppingBag, Trash2 } from 'lucide-react';

import Modal from '@/components/ui/Modal';
import { inr } from '@/lib/inr';
import type {
  GamingSessionAddonDTO,
  MenuItemDTO,
  MenuModifierGroupDTO,
} from '@/lib/erp-api';
import {
  catalogUnitPriceMinor,
  stagedAddonTotalMinor,
  validateGamingAddonDraft,
  type GamingAddonCreateAttempt,
  type GamingAddonDraft,
  type GamingAddonVoidAttempt,
} from './gaming-addons';

export function SessionAddonsPanel({
  addons,
  ready,
  error,
  canMutate,
  canAdd,
  catalogReady,
  pendingCreate,
  pendingVoidAddonId,
  busy,
  onAdd,
  onVoid,
}: {
  addons: GamingSessionAddonDTO[];
  ready: boolean;
  error: string | null;
  canMutate: boolean;
  canAdd: boolean;
  catalogReady: boolean;
  pendingCreate: boolean;
  pendingVoidAddonId: string | null;
  busy: boolean;
  onAdd: () => void;
  onVoid: (addon: GamingSessionAddonDTO) => void;
}) {
  const active = addons.filter((addon) => !addon.voided_at);
  const voided = addons.filter((addon) => addon.voided_at);
  const stagedTotal = stagedAddonTotalMinor(addons);

  return (
    <section className="mb-3 rounded-lg border border-bg-border bg-bg-raised/70 p-3">
      <div className="flex items-center justify-between gap-3">
        <div className="flex min-w-0 items-center gap-2">
          <ShoppingBag size={15} className="shrink-0 text-fg-muted"/>
          <div className="min-w-0">
            <div className="text-xs font-semibold">Drinks & snacks</div>
            <div className="text-[11px] text-fg-muted">
              {!ready ? 'Loading saved items…' : `${active.length} staged for the combined bill`}
            </div>
          </div>
        </div>
        <div className="shrink-0 text-right">
          <div className="font-mono text-sm font-bold">{inr(stagedTotal)}</div>
          <div className="text-[10px] text-fg-muted">staged total</div>
        </div>
      </div>

      {error && (
        <div className="mt-2 flex items-start gap-1.5 rounded-md border border-accent-bad/30 bg-accent-bad/10 p-2 text-[11px] text-accent-bad">
          <AlertCircle size={12} className="mt-0.5 shrink-0"/>
          <span>{error} Refresh before sending this session to POS.</span>
        </div>
      )}

      {ready && active.length > 0 && (
        <div className="mt-2 max-h-40 space-y-1.5 overflow-y-auto pr-0.5">
          {active.map((addon) => {
            const variantName = typeof addon.variant_snapshot?.name === 'string'
              ? addon.variant_snapshot.name
              : null;
            return (
              <div key={addon.id} className="flex min-h-11 items-center gap-2 rounded-md border border-bg-border bg-bg-surface px-2.5 py-2">
                <div className="min-w-0 flex-1">
                  <div className="truncate text-xs font-medium">
                    {addon.qty} × {addon.menu_item_name}{variantName ? ` · ${variantName}` : ''}
                  </div>
                  <div className="truncate text-[10px] text-fg-muted">
                    {addon.note || 'Added to this session'}
                  </div>
                </div>
                <span className="shrink-0 font-mono text-xs font-semibold">
                  {inr(addon.line_total_minor)}
                </span>
                {canMutate && (
                  <button
                    type="button"
                    className="flex min-h-11 min-w-11 items-center justify-center rounded-lg text-accent-bad hover:bg-accent-bad/10 disabled:opacity-40"
                    aria-label={`Void ${addon.menu_item_name}`}
                    title="Void this item with a reason"
                    disabled={busy || (pendingVoidAddonId !== null && pendingVoidAddonId !== addon.id)}
                    onClick={() => onVoid(addon)}
                  >
                    {pendingVoidAddonId === addon.id && busy
                      ? <Loader2 size={15} className="animate-spin"/>
                      : <Trash2 size={15}/>}
                  </button>
                )}
              </div>
            );
          })}
        </div>
      )}

      {ready && voided.length > 0 && (
        <details className="mt-2 text-[11px] text-fg-muted">
          <summary className="cursor-pointer select-none py-1">{voided.length} voided item{voided.length === 1 ? '' : 's'}</summary>
          <div className="space-y-1 pt-1">
            {voided.map((addon) => (
              <div key={addon.id} className="rounded-md border border-bg-border px-2 py-1.5 line-through">
                {addon.qty} × {addon.menu_item_name} · {addon.void_reason || 'Voided'}
              </div>
            ))}
          </div>
        </details>
      )}

      {canAdd && (
        <button
          type="button"
          className="btn btn-ghost mt-2 min-h-11 w-full"
          disabled={!ready || Boolean(error) || !catalogReady || busy}
          title={catalogReady ? undefined : 'Add an available drink or snack in Products first'}
          onClick={onAdd}
        >
          {pendingCreate ? <Loader2 size={14} className={busy ? 'animate-spin' : ''}/> : <Plus size={14}/>}
          {pendingCreate ? 'Review pending item' : 'Add drink or snack'}
        </button>
      )}
    </section>
  );
}

export function SessionAddonPickerModal({
  stationName,
  items,
  attempt,
  busy,
  requestError,
  onSubmit,
  onClose,
}: {
  stationName: string;
  items: MenuItemDTO[];
  attempt: GamingAddonCreateAttempt | null;
  busy: boolean;
  requestError: string | null;
  onSubmit: (draft: GamingAddonDraft) => void;
  onClose: () => void;
}) {
  const frozen = Boolean(attempt);
  const initialItemId = attempt?.body.menu_item_id ?? items[0]?.id ?? '';
  const initialItem = items.find((item) => item.id === initialItemId);
  const initialVariantId = attempt?.body.variant_id
    ?? (initialItem?.variants ?? []).filter((variant) => variant.is_active)
      .sort((left, right) => left.sort_order - right.sort_order)[0]?.id
    ?? null;
  const [selectedItemId, setSelectedItemId] = useState(initialItemId);
  const [variantId, setVariantId] = useState<string | null>(initialVariantId);
  const [modifierQuantities, setModifierQuantities] = useState<Record<string, number>>(
    Object.fromEntries((attempt?.body.modifiers ?? []).map((selection) => [selection.modifier_id, selection.qty])),
  );
  const [qty, setQty] = useState(attempt?.body.qty ?? 1);
  const [note, setNote] = useState(attempt?.body.note ?? '');
  const [validation, setValidation] = useState<string | null>(null);
  const selectedItem = items.find((item) => item.id === selectedItemId);
  const eligibleItemIds = useMemo(() => new Set(items.map((item) => item.id)), [items]);
  const activeVariants = (selectedItem?.variants ?? [])
    .filter((variant) => variant.is_active)
    .sort((left, right) => left.sort_order - right.sort_order);
  const activeGroups = (selectedItem?.modifier_groups ?? [])
    .filter((group) => group.is_active)
    .sort((left, right) => left.sort_order - right.sort_order);
  const modifiers = useMemo(
    () => Object.entries(modifierQuantities)
      .filter(([, quantity]) => quantity > 0)
      .map(([modifier_id, quantity]) => ({ modifier_id, qty: quantity })),
    [modifierQuantities],
  );
  const unitPrice = selectedItem
    ? catalogUnitPriceMinor(selectedItem, variantId, modifiers)
    : 0;

  function selectItem(itemId: string) {
    const item = items.find((candidate) => candidate.id === itemId);
    const firstVariant = (item?.variants ?? [])
      .filter((variant) => variant.is_active)
      .sort((left, right) => left.sort_order - right.sort_order)[0];
    setSelectedItemId(itemId);
    setVariantId(firstVariant?.id ?? null);
    setModifierQuantities({});
    setValidation(null);
  }

  function adjustModifier(group: MenuModifierGroupDTO, optionId: string, delta: number) {
    setModifierQuantities((current) => {
      const option = group.options.find((candidate) => candidate.id === optionId);
      if (!option) return current;
      const groupTotal = group.options.reduce(
        (total, candidate) => total + (current[candidate.id] ?? 0),
        0,
      );
      const present = current[optionId] ?? 0;
      const next = Math.max(0, Math.min(option.max_quantity, present + delta));
      if (delta > 0 && groupTotal >= group.max_select) return current;
      return { ...current, [optionId]: next };
    });
    setValidation(null);
  }

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (attempt) {
      onSubmit(attempt.body);
      return;
    }
    const draft: GamingAddonDraft = {
      menu_item_id: selectedItemId,
      variant_id: variantId,
      modifiers,
      qty,
      expected_unit_price_minor: unitPrice,
      note: note.trim() || null,
    };
    const problem = validateGamingAddonDraft(selectedItem, draft, eligibleItemIds);
    if (problem) {
      setValidation(problem);
      return;
    }
    onSubmit(draft);
  }

  return (
    <Modal open onClose={() => { if (!busy) onClose(); }} title={`Add to ${stationName}`} size="lg">
      <form onSubmit={submit} className="space-y-4">
        {frozen && (
          <div className="flex items-start gap-2 rounded-lg border border-accent-gold/40 bg-accent-gold/10 p-3 text-xs text-accent-gold">
            <AlertCircle size={14} className="mt-0.5 shrink-0"/>
            The first response was not confirmed. This retry keeps the exact same item, line ID and receipt key so it cannot create a duplicate.
          </div>
        )}
        {(requestError || validation) && (
          <div className="flex items-start gap-2 rounded-lg border border-accent-bad/30 bg-accent-bad/10 p-3 text-xs text-accent-bad">
            <AlertCircle size={14} className="mt-0.5 shrink-0"/>
            {validation || requestError}
          </div>
        )}

        <div>
          <div className="mb-2 text-xs font-semibold text-fg-muted">Available drinks and snacks</div>
          <div className="grid max-h-52 grid-cols-1 gap-2 overflow-y-auto sm:grid-cols-2">
            {items.map((item) => (
              <button
                key={item.id}
                type="button"
                disabled={frozen || busy}
                onClick={() => selectItem(item.id)}
                className={`min-h-14 rounded-xl border p-3 text-left transition ${
                  selectedItemId === item.id
                    ? 'border-accent bg-accent/10'
                    : 'border-bg-border bg-bg-raised hover:border-fg-muted'
                }`}
              >
                <div className="flex items-center justify-between gap-2">
                  <span className="truncate text-sm font-semibold">{item.name}</span>
                  <span className="shrink-0 font-mono text-sm">{inr(item.base_price_minor)}</span>
                </div>
                <div className="mt-1 text-[10px] uppercase tracking-wide text-fg-muted">{item.type}</div>
              </button>
            ))}
          </div>
        </div>

        {activeVariants.length > 0 && (
          <div>
            <div className="mb-2 text-xs font-semibold text-fg-muted">Size or variant</div>
            <div className="flex flex-wrap gap-2">
              {activeVariants.map((variant) => (
                <button
                  key={variant.id}
                  type="button"
                  disabled={frozen || busy}
                  onClick={() => { setVariantId(variant.id); setValidation(null); }}
                  className={`chip min-h-11 ${variantId === variant.id ? '!border-accent !text-accent' : ''}`}
                >
                  {variant.name}{variant.price_delta_minor ? ` · ${inr(selectedItem!.base_price_minor + variant.price_delta_minor)}` : ''}
                </button>
              ))}
            </div>
          </div>
        )}

        {activeGroups.map((group) => {
          const options = group.options
            .filter((option) => option.is_active)
            .sort((left, right) => left.sort_order - right.sort_order);
          const groupTotal = options.reduce(
            (total, option) => total + (modifierQuantities[option.id] ?? 0),
            0,
          );
          return (
            <div key={group.id} className="rounded-xl border border-bg-border p-3">
              <div className="mb-2 flex items-center justify-between gap-2">
                <span className="text-xs font-semibold">{group.name}</span>
                <span className="text-[10px] text-fg-muted">
                  choose {group.min_select}–{group.max_select}
                </span>
              </div>
              <div className="space-y-2">
                {options.map((option) => {
                  const selectedQty = modifierQuantities[option.id] ?? 0;
                  return (
                    <div key={option.id} className="flex min-h-11 items-center gap-2 rounded-lg bg-bg-raised px-2">
                      <div className="min-w-0 flex-1 text-xs">
                        <div className="truncate">{option.name}</div>
                        {option.price_delta_minor !== 0 && (
                          <div className="text-[10px] text-fg-muted">+{inr(option.price_delta_minor)}</div>
                        )}
                      </div>
                      <button
                        type="button"
                        className="flex min-h-11 min-w-11 items-center justify-center rounded-lg border border-bg-border"
                        aria-label={`Remove ${option.name}`}
                        disabled={frozen || busy || selectedQty <= 0}
                        onClick={() => adjustModifier(group, option.id, -1)}
                      ><Minus size={14}/></button>
                      <span className="w-5 text-center font-mono text-sm">{selectedQty}</span>
                      <button
                        type="button"
                        className="flex min-h-11 min-w-11 items-center justify-center rounded-lg border border-bg-border"
                        aria-label={`Add ${option.name}`}
                        disabled={frozen || busy || selectedQty >= option.max_quantity || groupTotal >= group.max_select}
                        onClick={() => adjustModifier(group, option.id, 1)}
                      ><Plus size={14}/></button>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}

        <div className="grid grid-cols-1 gap-3 sm:grid-cols-[auto_1fr]">
          <div>
            <div className="mb-1 text-xs text-fg-muted">Quantity</div>
            <div className="flex items-center gap-2">
              <button type="button" className="btn btn-ghost min-h-11 min-w-11 !px-2"
                disabled={frozen || busy || qty <= 1} onClick={() => setQty((value) => Math.max(1, value - 1))}
                aria-label="Decrease quantity"><Minus size={15}/></button>
              <span className="w-8 text-center font-mono font-bold">{qty}</span>
              <button type="button" className="btn btn-ghost min-h-11 min-w-11 !px-2"
                disabled={frozen || busy || qty >= 100} onClick={() => setQty((value) => Math.min(100, value + 1))}
                aria-label="Increase quantity"><Plus size={15}/></button>
            </div>
          </div>
          <label className="block">
            <span className="text-xs text-fg-muted">Note (optional)</span>
            <input className="input mt-1 min-h-11" maxLength={500} disabled={frozen || busy}
              value={note} onChange={(event) => setNote(event.target.value)}
              placeholder="For example: hand to customer at station"/>
          </label>
        </div>

        <div className="rounded-xl border border-bg-border bg-bg-raised p-3">
          <div className="flex items-baseline justify-between gap-3">
            <span className="text-sm text-fg-muted">Catalogue total</span>
            <span className="font-mono text-xl font-bold">{inr(unitPrice * qty)}</span>
          </div>
          <div className="mt-1 text-[11px] text-fg-muted">
            The server verifies current catalogue pricing and calculates the final tax and discount snapshot.
          </div>
        </div>

        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-ghost min-h-11" disabled={busy} onClick={onClose}>Close</button>
          <button type="submit" className="btn btn-primary min-h-11" disabled={busy || (!selectedItem && !attempt)}>
            {busy ? <Loader2 size={15} className="animate-spin"/> : <ShoppingBag size={15}/>}
            {frozen ? 'Retry exact item' : 'Add to session'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

export function SessionAddonVoidModal({
  addon,
  attempt,
  busy,
  requestError,
  onSubmit,
  onClose,
}: {
  addon: GamingSessionAddonDTO;
  attempt: GamingAddonVoidAttempt | null;
  busy: boolean;
  requestError: string | null;
  onSubmit: (reason: string) => void;
  onClose: () => void;
}) {
  const frozen = Boolean(attempt);
  const [reason, setReason] = useState(attempt?.reason ?? '');
  const [validation, setValidation] = useState<string | null>(null);
  const normalizedLength = reason.trim().length;

  function submit(event: React.FormEvent) {
    event.preventDefault();
    if (normalizedLength < 3 || normalizedLength > 500) {
      setValidation('Enter a clear void reason between 3 and 500 characters.');
      return;
    }
    onSubmit(reason.trim());
  }

  return (
    <Modal open onClose={() => { if (!busy) onClose(); }} title={`Void ${addon.menu_item_name}`} size="sm">
      <form onSubmit={submit} className="space-y-4">
        <div className="rounded-lg border border-accent-bad/30 bg-accent-bad/10 p-3 text-sm">
          <div className="font-semibold">{addon.qty} × {addon.menu_item_name}</div>
          <div className="mt-1 font-mono text-accent-bad">{inr(addon.line_total_minor)}</div>
          <div className="mt-1 text-xs text-fg-muted">The item remains in the audit trail and is excluded from the combined bill.</div>
        </div>
        {frozen && (
          <div className="text-xs text-accent-gold">
            Retrying the exact saved void request and receipt key; the reason cannot be changed until the server confirms it.
          </div>
        )}
        {(requestError || validation) && (
          <div className="flex items-start gap-2 text-xs text-accent-bad">
            <AlertCircle size={14} className="mt-0.5 shrink-0"/>
            {validation || requestError}
          </div>
        )}
        <label className="block">
          <span className="text-xs text-fg-muted">Void reason (required, at least 3 characters)</span>
          <textarea
            className="input mt-1 min-h-24 resize-y"
            autoFocus
            required
            minLength={3}
            maxLength={500}
            disabled={frozen || busy}
            value={reason}
            onChange={(event) => { setReason(event.target.value); setValidation(null); }}
            placeholder="For example: wrong can selected"
          />
          <span className="mt-1 block text-right text-[10px] text-fg-muted">{normalizedLength}/500</span>
        </label>
        <div className="flex justify-end gap-2">
          <button type="button" className="btn btn-ghost min-h-11" disabled={busy} onClick={onClose}>Close</button>
          <button type="submit" className="btn btn-danger min-h-11" disabled={busy || normalizedLength < 3 || normalizedLength > 500}>
            {busy ? <Loader2 size={15} className="animate-spin"/> : <Trash2 size={15}/>}
            {frozen ? 'Retry exact void' : 'Void item'}
          </button>
        </div>
      </form>
    </Modal>
  );
}
