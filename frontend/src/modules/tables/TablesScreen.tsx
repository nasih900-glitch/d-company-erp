/**
 * Tables / Floor — full live CRUD.
 *  - List tables
 *  - Add / edit / delete
 *  - Quick status cycle (tap chip)
 */
import { useEffect, useState } from 'react';
import {
  Users, Plus, Edit2, Trash2, AlertCircle, Loader2, RefreshCw,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { TABLES, type TableRec } from '@/lib/demo-data';
import { tables, type TableDTO } from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

const STATUS_COLOR: Record<TableDTO['status'], string> = {
  available: 'border-accent-good/40 text-accent-good',
  occupied:  'border-accent-bad/40 text-accent-bad',
  reserved:  'border-accent-gold/40 text-accent-gold',
  cleaning:  'border-fg-muted/40 text-fg-muted',
  merged:    'border-accent-purple/40 text-accent-purple',
};
const STATUS_NEXT: Record<TableDTO['status'], TableDTO['status']> = {
  available: 'occupied',
  occupied:  'cleaning',
  cleaning:  'available',
  reserved:  'occupied',
  merged:    'available',
};

export default function TablesScreen() {
  const [rows, setRows] = useState<TableDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<TableDTO | null>(null);

  async function load() {
    setLoading(true); setError(null);
    try {
      if (LIVE_MODE) setRows(await tables.list());
      else setRows(TABLES.map(demoToDTO));
    } catch (e) { setError((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  async function cycleStatus(t: TableDTO) {
    try { await tables.updateStatus(t.id, STATUS_NEXT[t.status]); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  async function onDelete(t: TableDTO) {
    if (!confirm(`Delete table ${t.code}?`)) return;
    try { await tables.delete(t.id); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  const summary = {
    total: rows.length,
    occupied: rows.filter((t) => t.status === 'occupied').length,
    available: rows.filter((t) => t.status === 'available').length,
  };

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">Tables &amp; floor</h2>
          <p className="text-fg-muted text-sm">
            {summary.total} tables · {summary.occupied} occupied · {summary.available} free
          </p>
        </div>
        <div className="flex gap-2">
          <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
          <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
            <Plus size={14}/> New table
          </button>
        </div>
      </header>

      {error && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {error}
        </div>
      )}

      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !rows.length ? (
        <div className="card text-fg-muted text-sm">
          No tables yet. Click <b>New table</b> to add the first one.
        </div>
      ) : (
        <div className="grid grid-cols-2 md:grid-cols-4 lg:grid-cols-5 gap-3 md:gap-4">
          {[...rows].sort((a, b) => a.code.localeCompare(b.code)).map((t) => (
            <div key={t.id} className="card">
              <div className="flex justify-between items-start mb-2">
                <div>
                  <div className="font-bold text-lg">{t.code}</div>
                  <div className="text-xs text-fg-muted flex items-center gap-1">
                    <Users size={11}/> {t.seats} seats · {t.shape}
                  </div>
                </div>
                <div className="flex flex-col gap-1">
                  <button className="text-fg-muted hover:text-accent p-1" onClick={() => setEdit(t)}>
                    <Edit2 size={12}/>
                  </button>
                  <button className="text-fg-muted hover:text-accent-bad p-1" onClick={() => onDelete(t)}>
                    <Trash2 size={12}/>
                  </button>
                </div>
              </div>
              <button onClick={() => cycleStatus(t)}
                className={`chip w-full justify-center ${STATUS_COLOR[t.status]}`}>
                {t.status}
              </button>
            </div>
          ))}
        </div>
      )}

      {addOpen && <TableForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {edit && <TableForm table={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
    </div>
  );
}

function TableForm({
  table, onClose, onSuccess,
}: { table?: TableDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!table;
  const [form, setForm] = useState({
    code: table?.code ?? '',
    seats: table?.seats?.toString() ?? '2',
    shape: table?.shape ?? 'rect' as TableDTO['shape'],
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      const body = {
        code: form.code.trim(),
        seats: parseInt(form.seats, 10),
        shape: form.shape,
      };
      if (isEdit) await tables.update(table!.id, body);
      else await tables.create(body);
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${table!.code}` : 'New table'}>
      <form onSubmit={submit} className="space-y-3">
        <Field label="Code (e.g. T1, B2, OUT-1)">
          <input className="input font-mono" required autoFocus disabled={isEdit}
            value={form.code}
            onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Seats">
            <input type="number" required min={1} max={20} className="input font-mono"
              value={form.seats}
              onChange={(e) => setForm({ ...form, seats: e.target.value })}/>
          </Field>
          <Field label="Shape">
            <select className="input" value={form.shape}
              onChange={(e) => setForm({ ...form, shape: e.target.value as TableDTO['shape'] })}>
              <option value="rect">Rectangular</option>
              <option value="round">Round</option>
              <option value="booth">Booth</option>
            </select>
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
function demoToDTO(t: TableRec): TableDTO {
  return {
    id: t.id, floor_id: 'demo', code: t.code,
    seats: t.seats, shape: 'rect', x: 0, y: 0,
    status: t.status as TableDTO['status'],
  };
}
