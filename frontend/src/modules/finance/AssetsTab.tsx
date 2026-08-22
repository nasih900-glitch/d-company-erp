import { useCallback, useEffect, useState } from 'react';
import { AlertCircle, Loader2, Package, Plus, RefreshCw } from 'lucide-react';

import Modal from '@/components/ui/Modal';
import { finance, settings, type AssetDTO, type BranchDTO } from '@/lib/erp-api';
import { inr, inrShort } from '@/lib/inr';
import { rupeesToMinor } from '@/lib/manual-collections';

// ============================================================================
// FIXED ASSETS — equipment register (gaming, kitchen, furniture, ...),
// straight-line depreciated. See app/services/accounting/depreciation.py.
// ============================================================================
const ASSET_CATEGORIES: ReadonlyArray<{ value: string; label: string }> = [
  { value: 'kitchen_equipment', label: 'Kitchen equipment (fridge, fryer, oven...)' },
  { value: 'gaming_station', label: 'Gaming station / console' },
  { value: 'tv', label: 'TV / display' },
  { value: 'projector', label: 'Projector' },
  { value: 'furniture', label: 'Furniture' },
  { value: 'pos_hardware', label: 'POS hardware' },
  { value: 'ac_hvac', label: 'AC / HVAC' },
  { value: 'other', label: 'Other' },
];

function categoryLabel(type: string): string {
  return ASSET_CATEGORIES.find((c) => c.value === type)?.label ?? type;
}

export default function AssetsTab() {
  const [rows, setRows] = useState<AssetDTO[]>([]);
  const [branches, setBranches] = useState<BranchDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);

  const load = useCallback(async () => {
    setLoading(true);
    setErr(null);
    try {
      const [assets, branchRows] = await Promise.all([
        finance.listAssets(),
        settings.listBranches(),
      ]);
      setRows(assets);
      setBranches(branchRows);
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  if (loading) {
    return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading fixed assets…</div>;
  }

  const branchNames = new Map(branches.map((b) => [b.id, b.name]));
  const totalCost = rows.reduce((sum, r) => sum + r.purchase_minor, 0);
  const totalBookValue = rows.reduce((sum, r) => sum + r.book_value_minor, 0);
  const totalDepreciation = rows.reduce((sum, r) => sum + r.accumulated_depreciation_minor, 0);

  return (
    <div>
      <div className="card mb-4 border-accent-gold/40 bg-accent-gold/10 text-sm">
        <div className="flex items-start gap-2">
          <AlertCircle size={16} className="mt-0.5 shrink-0 text-accent-gold"/>
          <div>
            <b>Record every piece of equipment you buy here</b> — gaming stations, kitchen
            equipment, furniture, anything with lasting value. Book value depreciates
            straight-line over its useful life and flows automatically into P&amp;L and the
            partner distributable-profit calculation. Assets cannot be edited or deleted here yet.
          </div>
        </div>
      </div>

      <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
        <AssetStat label="Assets on register" value={String(rows.length)}/>
        <AssetStat label="Total purchase cost" value={inrShort(totalCost)} sub={inr(totalCost)}/>
        <AssetStat label="Accumulated depreciation" value={inrShort(totalDepreciation)} sub={inr(totalDepreciation)} tone="bad"/>
        <AssetStat label="Current book value" value={inrShort(totalBookValue)} sub={inr(totalBookValue)} tone="good"/>
      </div>

      <div className="flex justify-between items-center mb-3 flex-wrap gap-2">
        <p className="text-sm text-fg-muted">
          {rows.length} asset{rows.length === 1 ? '' : 's'} on the register
        </p>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load} aria-label="Refresh assets"><RefreshCw size={14}/></button>
          <button className="btn btn-primary" onClick={() => setAddOpen(true)} disabled={!branches.length}>
            <Plus size={14}/> Add asset
          </button>
        </div>
      </div>

      {!branches.length && (
        <div className="card border-accent-gold/40 bg-accent-gold/10 text-accent-gold text-sm mb-3">
          Add a branch in <b>Settings → Branches</b> before recording an asset.
        </div>
      )}
      {err && <ErrorRow text={err}/>}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No fixed assets recorded yet. Add gaming, kitchen, or furniture equipment as you buy it.
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="hidden w-full text-sm md:table">
            <thead className="bg-bg-raised">
              <tr>
                <th className="text-left p-3">Asset / branch</th>
                <th className="text-left p-3">Category</th>
                <th className="text-left p-3">Purchased</th>
                <th className="text-right p-3">Cost</th>
                <th className="text-right p-3">Book value</th>
                <th className="text-right p-3 pr-4">Useful life</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r) => (
                <tr key={r.id} className="border-b border-bg-border/60 last:border-0">
                  <td className="p-3">
                    <div className="font-semibold">{r.name}</div>
                    <div className="mt-1 text-xs text-fg-muted">{branchNames.get(r.branch_id) ?? 'Unknown branch'}</div>
                    {r.notes && <div className="mt-1 text-xs text-fg-muted break-words">{r.notes}</div>}
                  </td>
                  <td className="p-3"><span className="chip text-xs">{categoryLabel(r.type)}</span></td>
                  <td className="p-3 font-mono text-xs">{new Date(r.purchase_date).toLocaleDateString('en-IN')}</td>
                  <td className="p-3 text-right font-mono">{inr(r.purchase_minor)}</td>
                  <td className="p-3 text-right font-mono font-semibold">{inr(r.book_value_minor)}</td>
                  <td className="p-3 text-right pr-4 text-xs text-fg-muted">
                    {r.useful_life_months} mo
                  </td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className="mobile-card-list md:hidden">
            {rows.map((r) => (
              <div key={r.id} className="mobile-record-card">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="font-semibold break-words">{r.name}</div>
                    <div className="mt-1 text-xs text-fg-muted">
                      {branchNames.get(r.branch_id) ?? 'Unknown branch'} · {new Date(r.purchase_date).toLocaleDateString('en-IN')}
                    </div>
                    <span className="chip mt-2 text-[10px]">{categoryLabel(r.type)}</span>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-mono font-semibold">{inr(r.book_value_minor)}</div>
                    <div className="mt-1 text-[10px] text-fg-muted">Cost {inr(r.purchase_minor)}</div>
                  </div>
                </div>
                {r.notes && <div className="mt-2 text-xs text-fg-muted break-words">{r.notes}</div>}
                <div className="mt-3 border-t border-bg-border/60 pt-3 text-[10px] text-fg-muted">
                  Useful life {r.useful_life_months} months
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {addOpen && (
        <AssetForm
          branches={branches}
          onClose={() => setAddOpen(false)}
          onSuccess={() => { setAddOpen(false); void load(); }}
        />
      )}
    </div>
  );
}

function newAssetKey(): string {
  const randomPart = typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return `asset:${randomPart}`;
}

function AssetForm({
  branches,
  onClose,
  onSuccess,
}: {
  branches: BranchDTO[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [form, setForm] = useState({
    branch_id: branches[0]?.id ?? '',
    name: '',
    type: ASSET_CATEGORIES[0].value,
    purchase_rupees: '',
    purchase_date: new Date().toISOString().slice(0, 10),
    useful_life_months: '60',
    salvage_rupees: '0',
    notes: '',
  });
  const [idempotencyKey] = useState(newAssetKey);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    const purchaseMinor = rupeesToMinor(form.purchase_rupees);
    if (purchaseMinor === null) {
      setErr('Enter a purchase cost greater than ₹0 with no more than two decimal places.');
      return;
    }
    const salvageTrimmed = form.salvage_rupees.trim() || '0';
    const salvageMinor = salvageTrimmed === '0' ? 0 : rupeesToMinor(salvageTrimmed);
    if (salvageMinor === null) {
      setErr('Salvage value must be ₹0 or a valid amount with no more than two decimal places.');
      return;
    }
    if (salvageMinor > purchaseMinor) {
      setErr('Salvage value cannot exceed the purchase cost.');
      return;
    }
    const usefulLifeMonths = parseInt(form.useful_life_months, 10);
    if (!Number.isFinite(usefulLifeMonths) || usefulLifeMonths <= 0) {
      setErr('Useful life must be a whole number of months greater than 0.');
      return;
    }
    setBusy(true);
    setErr(null);
    try {
      await finance.createAsset({
        branch_id: form.branch_id,
        name: form.name.trim(),
        type: form.type,
        purchase_minor: purchaseMinor,
        purchase_date: new Date(form.purchase_date).toISOString(),
        useful_life_months: usefulLifeMonths,
        salvage_minor: salvageMinor,
        notes: form.notes.trim() || undefined,
      }, idempotencyKey);
      onSuccess();
    } catch (error) {
      setErr((error as Error).message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Modal open onClose={busy ? () => undefined : onClose} title="Add fixed asset">
      <form onSubmit={submit} className="space-y-3">
        <Field label="Asset name">
          <input className="input" required maxLength={200} autoFocus
            placeholder="e.g. Double-door commercial fridge"
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Branch">
            <select className="input" required value={form.branch_id}
              onChange={(e) => setForm({ ...form, branch_id: e.target.value })}>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label="Category">
            <select className="input" value={form.type}
              onChange={(e) => setForm({ ...form, type: e.target.value })}>
              {ASSET_CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
            </select>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Purchase cost (₹)">
            <input type="number" min="0.01" step="0.01" required inputMode="decimal"
              className="input font-mono text-right" value={form.purchase_rupees}
              onChange={(e) => setForm({ ...form, purchase_rupees: e.target.value })}/>
          </Field>
          <Field label="Purchase date">
            <input type="date" className="input" required max={new Date().toISOString().slice(0, 10)}
              value={form.purchase_date}
              onChange={(e) => setForm({ ...form, purchase_date: e.target.value })}/>
          </Field>
        </div>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Useful life (months)">
            <input type="number" min="1" step="1" required className="input font-mono"
              value={form.useful_life_months}
              onChange={(e) => setForm({ ...form, useful_life_months: e.target.value })}/>
            <span className="mt-1 block text-[10px] text-fg-muted">Default 60 months (5 years)</span>
          </Field>
          <Field label="Salvage value (₹)">
            <input type="number" min="0" step="0.01" inputMode="decimal"
              className="input font-mono text-right" value={form.salvage_rupees}
              onChange={(e) => setForm({ ...form, salvage_rupees: e.target.value })}/>
            <span className="mt-1 block text-[10px] text-fg-muted">Estimated resale value at end of life</span>
          </Field>
        </div>
        <Field label="Notes (optional)">
          <textarea className="input" rows={2} maxLength={500} value={form.notes}
            placeholder="Invoice reference, vendor, or anything worth remembering"
            onChange={(e) => setForm({ ...form, notes: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={busy}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Package size={14}/>}
            Add asset
          </button>
        </div>
      </form>
    </Modal>
  );
}

function AssetStat({
  label,
  value,
  sub,
  tone = 'default',
}: {
  label: string;
  value: string;
  sub?: string;
  tone?: 'default' | 'good' | 'bad';
}) {
  const color = tone === 'bad' ? 'text-accent-bad' : tone === 'good' ? 'text-accent-good' : 'text-fg';
  return (
    <div className="card">
      <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
      <div className={`text-2xl font-bold mt-1 ${color}`}>{value}</div>
      {sub && <div className="text-xs text-fg-muted mt-1 font-mono">{sub}</div>}
    </div>
  );
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="block text-xs text-fg-muted mb-1">{label}</span>
      {children}
    </label>
  );
}

function ErrorRow({ text }: { text: string }) {
  return (
    <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad">
      {text}
    </div>
  );
}
