/**
 * Menu screen — full live CRUD on categories + items.
 *
 * Live mode hits /api/v1/menu/*; demo mode reads MENU + CATEGORIES.
 */
import { useEffect, useMemo, useState } from 'react';
import {
  Search, Edit2, Plus, Trash2, AlertCircle, Loader2, RefreshCw,
  Eye, EyeOff,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { CATEGORIES, MENU } from '@/lib/demo-data';
import { inr } from '@/lib/inr';
import { APP_STORE_REVIEW, isAppStoreAllowedType } from '@/lib/app-store-compliance';
import {
  menu, menuAdmin, type MenuItemDTO, type MenuCategoryDTO,
} from '@/lib/erp-api';
import { useAuth } from '@/modules/auth/AuthContext';
import Modal from '@/components/ui/Modal';

type ItemType = 'food' | 'drink' | 'dessert' | 'gaming' | 'event' | 'hookah';

export default function MenuScreen() {
  const { me, demo } = useAuth();
  const canManageMenu = Boolean(demo || me?.protected_access);
  const [items, setItems] = useState<MenuItemDTO[]>([]);
  const [cats, setCats] = useState<MenuCategoryDTO[]>([]);
  const [q, setQ] = useState('');
  const [catFilter, setCatFilter] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const [addItemOpen, setAddItemOpen] = useState(false);
  const [editItem, setEditItem] = useState<MenuItemDTO | null>(null);
  const [addCatOpen, setAddCatOpen] = useState(false);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) {
        const [c, i] = await Promise.all([menu.categories(), menu.items()]);
        const allowedItems = i.filter((item) => isAppStoreAllowedType(item.type));
        const allowedCategoryIds = new Set(allowedItems.map((item) => item.category_id));
        setCats(c.filter((cat) => allowedCategoryIds.has(cat.id)));
        setItems(allowedItems);
      } else {
        setCats(CATEGORIES.map((c, idx) => ({ id: c, name: c, sort_order: idx })));
        setItems(MENU.map((m) => ({
          id: m.id, category_id: m.category, sku: m.sku, name: m.name, type: 'food',
          base_price_minor: m.price, tax_rate: m.rate, is_available: true,
        })));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load menu');
    } finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  const filtered = useMemo(() => {
    return items.filter((m) => {
      if (catFilter && m.category_id !== catFilter) return false;
      if (q && !m.name.toLowerCase().includes(q.toLowerCase())
          && !m.sku.toLowerCase().includes(q.toLowerCase())) return false;
      return true;
    });
  }, [items, q, catFilter]);

  async function onDelete(it: MenuItemDTO) {
    if (!confirm(`Delete '${it.name}'? Past orders will keep showing the old name.`)) return;
    try { await menuAdmin.deleteItem(it.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }
  async function onToggleAvail(it: MenuItemDTO) {
    try {
      await menuAdmin.updateItem(it.id, { is_available: !it.is_available });
      await load();
    } catch (e) { alert((e as Error).message); }
  }

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Menu</h2>
          <p className="text-fg-muted text-sm">
            {items.length} item{items.length === 1 ? '' : 's'} · {cats.length} categor{cats.length === 1 ? 'y' : 'ies'} · HSN/SAC per item
          </p>
        </div>
        <div className="flex gap-2 items-center flex-wrap">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          {canManageMenu && (
            <>
              <button className="btn btn-ghost" onClick={() => setAddCatOpen(true)}>
                <Plus size={14}/> Category
              </button>
              <button className="btn btn-primary" onClick={() => setAddItemOpen(true)} disabled={!cats.length}>
                <Plus size={14}/> New item
              </button>
            </>
          )}
        </div>
      </header>

      <div className="flex items-center gap-2 mb-4 max-w-md">
        <Search size={16} className="text-fg-muted"/>
        <input value={q} onChange={(e) => setQ(e.target.value)}
          placeholder="Search by name or SKU…" className="input !min-h-[40px] !py-2 flex-1"/>
      </div>

      <div className="scroll-strip flex gap-2 mb-4 pb-1 -mx-3 px-3 md:mx-0 md:px-0">
        <button onClick={() => setCatFilter(null)}
          className={`chip shrink-0 whitespace-nowrap ${!catFilter ? 'border-accent text-accent' : ''}`}>All</button>
        {cats.map((c) => (
          <button key={c.id} onClick={() => setCatFilter(c.id)}
            className={`chip shrink-0 whitespace-nowrap ${catFilter === c.id ? 'border-accent text-accent' : ''}`}>{c.name}</button>
        ))}
      </div>

      {error && (
        <div className="card border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2 mb-3">
          <AlertCircle size={14}/> {error}
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !filtered.length ? (
        <div className="card text-fg-muted text-sm">
          {!items.length
            ? canManageMenu ? 'No items yet. Add a category first, then create items.' : 'No menu items configured yet.'
            : 'No items match your filter.'}
        </div>
      ) : (
        <div className="card !p-0 overflow-hidden">
          <table className="w-full text-sm hidden md:table">
            <thead className="bg-bg-raised border-b border-bg-border">
              <tr>
                <th className="text-left p-3">Item</th>
                <th className="text-left p-3">SKU</th>
                <th className="text-left p-3">Type</th>
                <th className="text-right p-3">Price</th>
                <th className="text-right p-3">GST</th>
                <th className="text-center p-3">Avail.</th>
                {canManageMenu && <th className="text-right p-3 pr-4">Actions</th>}
              </tr>
            </thead>
            <tbody>
              {filtered.map((m, i) => (
                <tr key={m.id} className={i % 2 ? 'bg-bg-surface/50' : ''}>
                  <td className="p-3 font-medium">{m.name}</td>
                  <td className="p-3 font-mono text-xs text-fg-muted">{m.sku}</td>
                  <td className="p-3 text-fg-muted text-xs">{m.type}</td>
                  <td className="p-3 text-right font-mono">{inr(m.base_price_minor)}</td>
                  <td className="p-3 text-right text-fg-muted">{(m.tax_rate * 100).toFixed(0)}%</td>
                  <td className="p-3 text-center">
                    {canManageMenu ? (
                      <button onClick={() => onToggleAvail(m)} className="hover:text-accent"
                        title={m.is_available ? 'Hide from POS' : 'Show on POS'}>
                        {m.is_available
                          ? <Eye size={14} className="text-accent-good"/>
                          : <EyeOff size={14} className="text-fg-muted"/>}
                      </button>
                    ) : m.is_available ? (
                      <Eye size={14} className="text-accent-good"/>
                    ) : (
                      <EyeOff size={14} className="text-fg-muted"/>
                    )}
                  </td>
                  {canManageMenu && (
                    <td className="p-3 text-right pr-4 flex gap-1 justify-end">
                      <button className="text-fg-muted hover:text-accent" onClick={() => setEditItem(m)}>
                        <Edit2 size={14}/>
                      </button>
                      <button className="text-fg-muted hover:text-accent-bad" onClick={() => onDelete(m)}>
                        <Trash2 size={14}/>
                      </button>
                    </td>
                  )}
                </tr>
              ))}
            </tbody>
          </table>
          <div className="mobile-card-list md:hidden">
            {filtered.map((m) => (
              <div key={m.id} className="mobile-record-card">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <div className="truncate text-sm font-semibold">{m.name}</div>
                    <div className="mt-1 truncate font-mono text-[10px] text-fg-muted">
                      {m.sku} · {m.type}
                    </div>
                  </div>
                  <div className="shrink-0 text-right">
                    <div className="font-mono text-sm font-semibold">{inr(m.base_price_minor)}</div>
                    <div className="text-[10px] text-fg-muted">{(m.tax_rate * 100).toFixed(0)}% GST</div>
                  </div>
                </div>
                <div className="mt-3 flex flex-wrap items-center justify-between gap-2">
                  <span className={`chip text-[10px] ${m.is_available ? 'border-accent-good/50 text-accent-good' : ''}`}>
                    {m.is_available ? 'Available' : 'Hidden'}
                  </span>
                  {canManageMenu && (
                    <div className="flex shrink-0 gap-1">
                      <button
                        aria-label={m.is_available ? `Hide ${m.name} from POS` : `Show ${m.name} on POS`}
                        onClick={() => onToggleAvail(m)}
                        className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1"
                      >
                        {m.is_available ? <Eye size={14}/> : <EyeOff size={14}/>}
                      </button>
                      <button
                        aria-label={`Edit ${m.name}`}
                        onClick={() => setEditItem(m)}
                        className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1"
                      >
                        <Edit2 size={14}/>
                      </button>
                      <button
                        aria-label={`Delete ${m.name}`}
                        onClick={() => onDelete(m)}
                        className="btn btn-ghost !min-h-[32px] !min-w-[32px] !px-2 !py-1 hover:!text-accent-bad"
                      >
                        <Trash2 size={14}/>
                      </button>
                    </div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {canManageMenu && addItemOpen && (
        <ItemForm cats={cats} onClose={() => setAddItemOpen(false)}
          onSuccess={() => { setAddItemOpen(false); load(); }}/>
      )}
      {canManageMenu && editItem && (
        <ItemForm cats={cats} item={editItem} onClose={() => setEditItem(null)}
          onSuccess={() => { setEditItem(null); load(); }}/>
      )}
      {canManageMenu && addCatOpen && (
        <CategoryForm onClose={() => setAddCatOpen(false)}
          onSuccess={() => { setAddCatOpen(false); load(); }}/>
      )}
    </div>
  );
}

// ---------------------------------------------------------------- ItemForm
function ItemForm({
  cats, item, onClose, onSuccess,
}: {
  cats: MenuCategoryDTO[];
  item?: MenuItemDTO;
  onClose: () => void;
  onSuccess: () => void;
}) {
  const isEdit = !!item;
  const [form, setForm] = useState({
    category_id: (item as MenuItemDTO & { category_id?: string })?.category_id ?? cats[0]?.id ?? '',
    sku: item?.sku ?? '',
    name: item?.name ?? '',
    type: (item?.type as ItemType) ?? 'food',
    price_rupees: item ? (item.base_price_minor / 100).toFixed(2) : '',
    tax_rate_pct: item ? Math.round(item.tax_rate * 100).toString() : '5',
    description: '',
    is_available: item?.is_available ?? true,
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const base_price_minor = Math.round(parseFloat(form.price_rupees || '0') * 100);
      const tax_rate = parseFloat(form.tax_rate_pct || '0') / 100;
      if (isEdit) {
        await menuAdmin.updateItem(item!.id, {
          category_id: form.category_id,
          name: form.name.trim(),
          base_price_minor,
          tax_rate,
          description: form.description || undefined,
          is_available: form.is_available,
        });
      } else {
        await menuAdmin.createItem({
          category_id: form.category_id,
          sku: form.sku.trim(),
          name: form.name.trim(),
          type: form.type,
          base_price_minor,
          tax_rate,
          description: form.description || undefined,
        });
      }
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${item!.name}` : 'New menu item'}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Category">
          <select className="input" required value={form.category_id}
            onChange={(e) => setForm({ ...form, category_id: e.target.value })}>
            {cats.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
        </Field>
        <Field label="SKU (e.g. CAPPUCCINO-MED)">
          <input className="input font-mono" required disabled={isEdit} value={form.sku}
            onChange={(e) => setForm({ ...form, sku: e.target.value })}/>
        </Field>
        <Field label="Name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <Field label="Type">
          <select className="input" disabled={isEdit} value={form.type}
            onChange={(e) => setForm({ ...form, type: e.target.value as ItemType })}>
            <option value="food">Food</option>
            <option value="drink">Drink</option>
            <option value="dessert">Dessert</option>
            <option value="gaming">Gaming</option>
            <option value="event">Event</option>
            {!APP_STORE_REVIEW && <option value="hookah">Hookah</option>}
          </select>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Price (₹, before GST)">
            <input type="number" min={0} step="0.01" required className="input font-mono text-right"
              value={form.price_rupees}
              onChange={(e) => setForm({ ...form, price_rupees: e.target.value })}/>
          </Field>
          <Field label="GST rate (%)">
            <select className="input" value={form.tax_rate_pct}
              onChange={(e) => setForm({ ...form, tax_rate_pct: e.target.value })}>
              <option value="0">0% (exempt)</option>
              <option value="5">5% (most food)</option>
              <option value="12">12%</option>
              <option value="18">18% (AC dining, gaming)</option>
              <option value="28">28%</option>
            </select>
          </Field>
        </div>
        <Field label="Description (optional)">
          <textarea className="input" rows={2} value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}/>
        </Field>
        {isEdit && (
          <label className="flex items-center gap-2 text-sm">
            <input type="checkbox" checked={form.is_available}
              onChange={(e) => setForm({ ...form, is_available: e.target.checked })}/>
            Available on POS
          </label>
        )}
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

// ---------------------------------------------------------------- CategoryForm
function CategoryForm({
  onClose, onSuccess,
}: { onClose: () => void; onSuccess: () => void }) {
  const [name, setName] = useState('');
  const [sortOrder, setSortOrder] = useState('0');
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      await menuAdmin.createCategory({
        name: name.trim(),
        sort_order: parseInt(sortOrder, 10) || 0,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title="New category">
      <form onSubmit={submit} className="space-y-3">
        <Field label="Name (e.g. Coffee, Mocktails, Desserts)">
          <input className="input" required value={name} onChange={(e) => setName(e.target.value)} autoFocus/>
        </Field>
        <Field label="Sort order (lower shows first)">
          <input type="number" className="input font-mono" value={sortOrder}
            onChange={(e) => setSortOrder(e.target.value)}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : null}
            Create
          </button>
        </div>
      </form>
    </Modal>
  );
}

// ---------------------------------------------------------------- bits
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
    <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
      <AlertCircle size={14}/> {text}
    </div>
  );
}
