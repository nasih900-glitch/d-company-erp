import { useEffect, useState } from 'react';
import { Plus, Edit2, Save, Loader2, AlertCircle, MapPin } from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { settings, type BranchDTO } from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

export default function BranchesTab() {
  const [rows, setRows] = useState<BranchDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [addOpen, setAddOpen] = useState(false);
  const [edit, setEdit] = useState<BranchDTO | null>(null);

  async function load() {
    if (!LIVE_MODE) { setLoading(false); return; }
    setLoading(true); setErr(null);
    try {
      setRows(await settings.listBranches());
    } catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;
  if (!LIVE_MODE) return <div className="card text-fg-muted">Available in live mode only.</div>;

  return (
    <div className="space-y-3">
      <div className="flex justify-between items-center">
        <p className="text-sm text-fg-muted">{rows.length} branch{rows.length === 1 ? '' : 'es'}</p>
        <button className="btn btn-primary" onClick={() => setAddOpen(true)}>
          <Plus size={14}/> Add branch
        </button>
      </div>

      {err && <ErrorRow text={err}/>}

      <div className="space-y-3">
        {rows.map((b) => (
          <div key={b.id} className="card">
            <div className="flex items-start justify-between gap-3">
              <div className="flex items-start gap-3 min-w-0">
                <div className="p-2 bg-bg-raised rounded-lg text-fg-muted">
                  <MapPin size={16}/>
                </div>
                <div className="min-w-0">
                  <div className="font-bold truncate">{b.name}</div>
                  {b.address && <div className="text-xs text-fg-muted">{b.address}</div>}
                  <div className="text-xs text-fg-muted mt-1 flex gap-3 flex-wrap">
                    {b.code && <span>Code: <b>{b.code}</b></span>}
                    {b.state_code && <span>State: <b>{b.state_code}</b></span>}
                    {b.fssai_license_no && <span>FSSAI: <b className="font-mono">{b.fssai_license_no}</b></span>}
                    {b.branch_gstin && <span>GSTIN: <b className="font-mono">{b.branch_gstin}</b></span>}
                  </div>
                </div>
              </div>
              <button className="btn btn-ghost !min-h-[32px] !py-1 !px-2 text-xs" onClick={() => setEdit(b)}>
                <Edit2 size={11}/> Edit
              </button>
            </div>
          </div>
        ))}
        {!rows.length && (
          <div className="card text-fg-muted text-sm">No branches yet. Add your first branch.</div>
        )}
      </div>

      {addOpen && <BranchForm onClose={() => setAddOpen(false)}
        onSuccess={() => { setAddOpen(false); load(); }}/>}
      {edit && <BranchForm branch={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
    </div>
  );
}

function BranchForm({
  branch, onClose, onSuccess,
}: { branch?: BranchDTO; onClose: () => void; onSuccess: () => void }) {
  const isEdit = !!branch;
  const [form, setForm] = useState<Partial<BranchDTO>>({
    name: branch?.name ?? '',
    code: branch?.code ?? '',
    address: branch?.address ?? '',
    timezone: branch?.timezone ?? 'Asia/Kolkata',
    opens_at: branch?.opens_at ?? '09:00',
    closes_at: branch?.closes_at ?? '23:30',
    state_code: branch?.state_code ?? 'KL',
    fssai_license_no: branch?.fssai_license_no ?? '',
    trade_license_no: branch?.trade_license_no ?? '',
    branch_gstin: branch?.branch_gstin ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      if (isEdit) await settings.updateBranch(branch!.id, form);
      else await settings.createBranch(form);
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={isEdit ? `Edit ${branch!.name}` : 'New branch'} size="lg">
      <form onSubmit={submit} className="space-y-3">
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <Field label="Name"><input className="input" required value={form.name ?? ''}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/></Field>
          <Field label="Short code (e.g. MN for Main)">
            <input className="input font-mono" maxLength={4} value={form.code ?? ''}
              onChange={(e) => setForm({ ...form, code: e.target.value.toUpperCase() })}/>
          </Field>
        </div>
        <Field label="Address">
          <textarea className="input" rows={2} value={form.address ?? ''}
            onChange={(e) => setForm({ ...form, address: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="State code (KL, KA, MH…)">
            <input className="input font-mono" maxLength={2} value={form.state_code ?? ''}
              onChange={(e) => setForm({ ...form, state_code: e.target.value.toUpperCase() })}/>
          </Field>
          <Field label="Timezone">
            <input className="input" value={form.timezone ?? ''}
              onChange={(e) => setForm({ ...form, timezone: e.target.value })}/>
          </Field>
          <Field label="Opens"><input className="input" placeholder="09:00" value={form.opens_at ?? ''}
            onChange={(e) => setForm({ ...form, opens_at: e.target.value })}/></Field>
          <Field label="Closes"><input className="input" placeholder="23:30" value={form.closes_at ?? ''}
            onChange={(e) => setForm({ ...form, closes_at: e.target.value })}/></Field>
        </div>
        <Field label="FSSAI license (14 digits)">
          <input className="input font-mono" maxLength={14} value={form.fssai_license_no ?? ''}
            onChange={(e) => setForm({ ...form, fssai_license_no: e.target.value })}/>
        </Field>
        <Field label="Trade license no.">
          <input className="input font-mono" value={form.trade_license_no ?? ''}
            onChange={(e) => setForm({ ...form, trade_license_no: e.target.value })}/>
        </Field>
        <Field label="Branch GSTIN (if different from company)">
          <input className="input font-mono" maxLength={15} value={form.branch_gstin ?? ''}
            onChange={(e) => setForm({ ...form, branch_gstin: e.target.value.toUpperCase() })}/>
        </Field>
        {err && <ErrorRow text={err}/>}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Save size={14}/>}
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
