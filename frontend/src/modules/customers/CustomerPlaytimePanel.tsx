import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AlertCircle, Clock3, Loader2, MessageSquareText, Save, Search, Trophy } from 'lucide-react';

import {
  customers,
  type CustomerPlaytimeDTO,
  type PlaytimeLeaderboardDTO,
} from '@/lib/erp-api';
import { useAuth } from '@/modules/auth/AuthContext';
import { useNotifications } from '@/components/ui/Notifications';
import { draftPlaytimeMessage, formatPlayMinutes } from './customer-playtime';
import { canEditPlaytimeDraft } from './customer-access';

const PAGE_SIZE = 10;

export default function CustomerPlaytimePanel() {
  const { me, demo } = useAuth();
  const notifications = useNotifications();
  const canManage = canEditPlaytimeDraft(me, demo);
  const [data, setData] = useState<PlaytimeLeaderboardDTO | null>(null);
  const [query, setQuery] = useState('');
  const [activeQuery, setActiveQuery] = useState('');
  const [page, setPage] = useState(1);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [selected, setSelected] = useState<CustomerPlaytimeDTO | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [saving, setSaving] = useState(false);
  const [draft, setDraft] = useState({ threshold: '600', reward: '60', phone: '' });
  const leaderboardRequest = useRef<AbortController | null>(null);
  const detailRequest = useRef<AbortController | null>(null);

  const load = useCallback(async (nextPage: number, q: string) => {
    leaderboardRequest.current?.abort();
    const request = new AbortController();
    leaderboardRequest.current = request;
    setLoading(true);
    setError(null);
    try {
      const response = await customers.playtimeLeaderboard({
        page: nextPage,
        limit: PAGE_SIZE,
        ...(q.trim() ? { q: q.trim() } : {}),
      }, request.signal);
      if (request.signal.aborted) return;
      setData(response);
      setDraft({
        threshold: String(response.program.threshold_paid_minutes),
        reward: String(response.program.reward_minutes),
        phone: response.program.company_whatsapp_phone ?? '',
      });
      setSelected(null);
    } catch (cause) {
      if (request.signal.aborted) return;
      setError((cause as Error).message);
    } finally {
      if (leaderboardRequest.current === request) setLoading(false);
    }
  }, []);

  useEffect(() => { void load(page, activeQuery); }, [activeQuery, load, page]);
  useEffect(() => () => {
    leaderboardRequest.current?.abort();
    detailRequest.current?.abort();
  }, []);

  async function openDetail(customerId: string) {
    detailRequest.current?.abort();
    const request = new AbortController();
    detailRequest.current = request;
    setDetailLoading(true);
    setSelected(null);
    setError(null);
    try {
      const response = await customers.playtime(customerId, {}, request.signal);
      if (!request.signal.aborted) setSelected(response);
    } catch (cause) {
      if (request.signal.aborted) return;
      setError((cause as Error).message);
    } finally {
      if (detailRequest.current === request) setDetailLoading(false);
    }
  }

  async function saveDraft() {
    if (!canManage || saving) return;
    const threshold = Number(draft.threshold);
    const reward = Number(draft.reward);
    if (!Number.isInteger(threshold) || threshold < 1 || !Number.isInteger(reward) || reward < 1) {
      setError('Paid and draft reward minutes must be positive whole numbers.');
      return;
    }
    setSaving(true);
    setError(null);
    try {
      const program = await customers.savePlaytimeProgram({
        threshold_paid_minutes: threshold,
        reward_minutes: reward,
        company_whatsapp_phone: draft.phone.trim() || null,
      });
      setData((current) => current ? { ...current, program } : current);
      await load(page, activeQuery);
      notifications.success('Draft figures were saved. No rewards or messages were enabled.', {
        title: 'Draft saved',
      });
    } catch (cause) {
      setError((cause as Error).message);
    } finally {
      setSaving(false);
    }
  }

  const previewCustomer = useMemo(() => {
    if (!data?.items.length) return null;
    return data.items.find((item) => item.customer_id === selected?.customer_id) ?? data.items[0];
  }, [data?.items, selected?.customer_id]);
  const previewProgram = useMemo(() => {
    if (!data) return null;
    const threshold = Number(draft.threshold);
    const reward = Number(draft.reward);
    return {
      ...data.program,
      threshold_paid_minutes: Number.isInteger(threshold) && threshold > 0
        ? threshold
        : data.program.threshold_paid_minutes,
      reward_minutes: Number.isInteger(reward) && reward > 0
        ? reward
        : data.program.reward_minutes,
    };
  }, [data, draft.reward, draft.threshold]);
  const totalPages = data ? Math.max(1, Math.ceil(data.total / data.limit)) : 1;

  return (
    <section className="card mb-6 border-accent-purple/30" aria-labelledby="playtime-heading">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <Clock3 size={18} className="text-accent-purple"/>
            <h3 id="playtime-heading" className="font-semibold">Play hours & leaderboard</h3>
            <span className="chip text-[10px] text-accent-gold border-accent-gold/40">DRAFT</span>
          </div>
          <p className="mt-1 text-xs text-fg-muted">
            Recorded completed play is separate from conservative qualifying paid play.
            Draft estimates are proposals only; no free time is issued or available.
          </p>
        </div>
      </div>

      {error && (
        <div className="mt-3 rounded-lg border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad flex gap-2">
          <AlertCircle size={15} className="mt-0.5 shrink-0"/> {error}
        </div>
      )}

      {data && (
        <div className="mt-4 grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(280px,0.72fr)]">
          <div className="rounded-lg border border-bg-border p-3">
            <div className="grid gap-2 sm:grid-cols-3">
              <label className="text-xs text-fg-muted">
                Paid minutes threshold
                <input className="input mt-1" type="number" min={1} max={525600}
                  disabled={!canManage} value={draft.threshold}
                  onChange={(event) => setDraft((value) => ({ ...value, threshold: event.target.value }))}/>
              </label>
              <label className="text-xs text-fg-muted">
                Draft reward minutes
                <input className="input mt-1" type="number" min={1} max={10080}
                  disabled={!canManage} value={draft.reward}
                  onChange={(event) => setDraft((value) => ({ ...value, reward: event.target.value }))}/>
              </label>
              <label className="text-xs text-fg-muted">
                Company WhatsApp phone (later)
                <input className="input mt-1" type="tel" maxLength={20}
                  disabled={!canManage} placeholder="Optional"
                  value={draft.phone}
                  onChange={(event) => setDraft((value) => ({ ...value, phone: event.target.value }))}/>
              </label>
            </div>
            <div className="mt-3 flex items-center justify-between gap-3 text-xs text-fg-muted">
              <span>Messaging and rewards remain disabled until partner approval and setup.</span>
              {canManage && (
                <button type="button" className="btn btn-ghost shrink-0" disabled={saving}
                  onClick={() => { void saveDraft(); }}>
                  {saving ? <Loader2 size={14} className="animate-spin"/> : <Save size={14}/>} Save draft
                </button>
              )}
            </div>
          </div>

          <div className="rounded-lg border border-bg-border bg-bg-raised p-3">
            <div className="flex items-center gap-2 text-xs font-semibold">
              <MessageSquareText size={14}/> WhatsApp message preview
            </div>
            <p className="mt-2 whitespace-pre-wrap text-sm text-fg-muted">
              {previewCustomer && previewProgram
                ? draftPlaytimeMessage(previewCustomer, previewProgram)
                : 'Choose a tracked customer to preview a draft message. Nothing will be sent.'}
            </p>
          </div>
        </div>
      )}

      <form className="mt-4 flex max-w-md gap-2" onSubmit={(event) => {
        event.preventDefault();
        setActiveQuery(query);
        setPage(1);
      }}>
        <input className="input" value={query} placeholder="Search leaderboard…"
          onChange={(event) => setQuery(event.target.value)}/>
        <button className="btn btn-ghost" type="submit"><Search size={14}/> Find</button>
      </form>

      {loading ? (
        <div className="mt-4 flex items-center gap-2 text-sm text-fg-muted">
          <Loader2 size={15} className="animate-spin"/> Loading recorded play hours…
        </div>
      ) : !data?.items.length ? (
        <div className="mt-4 rounded-lg border border-bg-border p-4 text-sm text-fg-muted">
          No customers match this leaderboard view. Customers with zero play appear once they are saved.
        </div>
      ) : (
        <div className="mt-4 overflow-x-auto rounded-lg border border-bg-border">
          <table className="w-full min-w-[650px] text-sm">
            <thead className="bg-bg-raised text-xs text-fg-muted">
              <tr><th className="p-3 text-left">Rank</th><th className="p-3 text-left">Customer</th>
                <th className="p-3 text-right">Recorded play</th><th className="p-3 text-right">Qualifying paid</th>
                <th className="p-3 text-right">Draft estimate</th></tr>
            </thead>
            <tbody>
              {data.items.map((item) => (
                <tr key={item.customer_id} className="border-t border-bg-border/70 hover:bg-bg-raised/60">
                  <td className="p-3 font-mono"><Trophy size={12} className="mr-1 inline text-accent-gold"/>#{item.rank}</td>
                  <td className="p-3"><button type="button"
                    className="font-medium text-left hover:text-accent focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-accent"
                    onClick={() => { void openDetail(item.customer_id); }}>{item.name || 'No name'}</button>
                    <div className="text-xs text-fg-muted font-mono">{item.masked_phone}</div></td>
                  <td className="p-3 text-right font-mono">{formatPlayMinutes(item.total_played_minutes)}</td>
                  <td className="p-3 text-right font-mono">{formatPlayMinutes(item.qualifying_paid_minutes)}</td>
                  <td className="p-3 text-right font-mono text-accent-purple">
                    Draft estimate: {formatPlayMinutes(item.draft_estimated_reward_minutes)}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {data && data.total > data.limit && (
        <div className="mt-3 flex justify-end gap-2 text-xs">
          <button className="btn btn-ghost" disabled={page <= 1 || loading || saving} onClick={() => setPage((value) => value - 1)}>Previous</button>
          <span className="self-center text-fg-muted">Page {page} of {totalPages}</span>
          <button className="btn btn-ghost" disabled={page >= totalPages || loading || saving} onClick={() => setPage((value) => value + 1)}>Next</button>
        </div>
      )}

      {detailLoading && <div className="mt-3 text-xs text-fg-muted">Loading completed session history…</div>}
      {selected && !detailLoading && (
        <div className="mt-4 rounded-lg border border-bg-border p-3">
          <div className="font-medium">{selected.customer_name || 'Customer'} · completed sessions</div>
          <div className="mt-1 text-xs text-fg-muted">
            {formatPlayMinutes(selected.total_played_minutes)} recorded · {formatPlayMinutes(selected.qualifying_paid_minutes)} qualifying paid · Draft estimate {formatPlayMinutes(selected.draft_estimated_reward_minutes)}
          </div>
          {!selected.history.length ? <p className="mt-3 text-sm text-fg-muted">No completed gaming sessions recorded.</p> : (
            <div className="mt-3 space-y-2">
              {selected.history.map((row) => (
                <div key={row.session_id} className="flex flex-wrap justify-between gap-2 rounded-lg bg-bg-raised p-2 text-xs">
                  <span>{row.station_name} · {row.ended_at ? new Date(row.ended_at).toLocaleString('en-IN') : 'End time unavailable'}</span>
                  <span className="font-mono">{formatPlayMinutes(row.played_minutes)} · {row.qualification_status.replaceAll('_', ' ')}</span>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}
