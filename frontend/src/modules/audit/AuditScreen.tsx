/**
 * Audit Log — every create / update / delete in the system.
 *
 *  - Search across before/after JSON
 *  - Filter by entity (Order, Customer, MenuItem, …)
 *  - Filter by action (create, update, delete)
 *  - Click any row to see the full before-and-after JSON diff
 *
 * Powered by SQLAlchemy event listeners; the trail is auto-recorded on
 * every commit. No code change needed when new models join.
 */
import { useEffect, useState } from 'react';
import {
  ShieldCheck, Search, Loader2, AlertCircle, RefreshCw, Eye,
  Plus, Edit2, Trash2, KeyRound, LogIn, ShieldAlert, Lock,
} from 'lucide-react';

import { audit, type AuditEntryDTO, type AuditFacetsDTO } from '@/lib/erp-api';
import { inr } from '@/lib/inr';
import Modal from '@/components/ui/Modal';

const ACTION_COLOR: Record<string, string> = {
  create: 'border-accent-good/40 text-accent-good',
  update: 'border-accent-gold/40 text-accent-gold',
  delete: 'border-accent-bad/40 text-accent-bad',
  login_success: 'border-accent-good/40 text-accent-good',
  login_failed: 'border-accent-bad/40 text-accent-bad',
  audit_unlock_success: 'border-accent/40 text-accent',
  audit_unlock_failed: 'border-accent-bad/40 text-accent-bad',
  pricing_unlock_success: 'border-accent/40 text-accent',
  pricing_unlock_failed: 'border-accent-bad/40 text-accent-bad',
};
const ACTION_ICON: Record<string, React.ReactNode> = {
  create: <Plus size={11}/>,
  update: <Edit2 size={11}/>,
  delete: <Trash2 size={11}/>,
  login_success: <LogIn size={11}/>,
  login_failed: <ShieldAlert size={11}/>,
  audit_unlock_success: <KeyRound size={11}/>,
  audit_unlock_failed: <ShieldAlert size={11}/>,
  pricing_unlock_success: <KeyRound size={11}/>,
  pricing_unlock_failed: <ShieldAlert size={11}/>,
};

const AREA_OPTIONS = [
  { value: '', label: 'All' },
  { value: 'login', label: 'Login' },
  { value: 'pos', label: 'POS & purchases' },
  { value: 'customers', label: 'Customers' },
  { value: 'staff', label: 'Users & staff' },
  { value: 'inventory', label: 'Inventory' },
  { value: 'finance', label: 'Finance' },
  { value: 'menu', label: 'Menu' },
  { value: 'operations', label: 'Operations' },
  { value: 'system', label: 'System' },
];

export default function AuditScreen() {
  const [auditToken, setAuditToken] = useState<string | null>(null);
  const [password, setPassword] = useState('');
  const [unlocking, setUnlocking] = useState(false);
  const [rows, setRows] = useState<AuditEntryDTO[]>([]);
  const [facets, setFacets] = useState<AuditFacetsDTO | null>(null);
  const [q, setQ] = useState('');
  const [area, setArea] = useState('');
  const [entityType, setEntityType] = useState('');
  const [action, setAction] = useState('');
  const [loading, setLoading] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [view, setView] = useState<AuditEntryDTO | null>(null);

  async function unlock(e: React.FormEvent) {
    e.preventDefault();
    setUnlocking(true); setErr(null);
    try {
      const r = await audit.unlock(password);
      setAuditToken(r.audit_token);
      setPassword('');
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setUnlocking(false);
    }
  }

  function lockAudit() {
    setAuditToken(null);
    setPassword('');
    setRows([]);
    setFacets(null);
    setView(null);
  }

  async function load(token = auditToken) {
    if (!token) return;
    setLoading(true); setErr(null);
    try {
      const [r, f] = await Promise.all([
        audit.list({
          q: q.trim() || undefined,
          area: area || undefined,
          entity_type: entityType || undefined,
          action: action || undefined,
          limit: 200,
        }, token),
        facets ? Promise.resolve(facets) : audit.facets(token),
      ]);
      setRows(r);
      if (!facets) setFacets(f as AuditFacetsDTO);
    } catch (e) {
      const message = (e as Error).message;
      setErr(message);
      if (message.toLowerCase().includes('audit password unlock')) {
        setAuditToken(null);
      }
    }
    finally { setLoading(false); }
  }
  useEffect(() => { if (auditToken) load(auditToken); }, [auditToken]);

  const onSearch = (e: React.FormEvent) => { e.preventDefault(); load(); };
  const visibleRows = area ? rows.filter((e) => auditArea(e) === area) : rows;
  const securityRows = visibleRows.filter((e) => auditArea(e) === 'login' || auditArea(e) === 'system').length;
  const purchaseRows = visibleRows.filter((e) => auditArea(e) === 'pos').length;
  const changeRows = visibleRows.filter((e) => ['create', 'update', 'delete'].includes(e.action)).length;

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold flex items-center gap-2">
            <ShieldCheck size={22} className="text-accent"/> Audit Log
          </h2>
          <p className="text-fg-muted text-sm">
            Every change to the system · who · when · what · before vs after
          </p>
        </div>
        <div className="flex gap-2">
          {auditToken && (
            <button className="btn btn-ghost" onClick={lockAudit}>
              <Lock size={14}/> Lock
            </button>
          )}
          <button className="btn btn-ghost" onClick={() => load()} disabled={!auditToken || loading}>
            <RefreshCw size={14}/> Refresh
          </button>
        </div>
      </header>

      {!auditToken ? (
        <form onSubmit={unlock} className="card max-w-sm space-y-4">
          <div className="flex items-center gap-2 font-semibold">
            <KeyRound size={16} className="text-accent"/> Unlock Audit Log
          </div>
          <label className="block">
            <span className="text-xs text-fg-muted">Current password</span>
            <input
              className="input mt-1"
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              autoFocus
            />
          </label>
          {err && (
            <div className="text-accent-bad text-sm flex items-center gap-2">
              <AlertCircle size={14}/> {err}
            </div>
          )}
          <button className="btn btn-primary w-full" disabled={unlocking || !password} type="submit">
            {unlocking ? 'Unlocking…' : 'Unlock'}
          </button>
        </form>
      ) : (
        <>
      {/* Filters */}
      <form
        onSubmit={onSearch}
        className="card mb-4 grid grid-cols-1 gap-3 sm:grid-cols-2 xl:grid-cols-[minmax(220px,1fr)_180px_180px_180px_auto] xl:items-end"
      >
        <div className="sm:col-span-2 xl:col-span-1">
          <span className="text-xs text-fg-muted">Search</span>
          <div className="flex items-center gap-2 mt-1">
            <Search size={14} className="text-fg-muted"/>
            <input value={q} onChange={(e) => setQ(e.target.value)}
              placeholder="customer name, invoice no., phone…"
              className="input !min-h-[38px] !py-2 flex-1"/>
          </div>
        </div>
        <div className="min-w-0">
          <span className="text-xs text-fg-muted">Area</span>
          <select className="input !min-h-[38px] !py-2 mt-1"
            value={area} onChange={(e) => setArea(e.target.value)}>
            {AREA_OPTIONS.map((opt) => (
              <option key={opt.value || 'all'} value={opt.value}>{opt.label}</option>
            ))}
          </select>
        </div>
        <div className="min-w-0">
          <span className="text-xs text-fg-muted">Entity</span>
          <select className="input !min-h-[38px] !py-2 mt-1"
            value={entityType} onChange={(e) => setEntityType(e.target.value)}>
            <option value="">All</option>
            {facets?.entity_types.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div className="min-w-0">
          <span className="text-xs text-fg-muted">Action</span>
          <select className="input !min-h-[38px] !py-2 mt-1"
            value={action} onChange={(e) => setAction(e.target.value)}>
            <option value="">All</option>
            {facets?.actions.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
        </div>
        <button type="submit" className="btn btn-primary w-full sm:col-span-2 xl:col-span-1">Find</button>
      </form>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <AuditMetric label="Shown" value={visibleRows.length.toString()}/>
        <AuditMetric label="Changes" value={changeRows.toString()}/>
        <AuditMetric label="Purchases" value={purchaseRows.toString()}/>
        <AuditMetric label="Security" value={securityRows.toString()}/>
      </div>

      {err && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {err}
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !visibleRows.length ? (
        <div className="card text-fg-muted text-sm">
          No audit entries match. Try clearing filters — or take some actions and reload.
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <div className="mobile-card-list md:hidden">
            {visibleRows.map((e) => {
              const summary = auditSummary(e);
              const entryArea = auditArea(e);
              return (
                <button
                  key={e.id}
                  type="button"
                  onClick={() => setView(e)}
                  className="mobile-record-card w-full text-left active:bg-bg-raised/40"
                >
                  <div className="flex items-start justify-between gap-3">
                    <div className="min-w-0">
                      <div className="font-mono text-[11px] text-fg-muted">
                        {new Date(e.created_at).toLocaleString('en-IN', {
                          day: '2-digit', month: 'short',
                          hour: '2-digit', minute: '2-digit', second: '2-digit',
                        })}
                      </div>
                      <div className="mt-1 font-semibold leading-snug">{summary.title}</div>
                    </div>
                    <span className={`chip shrink-0 text-[10px] ${ACTION_COLOR[e.action] ?? ''}`}>
                      {ACTION_ICON[e.action]} {actionLabel(e.action)}
                    </span>
                  </div>
                  <div className="mt-2 text-xs leading-relaxed text-fg-muted">
                    {summary.detail}
                  </div>
                  <div className="mt-3 grid grid-cols-2 gap-x-3 gap-y-2 text-xs">
                    <div className="min-w-0">
                      <div className="text-fg-muted">Who</div>
                      <div className="truncate font-medium">
                        {e.actor_name || 'system / script'}
                      </div>
                      {e.actor_email && (
                        <div className="truncate text-[10px] text-fg-muted">{e.actor_email}</div>
                      )}
                    </div>
                    <div>
                      <div className="text-fg-muted">Area</div>
                      <div className="font-medium">{areaLabel(entryArea)}</div>
                    </div>
                    <div>
                      <div className="text-fg-muted">Entity</div>
                      <div className="font-medium">{entityLabel(e.entity_type)}</div>
                    </div>
                    <div>
                      <div className="text-fg-muted">ID</div>
                      <div className="font-mono text-[10px] text-fg-muted">
                        {e.entity_id.slice(0, 8)}…
                      </div>
                    </div>
                  </div>
                </button>
              );
            })}
          </div>
          <div className="hidden md:block overflow-x-auto">
          <table className="w-full min-w-[960px] text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr>
                <th className="text-left p-3">Time</th>
                <th className="text-left p-3">Who</th>
                <th className="text-left p-3">What happened</th>
                <th className="text-left p-3">Action</th>
                <th className="text-left p-3">Area</th>
                <th className="text-left p-3">Entity</th>
                <th className="text-left p-3">ID</th>
                <th className="p-3"></th>
              </tr>
            </thead>
            <tbody>
              {visibleRows.map((e) => {
                const summary = auditSummary(e);
                return (
                  <tr key={e.id} className="border-t border-bg-border/60 hover:bg-bg-raised/30">
                    <td className="p-3 font-mono text-xs whitespace-nowrap">
                      {new Date(e.created_at).toLocaleString('en-IN', {
                        day: '2-digit', month: 'short',
                        hour: '2-digit', minute: '2-digit', second: '2-digit',
                      })}
                    </td>
                    <td className="p-3 min-w-[150px] max-w-[220px]">
                      {e.actor_name || <span className="text-fg-muted italic">system / script</span>}
                      {e.actor_email && (
                        <div className="truncate text-[10px] text-fg-muted">{e.actor_email}</div>
                      )}
                    </td>
                    <td className="p-3 max-w-[360px]">
                      <div className="font-medium">{summary.title}</div>
                      <div className="text-xs text-fg-muted">{summary.detail}</div>
                    </td>
                    <td className="p-3">
                      <span className={`chip text-[10px] ${ACTION_COLOR[e.action] ?? ''}`}>
                        {ACTION_ICON[e.action]} {actionLabel(e.action)}
                      </span>
                    </td>
                    <td className="p-3 text-xs text-fg-muted">{areaLabel(auditArea(e))}</td>
                    <td className="p-3 font-medium">{entityLabel(e.entity_type)}</td>
                    <td className="p-3 font-mono text-[10px] text-fg-muted">
                      {e.entity_id.slice(0, 8)}…
                    </td>
                    <td className="p-3 text-right">
                      <button onClick={() => setView(e)} className="text-fg-muted hover:text-accent">
                        <Eye size={14}/>
                      </button>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          </div>
        </div>
      )}

      {view && <AuditDetailModal entry={view} onClose={() => setView(null)}/>}
      </>
      )}
    </div>
  );
}

function AuditMetric({ label, value }: { label: string; value: string }) {
  return (
    <div className="card !p-3">
      <div className="text-[10px] text-fg-muted uppercase tracking-wider">{label}</div>
      <div className="text-xl font-bold mt-1">{value}</div>
    </div>
  );
}

const IGNORED_AUDIT_FIELDS = new Set(['updated_at', 'created_at']);
const SENSITIVE_AUDIT_FIELDS = new Set([
  'password',
  'password_hash',
  'current_password',
  'new_password',
  'token',
  'access_token',
  'refresh_token',
  'refresh_token_hash',
  'audit_token',
  'pricing_token',
]);

function auditArea(entry: AuditEntryDTO): string {
  if (entry.action.startsWith('login_')) return 'login';
  if (entry.entity_type === 'AuditAccess' || entry.entity_type === 'PricingAccess') return 'system';
  if (['User', 'UserRole', 'Role'].includes(entry.entity_type)) return 'staff';
  if (['Order', 'OrderLine', 'Payment', 'Refund', 'Shift'].includes(entry.entity_type)) return 'pos';
  if (['Customer', 'CustomerMembership', 'MembershipTier'].includes(entry.entity_type)) return 'customers';
  if (['Ingredient', 'Supplier', 'GRN'].includes(entry.entity_type)) return 'inventory';
  if (['Expense', 'ExpenseCategory', 'Partner', 'CapitalEntry'].includes(entry.entity_type)) return 'finance';
  if (['MenuCategory', 'MenuItem'].includes(entry.entity_type)) return 'menu';
  if (['Table', 'Floor', 'Reservation', 'Station', 'Event', 'EventTicket'].includes(entry.entity_type)) {
    return 'operations';
  }
  return 'system';
}

function areaLabel(area: string): string {
  return AREA_OPTIONS.find((opt) => opt.value === area)?.label ?? 'System';
}

function entityLabel(entityType: string): string {
  const labels: Record<string, string> = {
    AuditAccess: 'Audit access',
    PricingAccess: 'Pricing access',
    User: 'User account',
    UserRole: 'User role',
    Role: 'Role',
    Order: 'Order / bill',
    OrderLine: 'Order item',
    Payment: 'Payment',
    Refund: 'Refund',
    Shift: 'POS shift',
    Customer: 'Customer',
    CustomerMembership: 'Membership',
    MembershipTier: 'Membership plan',
    Ingredient: 'Ingredient',
    Supplier: 'Supplier',
    GRN: 'Stock received',
    Expense: 'Expense',
    ExpenseCategory: 'Expense category',
    Partner: 'Partner',
    CapitalEntry: 'Partner capital',
    MenuCategory: 'Menu category',
    MenuItem: 'Menu item',
    Table: 'Table',
    Floor: 'Floor',
    Reservation: 'Reservation',
    Station: 'Gaming / hookah station',
    Event: 'Event',
    EventTicket: 'Event ticket',
  };
  return labels[entityType] ?? entityType;
}

function actionLabel(action: string): string {
  const labels: Record<string, string> = {
    create: 'Created',
    update: 'Updated',
    delete: 'Deleted',
    login_success: 'Login success',
    login_failed: 'Login failed',
    audit_unlock_success: 'Audit unlocked',
    audit_unlock_failed: 'Audit unlock failed',
    pricing_unlock_success: 'Pricing unlocked',
    pricing_unlock_failed: 'Pricing unlock failed',
  };
  return labels[action] ?? action.replaceAll('_', ' ');
}

function fieldLabel(field: string): string {
  const labels: Record<string, string> = {
    name: 'Name',
    email: 'Email',
    phone: 'Phone',
    status: 'Status',
    password_hash: 'Password',
    failed_login_count: 'Failed login count',
    last_login_at: 'Last login time',
    locked_until: 'Locked until',
    deleted_at: 'Deleted at',
    total_minor: 'Total amount',
    subtotal_minor: 'Subtotal',
    discount_minor: 'Discount',
    tax_minor: 'Tax',
    cgst_minor: 'CGST',
    sgst_minor: 'SGST',
    igst_minor: 'IGST',
    amount_minor: 'Amount',
    customer_name: 'Customer name',
    customer_phone: 'Customer phone',
    kitchen_state: 'Kitchen status',
    is_available: 'Available for sale',
    base_price_minor: 'Price',
    rate_per_hour_minor: 'Hourly rate',
    current_qty: 'Current stock',
    reorder_threshold: 'Low-stock alert level',
    role_id: 'Role',
    user_id: 'User',
  };
  return labels[field] ?? field.replaceAll('_', ' ');
}

function changedFields(entry: AuditEntryDTO): string[] {
  if (!entry.before && !entry.after) return [];
  const keys = new Set<string>();
  Object.keys(entry.before ?? {}).forEach((k) => keys.add(k));
  Object.keys(entry.after ?? {}).forEach((k) => keys.add(k));
  return Array.from(keys).filter((key) => !IGNORED_AUDIT_FIELDS.has(key) && hasFieldChanged(entry, key));
}

function hasFieldChanged(entry: AuditEntryDTO, key: string): boolean {
  if (!entry.before || !entry.after) return true;
  return JSON.stringify(entry.before[key]) !== JSON.stringify(entry.after[key]);
}

function displayChangedFields(entry: AuditEntryDTO): string[] {
  return changedFields(entry);
}

function changeSummary(entry: AuditEntryDTO, fields: string[]): string {
  if (!fields.length) return 'Record changed';
  const parts = fields.slice(0, 3).map((field) => {
    if (SENSITIVE_AUDIT_FIELDS.has(field)) return `${fieldLabel(field)} changed`;
    return `${fieldLabel(field)}: ${formatAuditValue(entry.before?.[field], field)} -> ${formatAuditValue(entry.after?.[field], field)}`;
  });
  const extra = fields.length > 3 ? ` +${fields.length - 3} more` : '';
  return `${parts.join(', ')}${extra}`;
}

function formatAuditValue(value: unknown, key: string): string {
  if (SENSITIVE_AUDIT_FIELDS.has(key)) return '[hidden]';
  if (value === null || value === undefined || value === '') return 'blank';
  if (typeof value === 'number') {
    if (key.endsWith('_minor') || key.includes('price') || key.includes('rate_per_hour')) {
      return inr(value);
    }
    return String(value);
  }
  if (typeof value === 'boolean') return value ? 'yes' : 'no';
  if (typeof value === 'string') {
    const cleaned = cleanAuditText(value);
    return cleaned.length > 42 ? `${cleaned.slice(0, 39)}...` : cleaned;
  }
  return 'changed';
}

function textValue(entry: AuditEntryDTO, key: string): string | null {
  const value = entry.after?.[key] ?? entry.before?.[key];
  return typeof value === 'string' && value.trim() ? cleanAuditText(value) : null;
}

function cleanAuditText(value: string): string {
  return value.replaceAll('super_owner', 'owner').replaceAll('Super Owner', 'Owner');
}

function cleanAuditValue(value: unknown, key?: string): unknown {
  if (key && SENSITIVE_AUDIT_FIELDS.has(key)) return '[hidden]';
  if (typeof value === 'string') return cleanAuditText(value);
  if (Array.isArray(value)) return value.map((item) => cleanAuditValue(item));
  if (value && typeof value === 'object') {
    return Object.fromEntries(
      Object.entries(value as Record<string, unknown>).map(([key, val]) => [
        key,
        cleanAuditValue(val, key),
      ]),
    );
  }
  return value;
}

function auditJson(value: unknown, space?: number): string {
  return JSON.stringify(cleanAuditValue(value), null, space);
}

function auditSummary(entry: AuditEntryDTO): { title: string; detail: string } {
  const who = entry.actor_name || textValue(entry, 'email') || 'System';

  if (entry.action === 'login_success') {
    return {
      title: `${who} logged in`,
      detail: `Successful login for ${textValue(entry, 'email') || entry.actor_email || 'this user'}`,
    };
  }
  if (entry.action === 'login_failed') {
    return {
      title: 'Failed login attempt',
      detail: `${textValue(entry, 'email') || 'Unknown email'} · ${textValue(entry, 'reason') || 'invalid credentials'}`,
    };
  }
  if (entry.action === 'audit_unlock_success') {
    return { title: `${who} unlocked the Audit Log`, detail: 'Password check passed' };
  }
  if (entry.action === 'audit_unlock_failed') {
    return { title: `${who} tried to unlock the Audit Log`, detail: 'Password check failed' };
  }
  if (entry.action === 'pricing_unlock_success') {
    return { title: `${who} unlocked Pricing`, detail: 'Password check passed' };
  }
  if (entry.action === 'pricing_unlock_failed') {
    return { title: `${who} tried to unlock Pricing`, detail: 'Password check failed' };
  }

  const entity = entityLabel(entry.entity_type);
  const fields = changedFields(entry);
  const readableFields = fields.map(fieldLabel);

  if (entry.action === 'create') {
    return { title: `${entity} created`, detail: createDetail(entry, readableFields) };
  }
  if (entry.action === 'delete') {
    return { title: `${entity} deleted`, detail: readableFields.length ? readableFields.join(', ') : 'Record removed' };
  }
  if (entry.action === 'update') {
    return {
      title: `${entity} updated`,
      detail: changeSummary(entry, fields),
    };
  }
  return { title: `${entity} ${actionLabel(entry.action).toLowerCase()}`, detail: readableFields.join(', ') || 'Audit event' };
}

function createDetail(entry: AuditEntryDTO, readableFields: string[]): string {
  if (entry.entity_type === 'Order') return `Bill/order ${textValue(entry, 'invoice_no') || 'created'}`;
  if (entry.entity_type === 'Payment') return 'Payment recorded';
  if (entry.entity_type === 'Customer') return textValue(entry, 'phone') || 'Customer profile added';
  if (entry.entity_type === 'User') return textValue(entry, 'email') || 'User account added';
  if (entry.entity_type === 'Expense') return 'Expense recorded';
  if (entry.entity_type === 'GRN') return 'Stock received into inventory';
  return readableFields.length ? readableFields.join(', ') : 'Record added';
}

function AuditDetailModal({ entry, onClose }: { entry: AuditEntryDTO; onClose: () => void }) {
  const summary = auditSummary(entry);
  const changed = displayChangedFields(entry);
  return (
    <Modal open onClose={onClose}
      title={summary.title} size="lg">
      <div className="space-y-3 text-sm">
        <Row label="Summary" value={summary.detail}/>
        <Row label="Area" value={areaLabel(auditArea(entry))}/>
        <Row label="Action" value={actionLabel(entry.action)}/>
        <Row label="When" value={new Date(entry.created_at).toLocaleString('en-IN')}/>
        <Row label="Who" value={entry.actor_name ? `${entry.actor_name} (${entry.actor_email || '—'})` : 'system'}/>
        <Row label="Record" value={`${entityLabel(entry.entity_type)} · ${entry.entity_id}`}/>
        {entry.ip && <Row label="From IP" value={entry.ip}/>}

        {entry.action === 'update' && changed.length > 0 && (
          <div className="pt-3 border-t border-bg-border">
            <div className="text-xs text-fg-muted uppercase tracking-wider mb-2">Changed fields</div>
            <div className="overflow-x-auto">
            <table className="w-full min-w-[520px] text-xs">
              <thead className="text-fg-muted">
                <tr>
                  <th className="text-left p-2">Field</th>
                  <th className="text-left p-2">Before</th>
                  <th className="text-left p-2">After</th>
                </tr>
              </thead>
              <tbody>
                {changed.map((k) => (
                  <tr key={k} className="border-t border-bg-border/60">
                    <td className="p-2 font-medium">{fieldLabel(k)}</td>
                    <td className="p-2 font-mono">
                      <pre className="whitespace-pre-wrap break-all">{auditJson(entry.before![k])}</pre>
                    </td>
                    <td className="p-2 font-mono">
                      <pre className="whitespace-pre-wrap break-all text-accent-good">{auditJson(entry.after![k])}</pre>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            </div>
          </div>
        )}

        {entry.action === 'create' && entry.after && (
          <div className="pt-3 border-t border-bg-border">
            <div className="text-xs text-fg-muted uppercase tracking-wider mb-2">Created with</div>
            <pre className="bg-bg-raised p-3 rounded-lg text-[10px] overflow-x-auto font-mono">
{auditJson(entry.after, 2)}
            </pre>
          </div>
        )}

        {entry.action === 'delete' && entry.before && (
          <div className="pt-3 border-t border-bg-border">
            <div className="text-xs text-fg-muted uppercase tracking-wider mb-2">Deleted record was</div>
            <pre className="bg-bg-raised p-3 rounded-lg text-[10px] overflow-x-auto font-mono">
{auditJson(entry.before, 2)}
            </pre>
          </div>
        )}
      </div>
    </Modal>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex justify-between gap-3">
      <span className="shrink-0 text-fg-muted">{label}</span>
      <span className="min-w-0 break-words text-right font-medium">{value}</span>
    </div>
  );
}
