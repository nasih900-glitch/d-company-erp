/**
 * Settings → Memberships
 *
 *  - View the 3 default tiers (D Club Silver / Gold / Platinum) seeded
 *    at install time with sensible defaults.
 *  - Edit pricing + perks per tier.
 *  - Customers screen has the "Subscribe a customer" flow.
 */
import { useEffect, useState } from 'react';
import {
  Crown, Edit2, Save, Loader2, AlertCircle, Award, Gamepad2, Flame, Utensils,
} from 'lucide-react';

import { inr } from '@/lib/inr';
import { APP_STORE_REVIEW, isAppStoreAllowedType } from '@/lib/app-store-compliance';
import { memberships, type MembershipTierDTO } from '@/lib/erp-api';
import Modal from '@/components/ui/Modal';

const TIER_ACCENT: Record<string, string> = {
  silver:   'border-fg-muted/60 text-fg',
  gold:     'border-accent-gold/60 text-accent-gold',
  platinum: 'border-accent-purple/60 text-accent-purple',
};

export default function MembershipsTab() {
  const [rows, setRows] = useState<MembershipTierDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [edit, setEdit] = useState<MembershipTierDTO | null>(null);

  async function load() {
    setLoading(true); setErr(null);
    try { setRows(await memberships.listTiers()); }
    catch (e) { setErr((e as Error).message); }
    finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  if (loading) return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;

  return (
    <div>
      <div className="card mb-4 border-accent/40 bg-accent/5 text-sm flex gap-2 items-start">
        <Crown size={16} className="text-accent shrink-0 mt-0.5"/>
        <div>
          <b>D Club memberships</b> — three default tiers seeded for you.
          Click <b>Edit</b> on any tier to change pricing or perks.
          Subscribe customers from the <b>Customers</b> screen.
          Recurring billing lands when your Razorpay keys arrive.
        </div>
      </div>

      {err && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertCircle size={14}/> {err}
        </div>
      )}

      {!rows.length ? (
        <div className="card text-fg-muted text-sm">
          No tiers seeded. Re-run <code>scripts.seed</code> on the droplet to seed D Club Silver / Gold / Platinum.
        </div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-3 gap-3 md:gap-4">
          {rows.map((t) => (
            <div key={t.id} className={`card border-2 ${TIER_ACCENT[t.code] ?? 'border-bg-border'}`}>
              <div className="flex items-start justify-between mb-3">
                <div className="flex items-center gap-2">
                  <Crown size={18}/>
                  <h3 className="font-bold text-lg">{t.name}</h3>
                </div>
                <button className="text-fg-muted hover:text-accent" onClick={() => setEdit(t)}>
                  <Edit2 size={14}/>
                </button>
              </div>

              <div className="mb-3">
                <div className="text-3xl font-bold font-mono">{inr(t.monthly_price_minor)}</div>
                <div className="text-xs text-fg-muted">per month</div>
                {t.annual_price_minor && (
                  <div className="text-xs text-accent mt-1">
                    or {inr(t.annual_price_minor)}/year (save {inr(t.monthly_price_minor * 12 - t.annual_price_minor)})
                  </div>
                )}
              </div>

              <div className="space-y-1.5 text-sm border-t border-bg-border pt-3">
                <Perk icon={<Utensils size={11}/>} active={t.food_discount_pct > 0}>
                  {(t.food_discount_pct * 100).toFixed(0)}% off food
                </Perk>
                <Perk icon={<Gamepad2 size={11}/>} active={t.gaming_discount_pct > 0 || t.free_gaming_minutes_per_week > 0}>
                  {t.free_gaming_minutes_per_week > 0
                    ? `${Math.floor(t.free_gaming_minutes_per_week / 60)}h free PS5/week`
                    : `${(t.gaming_discount_pct * 100).toFixed(0)}% off gaming`}
                </Perk>
                {isAppStoreAllowedType('hookah') && (
                  <Perk icon={<Flame size={11}/>} active={t.hookah_discount_pct > 0 || t.free_hookah_per_month > 0}>
                    {t.free_hookah_per_month > 0
                      ? `${t.free_hookah_per_month} free hookah/month`
                      : `${(t.hookah_discount_pct * 100).toFixed(0)}% off hookah`}
                  </Perk>
                )}
                <Perk icon={<Award size={11}/>} active={t.point_multiplier > 1}>
                  {t.point_multiplier}× loyalty points
                </Perk>
                <Perk icon={<Crown size={11}/>} active={t.priority_booking}>
                  Priority booking
                </Perk>
              </div>

              {t.description && (
                <p className="text-xs text-fg-muted mt-3 border-t border-bg-border pt-3">
                  {t.description}
                </p>
              )}
            </div>
          ))}
        </div>
      )}

      {edit && <TierForm tier={edit} onClose={() => setEdit(null)}
        onSuccess={() => { setEdit(null); load(); }}/>}
    </div>
  );
}

function Perk({ icon, active, children }: {
  icon: React.ReactNode; active: boolean; children: React.ReactNode;
}) {
  return (
    <div className={`flex items-center gap-2 ${active ? 'text-fg' : 'text-fg-muted/40 line-through'}`}>
      {icon} {children}
    </div>
  );
}

function TierForm({
  tier, onClose, onSuccess,
}: { tier: MembershipTierDTO; onClose: () => void; onSuccess: () => void }) {
  const [form, setForm] = useState({
    name: tier.name,
    monthly_rupees: (tier.monthly_price_minor / 100).toFixed(2),
    annual_rupees: tier.annual_price_minor ? (tier.annual_price_minor / 100).toFixed(2) : '',
    food_pct: (tier.food_discount_pct * 100).toFixed(0),
    gaming_pct: (tier.gaming_discount_pct * 100).toFixed(0),
    hookah_pct: (tier.hookah_discount_pct * 100).toFixed(0),
    point_multiplier: tier.point_multiplier.toString(),
    free_gaming_min: tier.free_gaming_minutes_per_week.toString(),
    free_hookah: tier.free_hookah_per_month.toString(),
    priority_booking: tier.priority_booking,
    description: tier.description ?? '',
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setErr(null);
    try {
      await memberships.updateTier(tier.id, {
        name: form.name.trim(),
        monthly_price_minor: Math.round(parseFloat(form.monthly_rupees) * 100),
        annual_price_minor: form.annual_rupees ? Math.round(parseFloat(form.annual_rupees) * 100) : null,
        food_discount_pct: parseFloat(form.food_pct || '0') / 100,
        gaming_discount_pct: parseFloat(form.gaming_pct || '0') / 100,
        hookah_discount_pct: parseFloat(form.hookah_pct || '0') / 100,
        point_multiplier: parseFloat(form.point_multiplier || '1'),
        free_gaming_minutes_per_week: parseInt(form.free_gaming_min || '0', 10),
        free_hookah_per_month: parseInt(form.free_hookah || '0', 10),
        priority_booking: form.priority_booking,
        description: form.description || null,
      });
      onSuccess();
    } catch (e) { setErr((e as Error).message); }
    finally { setBusy(false); }
  }

  return (
    <Modal open onClose={onClose} title={`Edit ${tier.name}`} size="lg">
      <form onSubmit={submit} className="space-y-3">
        <Field label="Name">
          <input className="input" required value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}/>
        </Field>
        <div className="grid grid-cols-2 gap-3">
          <Field label="Monthly price (₹)">
            <input type="number" required min={0} step="0.01" className="input font-mono text-right"
              value={form.monthly_rupees}
              onChange={(e) => setForm({ ...form, monthly_rupees: e.target.value })}/>
          </Field>
          <Field label="Annual price (₹, optional)">
            <input type="number" min={0} step="0.01" className="input font-mono text-right"
              value={form.annual_rupees}
              onChange={(e) => setForm({ ...form, annual_rupees: e.target.value })}/>
          </Field>
        </div>
        <div className={`grid ${APP_STORE_REVIEW ? 'grid-cols-2' : 'grid-cols-3'} gap-3`}>
          <Field label="Food discount %">
            <input type="number" min={0} max={100} className="input font-mono text-right"
              value={form.food_pct}
              onChange={(e) => setForm({ ...form, food_pct: e.target.value })}/>
          </Field>
          <Field label="Gaming discount %">
            <input type="number" min={0} max={100} className="input font-mono text-right"
              value={form.gaming_pct}
              onChange={(e) => setForm({ ...form, gaming_pct: e.target.value })}/>
          </Field>
          {!APP_STORE_REVIEW && (
            <Field label="Hookah discount %">
              <input type="number" min={0} max={100} className="input font-mono text-right"
                value={form.hookah_pct}
                onChange={(e) => setForm({ ...form, hookah_pct: e.target.value })}/>
            </Field>
          )}
        </div>
        <div className={`grid ${APP_STORE_REVIEW ? 'grid-cols-2' : 'grid-cols-3'} gap-3`}>
          <Field label="Point multiplier (×)">
            <input type="number" min={1} max={10} step="0.25" className="input font-mono text-right"
              value={form.point_multiplier}
              onChange={(e) => setForm({ ...form, point_multiplier: e.target.value })}/>
          </Field>
          <Field label="Free PS5 min/week">
            <input type="number" min={0} className="input font-mono text-right"
              value={form.free_gaming_min}
              onChange={(e) => setForm({ ...form, free_gaming_min: e.target.value })}/>
          </Field>
          {!APP_STORE_REVIEW && (
            <Field label="Free hookah/month">
              <input type="number" min={0} className="input font-mono text-right"
                value={form.free_hookah}
                onChange={(e) => setForm({ ...form, free_hookah: e.target.value })}/>
            </Field>
          )}
        </div>
        <label className="flex items-center gap-2 text-sm">
          <input type="checkbox" checked={form.priority_booking}
            onChange={(e) => setForm({ ...form, priority_booking: e.target.checked })}/>
          Priority booking for events & gaming slots
        </label>
        <Field label="Description (shown to customers)">
          <textarea className="input" rows={2} value={form.description}
            onChange={(e) => setForm({ ...form, description: e.target.value })}/>
        </Field>
        {err && (
          <div className="p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
            <AlertCircle size={14}/> {err}
          </div>
        )}
        <div className="flex justify-end gap-2 pt-2">
          <button type="button" className="btn btn-ghost" onClick={onClose}>Cancel</button>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? <Loader2 className="animate-spin" size={14}/> : <Save size={14}/>}
            Save
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
