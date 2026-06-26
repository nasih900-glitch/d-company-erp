/**
 * Inventory screen — full live CRUD.
 *
 * Live mode hits /api/v1/inventory/*; demo mode reads INGREDIENTS.
 *
 * Capabilities:
 *  - List ingredients with stock value, low-stock alert
 *  - Add ingredient (SKU, name, base unit, reorder threshold)
 *  - Edit ingredient
 *  - Delete ingredient (soft)
 *  - Record stock receipt (GRN) — multi-line, picks supplier, sets cost
 *  - Stock adjustment (waste / damage / count correction)
 *  - Manage suppliers
 *  - Inspect FIFO batches per ingredient
 */
import { useEffect, useMemo, useState } from 'react';
import {
  AlertTriangle, Boxes, Package, Plus, Edit2, Trash2, Truck,
  AlertCircle, Loader2, Minus, ListTree, Building2, RefreshCw,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { INGREDIENTS } from '@/lib/demo-data';
import { inr, inrShort } from '@/lib/inr';
import {
  inventory, settings, type IngredientDTO, type SupplierDTO, type BranchDTO,
} from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

type Tab = 'ingredients' | 'suppliers';

export default function InventoryScreen() {
  const [tab, setTab] = useState<Tab>('ingredients');
  const [ingredients, setIngredients] = useState<IngredientDTO[]>([]);
  const [suppliers, setSuppliers] = useState<SupplierDTO[]>([]);
  const [branches, setBranches] = useState<BranchDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [addIngOpen, setAddIngOpen] = useState(false);
  const [editIng, setEditIng] = useState<IngredientDTO | null>(null);
  const [grnOpen, setGrnOpen] = useState(false);
  const [adjustIng, setAdjustIng] = useState<IngredientDTO | null>(null);
  const [addSupOpen, setAddSupOpen] = useState(false);
  const [editSup, setEditSup] = useState<SupplierDTO | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) {
        const [ings, sups, brs] = await Promise.all([
          inventory.listIngredients(),
          inventory.listSuppliers().catch(() => []),
          settings.listBranches().catch(() => []),
        ]);
        setIngredients(ings);
        setSuppliers(sups);
        setBranches(brs);
      } else {
        setIngredients(INGREDIENTS.map((i) => ({
          id: i.id, sku: i.sku, name: i.name, base_unit: i.unit as 'ml' | 'g' | 'unit',
          current_qty: i.current, reorder_threshold: i.threshold,
          reorder_qty: 0, avg_cost_minor: i.cost_minor,
        })));
        setSuppliers([]); setBranches([]);
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load inventory');
    } finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  const stats = useMemo(() => {
    const value = ingredients.reduce((s, i) => s + i.current_qty * i.avg_cost_minor, 0);
    const low = ingredients.filter((i) => i.current_qty < i.reorder_threshold).length;
    return { value, low, total: ingredients.length };
  }, [ingredients]);
  const restockRows = useMemo(() => {
    return ingredients
      .filter((i) => i.reorder_threshold > 0 && i.current_qty < i.reorder_threshold)
      .sort((a, b) => (a.current_qty / a.reorder_threshold) - (b.current_qty / b.reorder_threshold))
      .slice(0, 5);
  }, [ingredients]);

  async function onDelete(i: IngredientDTO) {
    if (!confirm(`Delete ingredient '${i.name}'? Existing batches stay in audit trail.`)) return;
    try { await inventory.deleteIngredient(i.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }
  async function onDeleteSupplier(s: SupplierDTO) {
    if (!confirm(`Delete supplier '${s.name}'?`)) return;
    try { await inventory.deleteSupplier(s.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Inventory</h2>
          <p className="text-fg-muted text-sm">FIFO batches · recipe-driven deductions · low-stock alerts</p>
        </div>
        <div className="flex gap-2 flex-wrap">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          {tab === 'ingredients' && (
            <>
              <button className="btn btn-ghost" onClick={() => setAddIngOpen(true)}>
                <Plus size={14}/> New ingredient
              </button>
              <button className="btn btn-primary" onClick={() => setGrnOpen(true)}>
                <Truck size={14}/> Receive stock (GRN)
              </button>
            </>
          )}
          {tab === 'suppliers' && (
            <button className="btn btn-primary" onClick={() => setAddSupOpen(true)}>
              <Plus size={14}/> New supplier
            </button>
          )}
        </div>
      </header>

      <div className="grid grid-cols-2 md:grid-cols-3 gap-3 md:gap-4 mb-6">
        <Stat label="Stock value" value={inr(stats.value)} icon={<Boxes/>}/>
        <Stat label="Ingredients" value={stats.total.toString()} icon={<Package/>}/>
        <Stat label="Low stock" value={stats.low.toString()} icon={<AlertTriangle/>}
          tone={stats.low ? 'bad' : 'good'}/>
      </div>

      {restockRows.length > 0 && (
        <section className="card mb-6">
          <div className="flex items-center gap-2 font-semibold mb-3">
            <AlertTriangle size={16} className="text-accent-bad"/> Restock priority
          </div>
          <div className="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-5 gap-2">
            {restockRows.map((i) => (
              <button key={i.id} className="rounded-xl border border-accent-bad/30 bg-accent-bad/10 px-3 py-2 text-left"
                onClick={() => setAdjustIng(i)}>
                <div className="font-medium text-sm truncate">{i.name}</div>
                <div className="text-xs text-accent-bad font-mono mt-0.5">
                  {i.current_qty.toLocaleString('en-IN')} / {i.reorder_threshold.toLocaleString('en-IN')} {i.base_unit}
                </div>
                {i.reorder_qty > 0 && (
                  <div className="text-[10px] text-fg-muted mt-0.5">
                    Reorder target {i.reorder_qty.toLocaleString('en-IN')} {i.base_unit}
                  </div>
                )}
              </button>
            ))}
          </div>
        </section>
      )}

      <div className="scroll-strip flex gap-2 mb-4 border-b border-bg-border -mx-3 px-3 md:mx-0 md:px-0">
        <TabBtn active={tab === 'ingredients'} onClick={() => setTab('ingredients')}>
          <ListTree size={14}/> Ingredients
        </TabBtn>
        <TabBtn active={tab === 'suppliers'} onClick={() => setTab('suppliers')}>
          <Building2 size={14}/> Suppliers
        </TabBtn>
      </div>

      {error && <ErrorRow text={error}/>}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : tab === 'ingredients' ? (
        <IngredientsList
          rows={ingredients}
          onEdit={setEditIng}
          onAdjust={setAdjustIng}
          onDelete={onDelete}
        />
      ) : (
        <SuppliersList rows={suppliers} onEdit={setEditSup} onDelete={onDeleteSupplier}/>
      )}

      {addIngOpen && <IngredientForm onClose={() => setAddIngOpen(false)}
        onSuccess={() => { setAddIngOpen(false); load(); }}/>}
      {editIng && <IngredientForm ingredient={editIng} onClose={() => setEditIng(null)}
        onSuccess={() => { setEditIng(null); load(); }}/>}
      {grnOpen && <GRNForm ingredients={ingredients} suppliers={suppliers}
        branches={branches} onClose={() => setGrnOpen(false)}
        onSuccess={() => { setGrnOpen(false); load(); }}/>}
      {adjustIng && <AdjustmentForm ingredient={adjustIng} branches={branches}
        onClose={() => setAdjustIng(null)}
        onSuccess={() => { setAdjustIng(null); load(); }}/>}
      {addSupOpen && <SupplierForm onClose={() => setAddSupOpen(false)}
        onSuccess={() => { setAddSupOpen(false); load(); }}/>}
      {editSup && <SupplierForm supplier={editSup} onClose={() => setEditSup(null)}
        onSuccess={() => { setEditSup(null); load(); }}/>}
    </div>
  );
}

// ---------------------------------------------------------------- Lists
function IngredientsList({
  rows, onEdit, onAdjust, onDelete,
}: {
  rows: IngredientDTO[];
  onEdit: (i: IngredientDTO) => void;
  onAdjust: (i: IngredientDTO) => void;
  onDelete: (i: IngredientDTO) => void;
}) {
  const sorted = [...rows].sort((a, b) => {
    const ra = a.reorder_threshold > 0 ? a.current_qty / a.reorder_threshold : 99;
    const rb = b.reorder_threshold > 0 ? b.current_qty / b.reorder_threshold : 99;
    return ra - rb;
  });
  if (!sorted.length) {
    return <div className="card text-fg-muted text-sm">No ingredients yet. Click <b>New ingredient</b>.</div>;
  }
  return (
    <div className="card !p-0 overflow-hidden">
      <table className="w-full text-sm hidden lg:table">
        <thead className="bg-bg-raised">
          <tr>
            <th className="text-left p-3">SKU</th>
            <th className="text-left p-3">Ingredient</th>
            <th className="text-right p-3">Current</th>
            <th className="text-right p-3">Reorder at</th>
            <th className="p-3">Level</th>
            <th className="text-right p-3">Value</th>
            <th className="text-right p-3 pr-4">Actions</th>
          </tr>
        </thead>
        <tbody>
          {sorted.map((i) => {
            const denom = i.reorder_threshold > 0 ? i.reorder_threshold * 4 : 1;
            const pct = Math.min(100, (i.current_qty / denom) * 100);
            const low = i.current_qty < i.reorder_threshold;
            return (
              <tr key={i.id} className="border-b border-bg-border/60 last:border-0">
                <td className="p-3 font-mono text-xs text-fg-muted">{i.sku}</td>
                <td className="p-3 font-medium">{i.name}</td>
                <td className={`p-3 text-right font-mono ${low ? 'text-accent-bad' : ''}`}>
                  {i.current_qty.toLocaleString('en-IN')} {i.base_unit}
                </td>
                <td className="p-3 text-right text-fg-muted font-mono text-xs">
                  {i.reorder_threshold.toLocaleString('en-IN')} {i.base_unit}
                </td>
                <td className="p-3 w-40">
                  <div className="h-2 rounded-full bg-bg-raised overflow-hidden">
                    <div className={`h-full ${low ? 'bg-accent-bad' : pct < 40 ? 'bg-accent-gold' : 'bg-accent-good'}`}
                      style={{ width: `${pct}%` }}/>
                  </div>
                </td>
                <td className="p-3 text-right font-mono">{inrShort(i.current_qty * i.avg_cost_minor)}</td>
                <td className="p-3 text-right pr-4 flex gap-1 justify-end">
                  <button className="text-fg-muted hover:text-accent" title="Adjust stock"
                    onClick={() => onAdjust(i)}><Minus size={14}/></button>
                  <button className="text-fg-muted hover:text-accent" title="Edit"
                    onClick={() => onEdit(i)}><Edit2 size={14}/></button>
                  <button className="text-fg-muted hover:text-accent-bad" title="Delete"
                    onClick={() => onDelete(i)}><Trash2 size={14}/></button>
                </td>
              </tr>
            );
          })}
        </tbody>
      </table>
      <div className="mobile-card-list lg:hidden">
        {sorted.map((i) => {
          const low = i.current_qty < i.reorder_threshold;
          const denom = i.reorder_threshold > 0 ? i.reorder_threshold * 4 : 1;
          const pct = Math.min(100, (i.current_qty / denom) * 100);
          return (
            <div key={i.id} className="mobile-record-card">
              <div className="flex justify-between gap-2">
                <div className="min-w-0">
                  <div className="truncate text-sm font-semibold">{i.name}</div>
                  <div className="font-mono text-[10px] text-fg-muted">{i.sku}</div>
                </div>
                <div className="shrink-0 text-right">
                  <div className={`font-mono text-sm ${low ? 'text-accent-bad' : ''}`}>
                    {i.current_qty.toLocaleString('en-IN')} {i.base_unit}
                  </div>
                  <div className="text-[10px] text-fg-muted font-mono">{inrShort(i.current_qty * i.avg_cost_minor)}</div>
                </div>
              </div>
              <div className="mt-3">
                <div className="flex items-center justify-between text-[10px] text-fg-muted">
                  <span>Reorder at {i.reorder_threshold.toLocaleString('en-IN')} {i.base_unit}</span>
                  {low && <span className="text-accent-bad">Low stock</span>}
                </div>
                <div className="mt-1 h-2 overflow-hidden rounded-full bg-bg-raised">
                  <div
                    className={`h-full ${low ? 'bg-accent-bad' : pct < 40 ? 'bg-accent-gold' : 'bg-accent-good'}`}
                    style={{ width: `${pct}%` }}
                  />
                </div>
              </div>
              <div className="mt-3 flex flex-wrap justify-end gap-1">
                <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={() => onAdjust(i)}>Adjust</button>
                <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={() => onEdit(i)}>Edit</button>
                <button
                  aria-label={`Delete ${i.name}`}
                  className="btn btn-ghost !min-h-[32px] !min-w-[32px] !py-1 !px-2 text-xs hover:!text-accent-bad"
                  onClick={() => onDelete(i)}
                >
                  <Trash2 size={14}/>
                </button>
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}

function SuppliersList({
  rows, onEdit, onDelete,
}: {
  rows: SupplierDTO[];
  onEdit: (s: SupplierDTO) => void;
  onDelete: (s: SupplierDTO) => void;
}) {
  if (!rows.length) {
    return <div className="card text-fg-muted text-sm">No suppliers yet. Click <b>New supplier</b>.</div>;
  }
  return (
    <div className="card !p-0 overflow-hidden">
      <table className="hidden w-full text-sm md:table">
        <thead className="bg-bg-raised">
          <tr>
            <th className="text-left p-3">Name</th>
            <th className="text-left p-3">Contact</th>
            <th className="text-left p-3">GSTIN</th>
            <th className="text-left p-3">Terms</th>
            <th className="text-right p-3 pr-4">Actions</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((s) => (
            <tr key={s.id} className="border-b border-bg-border/60 last:border-0">
              <td className="p-3 font-medium">{s.name}</td>
              <td className="p-3 text-fg-muted">{s.contact || '—'}</td>
              <td className="p-3 font-mono text-xs text-fg-muted">{s.gstin || '—'}</td>
              <td className="p-3 text-fg-muted text-xs">{s.payment_terms || '—'}</td>
              <td className="p-3 text-right pr-4 flex gap-1 justify-end">
                <button className="text-fg-muted hover:text-accent" onClick={() => onEdit(s)}><Edit2 size={14}/></button>
                <button className="text-fg-muted hover:text-accent-bad" onClick={() => onDelete(s)}><Trash2 size={14}/></button>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
      <div className="mobile-card-list md:hidden">
        {rows.map((s) => (
          <div key={s.id} className="mobile-record-card">
            <div className="flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="truncate font-semibold">{s.name}</div>
                <div className="mt-1 text-xs text-fg-muted">
                  {s.contact || 'No contact saved'}
                </div>
              </div>
              <div className="flex shrink-0 gap-1">
                <button
                  aria-label={`Edit ${s.name}`}
                  className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1"
                  onClick={() => onEdit(s)}
                >
                  <Edit2 size={14}/>
                </button>
                <button
                  aria-label={`Delete ${s.name}`}
                  className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1 hover:!text-accent-bad"
                  onClick={() => onDelete(s)}
                >
                  <Trash2 size={14}/>
                </button>
              </div>
            </div>
            <div className="mt-3 grid grid-cols-2 gap-2 text-xs">
              <div className="min-w-0">
                <div className="text-fg-muted">GSTIN</div>
                <div className="truncate font-mono">{s.gstin || '—'}</div>
              </div>
              <div className="min-w-0">
                <div className="text-fg-muted">Payment terms</div>
                <div className="truncate">{s.payment_terms || '—'}</div>
              </div>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------- Forms
function IngredientForm({
  ingredient, onClose, onSuccess,
}: { ingredient?: IngredientDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!ingredient;
  const [form, setForm] = useState({
    sku: ingredient?.sku ?? '',
    name: ingredient?.name ?? '',
    base_unit: ingredient?.base_unit ?? 'g' as 'ml' | 'g' | 'unit',
    reorder_threshold: ingredient?.reorder_threshold ?? 0,
    reorder_qty: ingredient?.reorder_qty ?? 0,
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      if (isEdit) {
        await inventory.updateIngredient(ingredient!.id, {
          name: form.name.trim(),
          base_unit: form.base_unit,
          reorder_threshold: form.reorder_threshold,
          reorder_qty: form.reorder_qty,
        });
      } else {
        await inventory.createIngredient({
          sku: form.sku.trim(),
          name: form.name.trim(),
          base_unit: form.base_unit,
          reorder_threshold: form.reorder_threshold,
          reorder_qty: form.reorder_qty,
        });
      }
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${ingredient!.name}` : 'New ingredient'}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="SKU (e.g. MILK-1L, COFFEE-BEAN)">
          <input className="input" required disabled={isEdit} value={form.sku}
            onChange={(e) => setForm({ ...form, sku: e.target.value })}/>
        </Field>
        <Field label="Name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Base unit">
          <select className="input" value={form.base_unit}
            onChange={(e) => setForm({ ...form, base_unit: e.target.value as 'ml' | 'g' | 'unit' })}>
            <option value="g">g (grams)</option>
            <option value="ml">ml (millilitres)</option>
            <option value="unit">unit (each / piece)</option>
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Reorder at">
            <input type="number" className="input" min={0} step="0.01" value={form.reorder_threshold}
              onChange={(e) => setForm({ ...form, reorder_threshold: parseFloat(e.target.value) || 0 })}/>
          </Field>
          <Field label="Reorder qty">
            <input type="number" className="input" min={0} step="0.01" value={form.reorder_qty}
              onChange={(e) => setForm({ ...form, reorder_qty: parseFloat(e.target.value) || 0 })}/>
          </Field>
        </div>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function GRNForm({
  ingredients, suppliers, branches, onClose, onSuccess,
}: {
  ingredients: IngredientDTO[];
  suppliers: SupplierDTO[];
  branches: BranchDTO[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [supplierId, setSupplierId] = useState<string>('');
  const [branchId, setBranchId] = useState<string>(branches[0]?.id ?? '');
  const [invoiceNo, setInvoiceNo] = useState('');
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<Array<{
    ingredient_id: string; qty: string; unit_cost_rupees: string;
  }>>([{ ingredient_id: ingredients[0]?.id ?? '', qty: '', unit_cost_rupees: '' }]);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  function addLine() {
    setLines([...lines, { ingredient_id: ingredients[0]?.id ?? '', qty: '', unit_cost_rupees: '' }]);
  }
  function rmLine(idx: number) {
    setLines(lines.filter((_, i) => i !== idx));
  }
  function updLine(idx: number, patch: Partial<typeof lines[0]>) {
    setLines(lines.map((ln, i) => i === idx ? { ...ln, ...patch } : ln));
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!branchId) { setErr('select a branch'); return; }
    const linesOut = lines
      .filter((ln) => ln.ingredient_id && ln.qty)
      .map((ln) => ({
        ingredient_id: ln.ingredient_id,
        qty: parseFloat(ln.qty),
        unit_cost_minor: Math.round(parseFloat(ln.unit_cost_rupees || '0') * 100),
      }));
    if (!linesOut.length) { setErr('add at least one line'); return; }
    setBusy(true); setErr(null);
    try {
      await inventory.postGRN({
        branch_id: branchId,
        supplier_id: supplierId || undefined,
        supplier_invoice_no: invoiceNo || undefined,
        notes: notes || undefined,
        lines: linesOut,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title="Receive stock (GRN)" size="lg">
      <form onSubmit={submit} className="space-y-3">
        <div className="grid grid-cols-2 gap-3">
          <Field label="Branch">
            <select className="input" required value={branchId}
              onChange={(e) => setBranchId(e.target.value)}>
              <option value="">— select —</option>
              {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
            </select>
          </Field>
          <Field label="Supplier (optional)">
            <select className="input" value={supplierId}
              onChange={(e) => setSupplierId(e.target.value)}>
              <option value="">— none —</option>
              {suppliers.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </Field>
        </div>
        <Field label="Supplier invoice no.">
          <input className="input" value={invoiceNo} onChange={(e) => setInvoiceNo(e.target.value)}/>
        </Field>
        <div>
          <div className="text-xs text-fg-muted mb-2">Lines</div>
          <div className="space-y-2">
            {lines.map((ln, i) => (
              <div key={i} className="grid grid-cols-[1fr_70px_90px_28px] gap-2 items-center">
                <select className="input !min-h-[36px] !py-1.5" required
                  value={ln.ingredient_id}
                  onChange={(e) => updLine(i, { ingredient_id: e.target.value })}>
                  <option value="">— select ingredient —</option>
                  {ingredients.map((ing) => (
                    <option key={ing.id} value={ing.id}>{ing.name} ({ing.base_unit})</option>
                  ))}
                </select>
                <input type="number" required min={0} step="0.01" placeholder="Qty"
                  className="input !min-h-[36px] !py-1.5 font-mono text-right"
                  value={ln.qty} onChange={(e) => updLine(i, { qty: e.target.value })}/>
                <input type="number" required min={0} step="0.01" placeholder="₹/unit"
                  className="input !min-h-[36px] !py-1.5 font-mono text-right"
                  value={ln.unit_cost_rupees}
                  onChange={(e) => updLine(i, { unit_cost_rupees: e.target.value })}/>
                <button type="button" onClick={() => rmLine(i)} className="text-fg-muted hover:text-accent-bad p-1">
                  <Trash2 size={14}/>
                </button>
              </div>
            ))}
          </div>
          <button type="button" className="btn btn-ghost mt-2 !min-h-[32px] text-xs"
            onClick={addLine}><Plus size={12}/> Add line</button>
        </div>
        <Field label="Notes (optional)">
          <textarea className="input" rows={2} value={notes} onChange={(e) => setNotes(e.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Truck size={14}/>}
            Record receipt
          </button>
        </div>
      </form>
    </Modal>
  );
}

function AdjustmentForm({
  ingredient, branches, onClose, onSuccess,
}: {
  ingredient: IngredientDTO;
  branches: BranchDTO[];
  onClose: () => void;
  onSuccess: () => void;
}) {
  const [branchId, setBranchId] = useState(branches[0]?.id ?? '');
  const [type, setType] = useState<'waste' | 'damage' | 'transfer' | 'adjustment'>('waste');
  const [qty, setQty] = useState('');
  const [note, setNote] = useState('');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    if (!branchId) { setErr('select a branch'); return; }
    setBusy(true); setErr(null);
    try {
      // Waste/damage are typically negative
      const q = parseFloat(qty);
      const delta = (type === 'waste' || type === 'damage') && q > 0 ? -q : q;
      await inventory.postAdjustment({
        ingredient_id: ingredient.id,
        branch_id: branchId,
        qty_delta: delta,
        type,
        note: note || undefined,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={`Adjust ${ingredient.name}`}>
      <form onSubmit={submit} className="space-y-3">
        <div className="text-xs text-fg-muted">
          Current: <b>{ingredient.current_qty} {ingredient.base_unit}</b>
        </div>
        <Field label="Branch">
          <select className="input" required value={branchId}
            onChange={(e) => setBranchId(e.target.value)}>
            <option value="">— select —</option>
            {branches.map((b) => <option key={b.id} value={b.id}>{b.name}</option>)}
          </select>
        </Field>
        <Field label="Type">
          <select className="input" value={type}
            onChange={(e) => setType(e.target.value as typeof type)}>
            <option value="waste">Waste (spoilage / spill)</option>
            <option value="damage">Damage</option>
            <option value="adjustment">Count correction</option>
            <option value="transfer">Transfer out</option>
          </select>
        </Field>
        <Field label={`Quantity in ${ingredient.base_unit} (positive number)`}>
          <input type="number" min={0} step="0.01" required className="input font-mono"
            value={qty} onChange={(e) => setQty(e.target.value)}/>
        </Field>
        <Field label="Note (optional)">
          <input className="input" value={note} onChange={(e) => setNote(e.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            Record
          </button>
        </div>
      </form>
    </Modal>
  );
}

function SupplierForm({
  supplier, onClose, onSuccess,
}: { supplier?: SupplierDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!supplier;
  const [form, setForm] = useState({
    name: supplier?.name ?? '',
    contact: supplier?.contact ?? '',
    gstin: supplier?.gstin ?? '',
    payment_terms: supplier?.payment_terms ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const body = {
        name: form.name.trim(),
        contact: form.contact.trim() || undefined,
        gstin: form.gstin.trim() || undefined,
        payment_terms: form.payment_terms.trim() || undefined,
      };
      if (isEdit) await inventory.updateSupplier(supplier!.id, body);
      else await inventory.createSupplier(body);
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${supplier!.name}` : 'New supplier'}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Contact (phone / email)">
          <input className="input" value={form.contact}
            onChange={(e) => setForm({ ...form, contact: e.target.value })}/>
        </Field>
        <Field label="GSTIN">
          <input className="input font-mono" value={form.gstin}
            onChange={(e) => setForm({ ...form, gstin: e.target.value })}/>
        </Field>
        <Field label="Payment terms (e.g. Net 30, COD)">
          <input className="input" value={form.payment_terms}
            onChange={(e) => setForm({ ...form, payment_terms: e.target.value })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            {isEdit ? 'Save' : 'Create'}
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- bits
function Stat({ label, value, icon, tone = 'default' }: {
  label: string; value: string; icon: React.ReactNode; tone?: 'default' | 'good' | 'bad';
}) {
  const color = tone === 'bad' ? 'text-accent-bad' : tone === 'good' ? 'text-accent-good' : 'text-fg';
  return (
    <div className="card flex items-center gap-4">
      <div className="p-2 bg-bg-raised rounded-lg text-fg-muted">{icon}</div>
      <div>
        <div className="text-xs text-fg-muted uppercase tracking-wider">{label}</div>
        <div className={`text-2xl font-bold ${color}`}>{value}</div>
      </div>
    </div>
  );
}
function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="text-xs text-fg-muted">{label}</span>
      <div className="mt-1">{children}</div>
    </label>
  );
}
function ErrorRow({ text }: { text: string }) {
  return (
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2 mb-3">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
function TabBtn({ active, onClick, children }: {
  active: boolean; onClick: () => void; children: React.ReactNode;
}) {
  return (
    <button onClick={onClick}
      className={`shrink-0 px-4 py-2 text-sm font-medium border-b-2 -mb-px whitespace-nowrap flex items-center gap-1.5
        ${active ? 'border-accent text-accent' : 'border-transparent text-fg-muted hover:text-fg'}`}>
      {children}
    </button>
  );
}
