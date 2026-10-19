import type { BusinessAuditEntryDTO } from '@/lib/erp-api';
import { inr } from '@/lib/inr';

export function shopTime(value: string | null, zone: string): string {
  if (!value) return 'Not recorded';
  try {
    return new Intl.DateTimeFormat('en-IN', {
      timeZone: zone,
      dateStyle: 'medium',
      timeStyle: 'medium',
    }).format(new Date(value));
  } catch {
    return 'Time unavailable';
  }
}
export function auditActor(entry: BusinessAuditEntryDTO): string {
  if (entry.actor_user_id && entry.actor_kind === 'unverified_legacy')
    return `Unverified legacy attribution · ${entry.actor_name || 'Recorded account identity'}`;
  if (
    !entry.actor_user_id ||
    /unauthenticated|unverified|unknown/.test(entry.actor_kind)
  )
    return 'Requester not verified';
  const label = entry.actor_name || 'Recorded account identity';
  if (entry.actor_kind === 'otp_approved_requester')
    return `OTP-approved requester · ${label}`;
  if (entry.actor_kind === 'requester_recorded')
    return `Recorded requester · ${label}`;
  if (entry.actor_kind === 'authenticated')
    return `Authenticated actor · ${label}`;
  return `Recorded actor (${businessLabel(entry.actor_kind)}) · ${label}`;
}
export function businessLabel(value: string): string {
  const labels: Record<string, string> = {
    qty_delta: 'Quantity change',
    qty: 'Quantity',
    base_unit: 'Stock unit',
    play_elapsed_ms: 'Accumulated playing time',
    before_roles: 'Roles before',
    after_roles: 'Roles after',
    original_method: 'Originally recorded method',
    effective_method: 'Effective payment method',
    before_cash_minor: 'Recorded Cash before',
    before_upi_minor: 'Recorded UPI before',
    actual_cash_minor: 'Owner-confirmed Cash',
    actual_upi_minor: 'Owner-confirmed UPI',
    cash_delta_minor: 'Cash classification change',
    upi_delta_minor: 'UPI classification change',
    cash_minor: 'Cash',
    upi_minor: 'UPI',
    source_allocation: 'Allocation evidence',
    evidence_kind: 'Evidence basis',
  };
  if (labels[value]) return labels[value];
  return value
    .replace(/_minor$/, '')
    .replaceAll('_', ' ')
    .replace(/^./, (character) => character.toUpperCase());
}
export function safeAuditValue(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(safeAuditValue);
  if (value && typeof value === 'object')
    return Object.fromEntries(
      Object.entries(value).map(([key, item]) => [
        key,
        /password|secret|token|otp|authorization|cookie|private_key/i.test(key)
          ? '[redacted]'
          : safeAuditValue(item),
      ]),
    );
  return value;
}
export function businessValue(
  key: string,
  value: unknown,
  zone = 'UTC',
): string {
  if (/password|secret|token|otp|authorization|cookie|private_key/i.test(key))
    return '[redacted]';
  if (value === undefined || value === null || value === '')
    return 'Not recorded';
  if (typeof value === 'number' && key.endsWith('_minor')) return inr(value);
  if (typeof value === 'boolean') return value ? 'Yes' : 'No';
  if (
    typeof value === 'string' &&
    [
      'method',
      'payment_method',
      'original_method',
      'effective_method',
    ].includes(key)
  )
    return value.toUpperCase();
  if (typeof value === 'string' && key.endsWith('_at'))
    return `${shopTime(value, zone)} · ${zone}`;
  if (typeof value === 'number' && key.endsWith('_ms')) {
    const seconds = Math.floor(value / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    return `${hours ? `${hours} hr ` : ''}${minutes % 60} min ${seconds % 60} sec playing time`;
  }
  if (Array.isArray(value))
    return value.length
      ? value
          .map((item) =>
            typeof item === 'string'
              ? businessLabel(item)
              : businessValue(key, item, zone),
          )
          .join(' · ')
      : key.includes('roles')
        ? 'No roles'
        : 'None retained';
  if (value && typeof value === 'object')
    return Object.entries(safeAuditValue(value) as Record<string, unknown>)
      .map(
        ([field, item]) =>
          `${businessLabel(field)}: ${businessValue(field, item, zone)}`,
      )
      .join('; ');
  if (key === 'source_allocation' && value === 'unallocated_owner_attestation')
    return 'Owner-confirmed totals; not linked to an individual payment';
  if (key === 'source_allocation' && value === 'identified_payment')
    return 'Linked to an identified payment';
  if (key === 'evidence_kind' && value === 'owner_attestation')
    return 'Owner confirmation';
  if (key === 'kind' && value === 'applied') return 'Applied';
  if (key === 'kind' && value === 'unresolved') return 'Unresolved';
  return String(value);
}
export function auditOrderId(entry: BusinessAuditEntryDTO): string | null {
  const id =
    entry.business_details?.order_id ??
    entry.after?.order_id ??
    entry.before?.order_id;
  if (typeof id === 'string' && /^[a-f0-9-]{36}$/i.test(id)) return id;
  return entry.entity_type === 'Order' ? entry.entity_id : null;
}

/** Display retained facts without classifying or upgrading the event outcome. */
export function auditBusinessSummary(entry: BusinessAuditEntryDTO): string {
  const facts = entry.business_details ?? {};
  const pieces: string[] = [];
  const amount =
    facts.amount_minor ?? facts.line_total_minor ?? facts.total_minor;
  if (typeof amount === 'number' && Number.isSafeInteger(amount))
    pieces.push(inr(amount));
  const method = facts.effective_method ?? facts.payment_method ?? facts.method;
  if (typeof method === 'string')
    pieces.push(
      typeof facts.original_method === 'string'
        ? `${businessLabel(facts.original_method)} → ${method.toUpperCase()}`
        : method.toUpperCase(),
    );
  if (facts.qty_delta !== undefined || facts.qty !== undefined) {
    const name =
      facts.menu_item_name ??
      facts.ingredient_name_snapshot ??
      facts.ingredient_name;
    pieces.push(typeof name === 'string' ? name : 'Item name not retained');
    const qty = facts.qty_delta ?? facts.qty;
    const unit = facts.base_unit ?? facts.unit;
    pieces.push(
      `${typeof qty === 'number' && qty > 0 && facts.qty_delta !== undefined ? '+' : ''}${String(qty)}${typeof unit === 'string' ? ` ${unit}` : ''}`,
    );
  }
  if (Array.isArray(facts.before_roles) && Array.isArray(facts.after_roles))
    pieces.push(
      `Roles: ${businessValue('before_roles', facts.before_roles)} → ${businessValue('after_roles', facts.after_roles)}`,
    );
  else for (const key of ['before_roles', 'after_roles']) {
    if (Array.isArray(facts[key])) pieces.push(`${businessLabel(key)}: ${businessValue(key, facts[key])}`);
  }
  if (typeof facts.duration_minutes === 'number')
    pieces.push(`${facts.duration_minutes} min`);
  return pieces.length
    ? pieces.join(' · ')
    : entry.summary === entry.action_label
      ? 'No additional business facts retained'
      : entry.summary;
}

const mutationActions = new Set([
  'update', 'staff_access_changed', 'loyalty_program_publish', 'gaming_timer_extended',
  'gaming.session.pause', 'gaming.session.resume', 'gaming_session_package_amended',
  'gaming_participant_left', 'gaming_participants_settled', 'gaming_session_addon_voided',
  'gaming_session_legacy_pause_resolution', 'gaming_session_billing_repair',
  'gaming_session_handoff_to_pos', 'gaming_session_reconcile_to_pos',
  'gaming_legacy_server_session_no_play_cancel', 'gaming_legacy_server_session_manual_bill_link',
  'pos_direct_order_hold_for_checkout', 'shift_closed', 'shift_android_origin_recovery_close',
  'payment_method_corrected', 'daily_collections_reconciled', 'expense_receipt_review',
  'bug_report_status_change', 'bug_report_resolution_note_change',
]);
function businessSnapshotField(key: string): boolean {
  return !/(^id$|_id$|_ids$|fingerprint|hash|password|secret|token|otp|authorization|cookie|private.?key|api.?key|access.?key|email|phone|client_action|request_id)/i.test(key) &&
    !['created_at', 'updated_at', 'auth_version', 'token_version'].includes(key);
}
/** Two-sided source-defined mutation evidence only; absent is never a recorded null. */
export function auditBusinessChanges(entry: BusinessAuditEntryDTO): Array<{key: string; before: unknown; after: unknown}> {
  if (!mutationActions.has(entry.action) || !entry.before || !entry.after) return [];
  const before = entry.before, after = entry.after;
  const changes: Array<{key: string; before: unknown; after: unknown}> = [];
  if (entry.action === 'staff_access_changed' && Array.isArray(before.before_roles) && Array.isArray(after.after_roles) && JSON.stringify(before.before_roles) !== JSON.stringify(after.after_roles))
    changes.push({key: 'roles', before: before.before_roles, after: after.after_roles});
  for (const key of Object.keys(before)) {
    if (Object.prototype.hasOwnProperty.call(after, key) && businessSnapshotField(key) && !['before_roles','after_roles'].includes(key) && JSON.stringify(before[key]) !== JSON.stringify(after[key]))
      changes.push({key, before: before[key], after: after[key]});
  }
  return changes;
}
export function auditRetainedSnapshots(entry: BusinessAuditEntryDTO): Array<{side: string; key: string; value: unknown}> {
  const changed = new Set(auditBusinessChanges(entry).map(item => item.key));
  return ([['Retained before action', entry.before], ['Retained after action', entry.after]] as const).flatMap(([side,snapshot]) =>
    Object.entries(snapshot ?? {}).filter(([key]) => businessSnapshotField(key) && !changed.has(key) && !(changed.has('roles') && ['before_roles','after_roles'].includes(key))).map(([key,value]) => ({side,key,value})));
}
