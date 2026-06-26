import { useEffect, useState } from 'react';
import { Save, Loader2, AlertCircle, Check } from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { settings, type CompanyDTO } from '@/lib/erp-api';

export default function CompanyTab() {
  const [data, setData] = useState<CompanyDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  useEffect(() => {
    if (!LIVE_MODE) { setLoading(false); return; }
    settings.getCompany().then(setData)
      .catch((e) => setErr((e as Error).message))
      .finally(() => setLoading(false));
  }, []);

  async function save() {
    if (!data) return;
    setBusy(true); setErr(null); setDone(false);
    try {
      const updated = await settings.updateCompany({
        name: data.name,
        legal_name: data.legal_name,
        timezone: data.timezone,
        gstin: data.gstin,
        pan: data.pan,
        gst_registration_type: data.gst_registration_type,
        is_composition: data.is_composition,
        e_invoicing_enabled: data.e_invoicing_enabled,
      });
      setData(updated); setDone(true);
      setTimeout(() => setDone(false), 2000);
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;
  if (!data) return (
    <div className="card text-fg-muted">
      Company settings are only available in live mode (you're in demo mode). Connect to a backend to manage company details.
    </div>
  );

  return (
    <div className="space-y-4 max-w-2xl">
      <div className="card">
        <h3 className="font-bold mb-3">Company profile</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <Field label="Display name">
            <input className="input" value={data.name ?? ''}
              onChange={(e) => setData({ ...data, name: e.target.value })}/>
          </Field>
          <Field label="Legal name">
            <input className="input" value={data.legal_name ?? ''}
              onChange={(e) => setData({ ...data, legal_name: e.target.value })}/>
          </Field>
          <Field label="Currency">
            <input className="input" disabled value={data.currency}/>
          </Field>
          <Field label="Timezone">
            <input className="input" value={data.timezone ?? ''}
              onChange={(e) => setData({ ...data, timezone: e.target.value })}/>
          </Field>
        </div>
      </div>

      <div className="card">
        <h3 className="font-bold mb-3">India tax registration</h3>
        <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
          <Field label="GSTIN (15 chars)">
            <input className="input font-mono" maxLength={15} value={data.gstin ?? ''}
              onChange={(e) => setData({ ...data, gstin: e.target.value.toUpperCase() })}/>
          </Field>
          <Field label="PAN (10 chars)">
            <input className="input font-mono" maxLength={10} value={data.pan ?? ''}
              onChange={(e) => setData({ ...data, pan: e.target.value.toUpperCase() })}/>
          </Field>
          <Field label="GST registration type">
            <select className="input" value={data.gst_registration_type}
              onChange={(e) => setData({ ...data, gst_registration_type: e.target.value })}>
              <option value="regular">Regular</option>
              <option value="composition">Composition</option>
              <option value="unregistered">Unregistered</option>
              <option value="sez">SEZ</option>
            </select>
          </Field>
          <Field label="Fiscal year">
            <input className="input" disabled value="Apr — Mar (India)"/>
          </Field>
        </div>
        <div className="flex flex-wrap gap-4 mt-4">
          <Toggle label="Composition scheme" checked={data.is_composition}
            onChange={(v) => setData({ ...data, is_composition: v })}/>
          <Toggle label="E-invoicing enabled" checked={data.e_invoicing_enabled}
            onChange={(v) => setData({ ...data, e_invoicing_enabled: v })}/>
        </div>
      </div>

      {err && <ErrorRow text={err}/>}
      {done && (
        <div className="p-2.5 rounded-lg bg-accent-good/10 border border-accent-good/40 text-accent-good text-sm flex items-center gap-2">
          <Check size={14}/> Saved.
        </div>
      )}
      <div className="flex justify-end">
        <button className="btn btn-primary" onClick={save} disabled={busy}>
          {busy ? <Loader2 className="animate-spin" size={14}/> : <Save size={14}/>}
          Save changes
        </button>
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
function Toggle({ label, checked, onChange }: {
  label: string; checked: boolean; onChange: (v: boolean) => void;
}) {
  return (
    <label className="flex items-center gap-2 text-sm cursor-pointer">
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)}/>
      {label}
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
