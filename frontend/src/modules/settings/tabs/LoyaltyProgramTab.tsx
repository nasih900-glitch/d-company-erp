import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertCircle, CheckCircle2, Loader2, RefreshCw } from 'lucide-react';

import {
  settings,
  type LoyaltyProgramDTO,
  type LoyaltyProgramRulesDTO,
  type LoyaltyRankKey,
} from '@/lib/erp-api';
import { createOperationKey } from '@/lib/retry-drafts';
import { inr } from '@/lib/inr';
import { ConfirmModal } from '@/components/ui/ConfirmDialog';
import { useAuth } from '@/modules/auth/AuthContext';
import { minorToRupeesInput, parseRupeesToMinor } from '@/lib/money-input';

interface PublicationReview {
  companyId: string;
  expectedRevision: number;
  currentValue100Minor: number;
  currentRules: LoyaltyProgramRulesDTO;
  rules: LoyaltyProgramRulesDTO;
}

export function summarizeLoyaltyProgramChanges(before: LoyaltyProgramRulesDTO, after: LoyaltyProgramRulesDTO): string[] {
  const changes: string[] = [];
  if (before.gaming_earn_points !== after.gaming_earn_points || before.gaming_earn_rupees !== after.gaming_earn_rupees) {
    changes.push(`Gaming earn: ${before.gaming_earn_points} per ₹${before.gaming_earn_rupees} → ${after.gaming_earn_points} per ₹${after.gaming_earn_rupees}`);
  }
  if (before.minor_per_point !== after.minor_per_point) changes.push(`100-point value: ${inr(before.minor_per_point * 100)} → ${inr(after.minor_per_point * 100)}`);
  after.ranks.forEach((rank, index) => {
    const old = before.ranks[index];
    if (old && JSON.stringify(old) !== JSON.stringify(rank)) {
      const fields = [
        old.label !== rank.label ? `label ${old.label} → ${rank.label}` : null,
        old.threshold !== rank.threshold ? `threshold ${old.threshold.toLocaleString('en-IN')} → ${rank.threshold.toLocaleString('en-IN')}` : null,
        old.bonus_points !== rank.bonus_points ? `bonus ${old.bonus_points.toLocaleString('en-IN')} → ${rank.bonus_points.toLocaleString('en-IN')}` : null,
      ].filter((field): field is string => field !== null);
      changes.push(`${rank.key} rank: ${fields.join(' · ')}`);
    }
  });
  after.rewards.forEach((reward, index) => {
    const old = before.rewards[index];
    if (old && JSON.stringify(old) !== JSON.stringify(reward)) {
      const fields = [
        old.label !== reward.label ? `label ${old.label} → ${reward.label}` : null,
        old.description !== reward.description ? `description ${old.description} → ${reward.description}` : null,
        old.points_cost !== reward.points_cost ? `cost ${old.points_cost.toLocaleString('en-IN')} → ${reward.points_cost.toLocaleString('en-IN')} pts` : null,
        old.value_minor !== reward.value_minor ? `credit ${inr(old.value_minor)} → ${inr(reward.value_minor)}` : null,
        old.min_rank_key !== reward.min_rank_key ? `minimum rank ${old.min_rank_key} → ${reward.min_rank_key}` : null,
        old.enabled !== reward.enabled ? (reward.enabled ? 'enabled' : 'disabled') : null,
      ].filter((field): field is string => field !== null);
      changes.push(`${reward.key}: ${fields.join(' · ')}`);
    }
  });
  return changes;
}

function cloneRules(rules: LoyaltyProgramRulesDTO): LoyaltyProgramRulesDTO {
  return {
    ...rules,
    ranks: rules.ranks.map((rank) => ({ ...rank })),
    rewards: rules.rewards.map((reward) => ({ ...reward })),
  };
}

export function validateLoyaltyProgramDraft(rules: LoyaltyProgramRulesDTO): string[] {
  const errors: string[] = [];
  const bounded = (value: number, min: number, max: number, label: string) => {
    if (!Number.isInteger(value) || value < min || value > max) errors.push(`${label} must be a whole number from ${min.toLocaleString('en-IN')} to ${max.toLocaleString('en-IN')}.`);
  };
  bounded(rules.gaming_earn_points, 0, 1_000_000, 'Points earned');
  bounded(rules.gaming_earn_rupees, 1, 1_000_000, 'Gaming spend');
  bounded(rules.minor_per_point, 1, 1_000_000, 'Value of 100 points');
  rules.ranks.forEach((rank, index) => {
    bounded(rank.threshold, 0, 2_000_000_000, `${rank.key} threshold`);
    bounded(rank.bonus_points, 0, 2_000_000_000, `${rank.key} bonus`);
    if (!rank.label.trim()) errors.push(`${rank.key} rank needs a label.`);
    if (index === 0 && rank.threshold !== 0) errors.push('Rookie threshold must be 0.');
    if (index > 0 && rank.threshold <= rules.ranks[index - 1].threshold) errors.push(`${rank.key} threshold must be above ${rules.ranks[index - 1].key}.`);
  });
  rules.rewards.forEach((reward) => {
    bounded(reward.points_cost, 1, 2_000_000_000, `${reward.key} points cost`);
    bounded(reward.value_minor, 1, 9_999_999_999, `${reward.key} bill credit`);
    if (!reward.label.trim() || !reward.description.trim()) errors.push(`${reward.key} needs a label and description.`);
  });
  const bonuses = rules.ranks.reduce((sum, rank) => sum + rank.bonus_points, 0);
  if (bonuses > 2_147_483_647) errors.push('Combined rank bonuses exceed the customer points storage limit.');
  if (Number.isInteger(rules.gaming_earn_points) && Number.isInteger(rules.gaming_earn_rupees) && rules.gaming_earn_rupees > 0) {
    const maximumEarned = Math.floor(9_999_999_999 * rules.gaming_earn_points / (rules.gaming_earn_rupees * 100));
    if (maximumEarned * 10 + bonuses > 2_147_483_647) errors.push('This earning rate and the rank bonuses can exceed the points storage limit for one bill.');
  }
  return [...new Set(errors)];
}

export default function LoyaltyProgramTab() {
  const { me } = useAuth();
  const [current, setCurrent] = useState<LoyaltyProgramDTO | null>(null);
  const [draft, setDraft] = useState<LoyaltyProgramRulesDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [reviewing, setReviewing] = useState<PublicationReview | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const generation = useRef(0);

  const load = useCallback(() => {
    const requested = ++generation.current;
    const requestedCompanyId = me?.company_id;
    const controller = new AbortController();
    setLoading(true);
    setError(null);
    settings.getLoyaltyProgram(controller.signal)
      .then((program) => {
        if (requested !== generation.current) return;
        if (!requestedCompanyId || program.company_id !== requestedCompanyId) {
          throw new Error('The signed-in company changed. Reload Settings before editing points.');
        }
        setCurrent(program);
        setDraft(cloneRules(program.rules));
      })
      .catch((reason: Error) => {
        if (requested === generation.current) setError(reason.message);
      })
      .finally(() => {
        if (requested === generation.current) setLoading(false);
      });
    return controller;
  }, [me?.company_id]);

  useEffect(() => {
    setCurrent(null);
    setDraft(null);
    setReviewing(null);
    setSaving(false);
    setNotice(null);
    const controller = load();
    return () => {
      generation.current += 1;
      controller.abort();
    };
  }, [load]);

  const nextValue100 = useMemo(
    () => (draft ? draft.minor_per_point * 100 : 0),
    [draft],
  );
  const dirty = Boolean(current && draft && JSON.stringify(current.rules) !== JSON.stringify(draft));
  const validationErrors = useMemo(() => draft ? validateLoyaltyProgramDraft(draft) : [], [draft]);

  const publish = async (review: PublicationReview) => {
    if (
      !current || !draft || !dirty || validationErrors.length > 0 ||
      !me?.company_id || me.company_id !== review.companyId ||
      current.company_id !== review.companyId || current.revision !== review.expectedRevision ||
      JSON.stringify(draft) !== JSON.stringify(review.rules)
    ) {
      setReviewing(null);
      setError('The company or points program changed after review. Reload and review it again.');
      return;
    }
    const requested = generation.current;
    setSaving(true);
    setError(null);
    setNotice(null);
    try {
      const saved = await settings.publishLoyaltyProgram(
        { expected_revision: review.expectedRevision, rules: review.rules },
        createOperationKey(),
      );
      if (
        requested !== generation.current || me.company_id !== review.companyId ||
        saved.company_id !== review.companyId
      ) return;
      setCurrent(saved);
      setDraft(cloneRules(saved.rules));
      setReviewing(null);
      setNotice(`Points program revision ${saved.revision} was confirmed by the server.`);
    } catch (reason) {
      if (requested === generation.current) {
        setReviewing(null);
        setError((reason as Error).message);
      }
    } finally {
      if (requested === generation.current) setSaving(false);
    }
  };

  if (loading) return <div className="card flex items-center gap-2"><Loader2 className="animate-spin" size={16}/> Loading points program…</div>;
  if (!current || !draft) {
    return <div className="card text-accent-bad">{error ?? 'Points program is unavailable.'}</div>;
  }

  return (
    <div className="space-y-5 max-w-5xl">
      <section className="card space-y-3">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div>
            <h3 className="font-semibold">Points program</h3>
            <p className="text-sm text-fg-muted">
              Gaming earnings, rank awards, and fixed bill-credit rewards. Drinks do not earn points; the paid-hours and WhatsApp proposal remains off.
            </p>
          </div>
          <button className="btn btn-ghost" onClick={load} disabled={saving}>
            <RefreshCw size={14}/> Reload
          </button>
        </div>
        <div className="grid gap-3 sm:grid-cols-3 text-sm">
          <Value label="Current revision" value={String(current.revision)}/>
          <Value label="100 points now" value={inr(current.value_of_100_points_minor)}/>
          <Value label="100 points after save" value={inr(nextValue100)}/>
        </div>
        {current.unresolved_reservation_count > 0 && (
          <div className="rounded-xl border border-accent-warn/40 bg-accent-warn/10 p-3 text-sm">
            <div className="flex items-center gap-2 font-semibold"><AlertCircle size={15}/> Publication blocked</div>
            <p className="mt-1 text-fg-muted">
              Resolve {current.unresolved_reservation_count} unpaid points reservation{current.unresolved_reservation_count === 1 ? '' : 's'} first.
            </p>
            <ul className="mt-2 list-disc pl-5 font-mono text-xs">
              {current.unresolved_reservations.map((row) => <li key={row.order_id}>{row.reference} · {row.status}</li>)}
            </ul>
          </div>
        )}
        {notice && <p className="flex items-center gap-2 text-sm text-accent-good"><CheckCircle2 size={15}/>{notice}</p>}
        {error && <p className="text-sm text-accent-bad">{error}</p>}
        {validationErrors.length > 0 && (
          <div className="rounded-xl border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad">
            <div className="font-semibold">Fix these values before review</div>
            <ul className="mt-1 list-disc pl-5">{validationErrors.map((message) => <li key={message}>{message}</li>)}</ul>
          </div>
        )}
      </section>

      <section className="card space-y-3">
        <h3 className="font-semibold">Gaming earning and generic value</h3>
        <div className="grid gap-3 sm:grid-cols-3">
          <NumberField label="Points earned" value={draft.gaming_earn_points} min={0}
            onChange={(value) => setDraft({ ...draft, gaming_earn_points: value })}/>
          <NumberField label="Per gaming spend (₹)" value={draft.gaming_earn_rupees} min={1}
            onChange={(value) => setDraft({ ...draft, gaming_earn_rupees: value })}/>
          <NumberField label="Value of 100 points (₹)" value={draft.minor_per_point} min={1}
            onChange={(value) => setDraft({ ...draft, minor_per_point: value })}/>
        </div>
        <p className="text-xs text-fg-muted">Membership point multipliers still apply. Zero earning pauses new gaming points without changing balances or history.</p>
      </section>

      <section className="card space-y-3">
        <h3 className="font-semibold">Stable ranks</h3>
        <div className="space-y-3">
          {draft.ranks.map((rank, index) => (
            <div key={rank.key} className="grid gap-2 rounded-xl border border-bg-border p-3 md:grid-cols-[120px_1fr_1fr_1fr]">
              <div className="font-mono text-xs uppercase text-fg-muted self-end pb-3">{rank.key}</div>
              <TextField label="Label" value={rank.label} maxLength={40}
                onChange={(label) => setDraft({ ...draft, ranks: draft.ranks.map((item, i) => i === index ? { ...item, label } : item) })}/>
              <NumberField label="Lifetime threshold" value={rank.threshold} min={index === 0 ? 0 : 1}
                onChange={(threshold) => setDraft({ ...draft, ranks: draft.ranks.map((item, i) => i === index ? { ...item, threshold } : item) })}/>
              <NumberField label="One-time bonus" value={rank.bonus_points} min={0}
                onChange={(bonus_points) => setDraft({ ...draft, ranks: draft.ranks.map((item, i) => i === index ? { ...item, bonus_points } : item) })}/>
            </div>
          ))}
        </div>
      </section>

      <section className="card space-y-3">
        <h3 className="font-semibold">Named bill-credit rewards</h3>
        <div className="space-y-3">
          {draft.rewards.map((reward, index) => (
            <div key={reward.key} className="rounded-xl border border-bg-border p-3 space-y-2">
              <div className="flex items-center justify-between gap-3">
                <span className="font-mono text-xs uppercase text-fg-muted">{reward.key}</span>
                <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={reward.enabled}
                  onChange={(event) => setDraft({ ...draft, rewards: draft.rewards.map((item, i) => i === index ? { ...item, enabled: event.target.checked } : item) })}/> Enabled</label>
              </div>
              <div className="grid gap-2 md:grid-cols-2">
                <TextField label="Label" value={reward.label} maxLength={80}
                  onChange={(label) => setDraft({ ...draft, rewards: draft.rewards.map((item, i) => i === index ? { ...item, label } : item) })}/>
                <TextField label="Description" value={reward.description} maxLength={240}
                  onChange={(description) => setDraft({ ...draft, rewards: draft.rewards.map((item, i) => i === index ? { ...item, description } : item) })}/>
                <NumberField label="Points cost" value={reward.points_cost} min={1}
                  onChange={(points_cost) => setDraft({ ...draft, rewards: draft.rewards.map((item, i) => i === index ? { ...item, points_cost } : item) })}/>
                <MoneyField label="Bill credit (₹)" valueMinor={reward.value_minor}
                  onChangeMinor={(value_minor) => setDraft({ ...draft, rewards: draft.rewards.map((item, i) => i === index ? { ...item, value_minor } : item) })}/>
                <label className="block text-xs text-fg-muted">Minimum rank
                  <select className="input mt-1" value={reward.min_rank_key}
                    onChange={(event) => setDraft({ ...draft, rewards: draft.rewards.map((item, i) => i === index ? { ...item, min_rank_key: event.target.value as LoyaltyRankKey } : item) })}>
                    {draft.ranks.map((rank) => <option key={rank.key} value={rank.key}>{rank.label}</option>)}
                  </select>
                </label>
                <Value label="Customer receives" value={inr(reward.value_minor)}/>
              </div>
            </div>
          ))}
        </div>
      </section>

      <div className="flex justify-end gap-2">
        <button className="btn btn-ghost" disabled={!dirty || saving} onClick={() => setDraft(cloneRules(current.rules))}>Discard changes</button>
        <button className="btn btn-primary" disabled={!dirty || saving || validationErrors.length > 0 || current.unresolved_reservation_count > 0 || current.company_id !== me?.company_id} onClick={() => setReviewing({ companyId: current.company_id, expectedRevision: current.revision, currentValue100Minor: current.value_of_100_points_minor, currentRules: cloneRules(current.rules), rules: cloneRules(draft) })}>Review and publish</button>
      </div>

      {reviewing && (
        <ConfirmModal
          title="Review points program"
          size="md"
          message={<div className="space-y-2 text-sm">
            <p>Publish revision <b>{reviewing.expectedRevision + 1}</b> for future activity?</p>
            <p>100 points changes from <b>{inr(reviewing.currentValue100Minor)}</b> to <b>{inr(reviewing.rules.minor_per_point * 100)}</b>.</p>
            <ul className="list-disc space-y-1 pl-5">
              {summarizeLoyaltyProgramChanges(reviewing.currentRules, reviewing.rules).map((change) => <li key={change}>{change}</li>)}
            </ul>
            <p className="text-fg-muted">Existing point counts stay unchanged; future redemption value follows the new rate. Accepted reservations, invoices and refunds keep their values. New thresholds and bonuses apply only on a later paid gaming checkout.</p>
          </div>}
          confirmLabel="Publish program"
          busy={saving}
          confirmDisabled={validationErrors.length > 0 || current.unresolved_reservation_count > 0}
          onConfirm={() => void publish(reviewing)}
          onCancel={() => setReviewing(null)}
        />
      )}
    </div>
  );
}

function NumberField({ label, value, min, onChange }: { label: string; value: number; min: number; onChange: (value: number) => void }) {
  return <label className="block text-xs text-fg-muted">{label}<input className="input mt-1" type="number" min={min} step="1" value={Number.isNaN(value) ? '' : value} onChange={(event) => onChange(event.target.value === '' ? Number.NaN : Number(event.target.value))}/></label>;
}
function MoneyField({ label, valueMinor, onChangeMinor }: { label: string; valueMinor: number; onChangeMinor: (value: number) => void }) {
  const emitted = useRef(valueMinor);
  const [raw, setRaw] = useState(minorToRupeesInput(valueMinor));
  useEffect(() => {
    if (!Object.is(valueMinor, emitted.current)) {
      emitted.current = valueMinor;
      setRaw(minorToRupeesInput(valueMinor));
    }
  }, [valueMinor]);
  return <label className="block text-xs text-fg-muted">{label}<input className="input mt-1" inputMode="decimal" value={raw} onChange={(event) => {
    const next = event.target.value;
    setRaw(next);
    const parsed = parseRupeesToMinor(next);
    emitted.current = parsed ?? Number.NaN;
    onChangeMinor(parsed ?? Number.NaN);
  }}/></label>;
}
function TextField({ label, value, maxLength, onChange }: { label: string; value: string; maxLength: number; onChange: (value: string) => void }) {
  return <label className="block text-xs text-fg-muted">{label}<input className="input mt-1" value={value} maxLength={maxLength} onChange={(event) => onChange(event.target.value)}/></label>;
}
function Value({ label, value }: { label: string; value: string }) {
  return <div className="rounded-xl border border-bg-border p-3"><div className="text-xs text-fg-muted">{label}</div><div className="mt-1 font-mono font-semibold">{value}</div></div>;
}
