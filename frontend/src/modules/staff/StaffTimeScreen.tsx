import './staff-time.css';
import { useCallback, useEffect, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { Clock, CalendarDays, RefreshCw, History, ShieldCheck, ArrowRight, Pencil } from 'lucide-react';
import { useAuth, type Me } from '@/modules/auth/AuthContext';
import { readSessionGeneration } from '@/lib/api';
import { readStoredTerminalId } from '@/lib/operational-context';
import { inr } from '@/lib/inr';
import { parseRupeesToMinor, minorToRupeesInput } from '@/lib/money-input';
import { ownerScope, readOwnerWorkspace, rememberOwnerWorkspace } from '@/lib/owner-workspace';
import { StableMutationIntent } from '@/lib/stable-mutation-intent';
import { staffTime, type StaffTimePerson, type StaffAttendance, type StaffTimeAction, type StaffTimeSummary, type StaffWorkPolicy } from '@/lib/staff-time-api';
import Modal from '@/components/ui/Modal';
import StaffPayEstimateCard from './StaffPayEstimateCard';
import { validatePayEstimate } from './staff-pay-estimate';
import { canManageStaffTime, canUseStaffTime, confirmedWriteRefusal, currentMonth, duration, readUtcInput, utcInput, wholeMinutes } from './staff-time-policy';
const GLASS = 'rounded-2xl border border-bg-border/70 bg-bg-surface/60 p-5 shadow-lg backdrop-blur-xl';
const DAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];
function isAccessRefusal(error: unknown): boolean {
  const status = typeof error === 'object' && error !== null ? (error as { status?: number }).status : undefined;
  return status === 401 || status === 403 || status === 404;
}
function ErrorText({
  children
}: {
  children: ReactNode;
}) {
  return <p role="alert" className="rounded-xl border border-accent-bad/30 bg-accent-bad/10 p-3 text-sm text-accent-bad">{children}</p>;
}
function Label({
  title,
  children
}: {
  title: string;
  children: ReactNode;
}) {
  return <label className="block space-y-1.5 text-sm"><span className="text-fg-muted">{title}</span>{children}</label>;
}
function timestamp(value: string, timezone: string) {
  return new Intl.DateTimeFormat('en-IN', {
    timeZone: timezone,
    dateStyle: 'medium',
    timeStyle: 'short'
  }).format(new Date(value));
}
export default function StaffTimeScreen() {
  const {
    me,
    terminalId,
    terminalOptions
  } = useAuth();
  if (!me || !canUseStaffTime(me)) return <ErrorText>Working-time access is unavailable for this account.</ErrorText>;
  const canManage = canManageStaffTime(me);
  const canList = canManage;
  const generation = readSessionGeneration();
  const scope = `${ownerScope(me)}:${terminalId ?? 'none'}:${generation}:${canManage}:${canList}`;
  const branchId = terminalOptions.find(t => t.id === terminalId)?.branch_id ?? me.branch_id;
  return <StaffTimeWorkspace key={scope} me={me} scope={scope} generation={generation} terminalId={terminalId} branchId={branchId} canManage={canManage} canList={canList} />;
}
function StaffTimeWorkspace({
  me,
  scope,
  generation,
  terminalId,
  branchId,
  canManage,
  canList
}: {
  me: Me;
  scope: string;
  generation: number;
  terminalId: string | null;
  branchId: string | null;
  canManage: boolean;
  canList: boolean;
}) {
  const [target, setTarget] = useState(() => canManage ? readOwnerWorkspace<{
    target: string;
  }>(scope, 'staff-time-view')?.target ?? me.user_id : me.user_id);
  const [month, setMonth] = useState(() => readOwnerWorkspace<{
    month: string;
  }>(scope, 'staff-time-view')?.month ?? currentMonth());
  const monthChosen = useRef(readOwnerWorkspace<{
    monthExplicit?: boolean;
    month: string;
  }>(scope, 'staff-time-view')?.monthExplicit ?? Boolean(readOwnerWorkspace<{
    month: string;
  }>(scope, 'staff-time-view')?.month));
  const [users, setUsers] = useState<StaffTimePerson[]>([]);
  const [peopleCursor, setPeopleCursor] = useState<string | null>(null);
  const [peopleBusy, setPeopleBusy] = useState(false);
  const [directoryError, setDirectoryError] = useState<string | null>(null);
  const [summary, setSummary] = useState<StaffTimeSummary | null>(null);
  const [current, setCurrent] = useState<StaffTimeSummary | null>(null);
  const [policies, setPolicies] = useState<StaffWorkPolicy[]>([]);
  const [policiesReady, setPoliciesReady] = useState(false);
  const [policyError, setPolicyError] = useState<string | null>(null);
  const [hasOlder, setHasOlder] = useState(false);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [dialog, setDialog] = useState<'clock' | 'policy' | StaffAttendance | null>(null);
  const [intent] = useState(() => {
    const existing = readOwnerWorkspace<StableMutationIntent<StaffTimeAction>>(scope, 'staff-time-intent');
    if (existing) return existing;
    const created = new StableMutationIntent<StaffTimeAction>({
      prefix: 'staff-time'
    });
    rememberOwnerWorkspace(scope, 'staff-time-intent', created);
    return created;
  });
  const [pending, setPending] = useState(() => intent.peek());
  const [reconciled, setReconciled] = useState(false);
  const mounted = useRef(true);
  const writeBusy = useRef(false);
  const requests = useRef(0);
  const authorityEpoch = useRef(0);
  const abort = useRef<AbortController | null>(null);
  const timezone = summary?.timezone ?? 'UTC';
  const thisMonth = currentMonth(timezone);
  const currentScope = useCallback(() => readSessionGeneration() === generation && readStoredTerminalId() === terminalId, [generation, terminalId]);
  const clearPrivateProjection = useCallback(() => {
    ++authorityEpoch.current;
    abort.current?.abort();
    ++requests.current;
    setLoading(false);
    setSummary(null);
    setCurrent(null);
    setPolicies([]);
    setPoliciesReady(false);
    setPolicyError(null);
    setHasOlder(false);
    setUsers([]);
    setPeopleBusy(false);
    setPeopleCursor(null);
    setDirectoryError(null);
    setDialog(null);
    setReconciled(false);
  }, []);
  useEffect(() => {
    mounted.current = true;
    return () => {
      mounted.current = false;
      abort.current?.abort();
    };
  }, []);
  useEffect(() => {
    if (currentScope()) rememberOwnerWorkspace(scope, 'staff-time-view', {
      target,
      month,
      monthExplicit: monthChosen.current
    });
  }, [scope, target, month, currentScope]);
  useEffect(() => {
    if (!pending) return;
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };
    window.addEventListener('beforeunload', warn);
    return () => window.removeEventListener('beforeunload', warn);
  }, [pending]);
  useEffect(() => {
    if (!canList) return;
    let active = true;
    const epoch = authorityEpoch.current;
    void staffTime.people().then(page => {
      if (active && epoch === authorityEpoch.current && currentScope()) {
        setUsers(page.items);
        setPeopleCursor(page.next_cursor);
      }
    }).catch(e => {
      if (!active || epoch !== authorityEpoch.current || !currentScope()) return;
      if (isAccessRefusal(e)) {
        clearPrivateProjection();
        setError('Access changed. Owner review and a successful refresh are required before continuing.');
      } else {
        setDirectoryError('The people list could not be loaded. Your own time is still available.');
      }
    });
    return () => {
      active = false;
    };
  }, [canList, currentScope, clearPrivateProjection]);
  const load = useCallback(async () => {
    const sequence = ++requests.current;
    abort.current?.abort();
    const controller = new AbortController();
    abort.current = controller;
    setLoading(true);
    setError(null);
    try {
      const [view, live, history] = await Promise.all([staffTime.summary(month, target === me.user_id ? undefined : target, controller.signal), month === thisMonth ? Promise.resolve(null) : staffTime.summary(thisMonth, target === me.user_id ? undefined : target, controller.signal), canManage ? staffTime.policies(target, undefined, controller.signal).catch(e => {
        if (isAccessRefusal(e)) throw e;
        return null;
      }) : Promise.resolve([])]);
      if (!mounted.current || sequence !== requests.current || !currentScope()) return;
      validatePayEstimate(view, canManage);
      if (live) validatePayEstimate(live, canManage);
      const companyMonth = currentMonth(view.timezone);
      if (!monthChosen.current && month !== companyMonth) {
        setMonth(companyMonth);
        return;
      }
      const actualCurrent = month === companyMonth ? view : companyMonth === thisMonth ? live : await staffTime.summary(companyMonth, target === me.user_id ? undefined : target, controller.signal);
      if (!mounted.current || sequence !== requests.current || !currentScope()) return;
      setSummary(view);
      setCurrent(actualCurrent ?? view);
      setPolicies(history ?? []);
      setPoliciesReady(history !== null);
      setPolicyError(history === null ? 'Draft revision history is unavailable. Refresh before creating a new revision.' : null);
      setHasOlder(history?.length === 50);
      setReconciled(true);
      setPending(intent.peek());
      return true;
    } catch (e) {
      if (mounted.current && !controller.signal.aborted && sequence === requests.current && currentScope()) {
        if (isAccessRefusal(e)) {
          clearPrivateProjection();
          setError('Access changed. Owner review and a successful refresh are required before continuing.');
        } else {
          setSummary(null);
          setCurrent(null);
          setPolicies([]);
          setReconciled(false);
          setError(e instanceof Error ? e.message : 'Working time could not be loaded.');
        }
      }
    } finally {
      if (mounted.current && sequence === requests.current) setLoading(false);
    }
  }, [month, target, me.user_id, thisMonth, canManage, currentScope, intent, clearPrivateProjection]);
  useEffect(() => {
    void load();
    return () => {
      abort.current?.abort();
    };
  }, [load]);
  async function submit(action?: StaffTimeAction) {
    if (writeBusy.current || !currentScope()) return;
    const original = intent.peek();
    if (original && action) return;
    const value = original ?? (action ? intent.resolve(JSON.stringify(action), () => structuredClone(action)) : null);
    if (!value) {
      setPending(null);
      void load();
      return;
    }
    writeBusy.current = true;
    setBusy(true);
    setPending(value);
    setError(null);
    setNotice(null);
    setReconciled(false);
    try {
      await staffTime.submit(value.payload, value.idempotencyKey, {
        generation,
        terminalId
      });
      intent.confirmSuccess(value);
      if (mounted.current && currentScope()) {
        setPending(null);
        setDialog(null);
        setNotice('Saved. Refreshing records.');
        const refreshed = await load();
        if (mounted.current && currentScope()) setNotice(refreshed ? 'Saved. Records refreshed.' : 'Saved. Refresh records to review the confirmed write.');
      }
    } catch (e) {
      if (!mounted.current || !currentScope()) return;
      const accessChanged = isAccessRefusal(e);
      const refusedFirstAttempt = !original && confirmedWriteRefusal(e);
      if (refusedFirstAttempt) {
        intent.invalidate();
        setPending(null);
      } else {
        // A later refusal cannot establish whether the earlier request committed.
        setDialog(null);
      }
      if (accessChanged) clearPrivateProjection();
      setError(original && accessChanged
        ? 'Access changed. The earlier request remains unconfirmed. Owner review and a successful refresh are required before retrying this exact request.'
        : e instanceof Error ? e.message : 'The response could not be confirmed.');
    } finally {
      writeBusy.current = false;
      if (mounted.current) setBusy(false);
    }
  }
  const valid = summary?.user_id === target && summary.month === month;
  const view = valid ? summary : null;
  const live = current?.user_id === target ? current : null;
  const open = live?.entries.find(row => row.clock_out_at === null);
  const selectedPerson = users.find(u => u.id === target);
  const inactive = target !== me.user_id && Boolean(selectedPerson?.departed || selectedPerson && selectedPerson.status !== 'active');
  const selectedName = target === me.user_id ? me.name : users.find(u => u.id === target)?.name ?? view?.name ?? 'Selected staff member';
  const locked = busy || pending !== null;
  const canRecord = target !== me.user_id ? canManage : canManage || me.effective_permissions?.includes('staff.attendance.write') === true;
  async function morePeople() {
    if (!peopleCursor || peopleBusy || locked) return;
    setPeopleBusy(true);
    setDirectoryError(null);
    const epoch = authorityEpoch.current;
    try {
      const page = await staffTime.people(peopleCursor);
      if (mounted.current && epoch === authorityEpoch.current && currentScope()) {
        setUsers(rows => [...rows, ...page.items.filter(item => !rows.some(row => row.id === item.id))]);
        setPeopleCursor(page.next_cursor);
      }
    } catch (e) {
      if (mounted.current && epoch === authorityEpoch.current && currentScope()) {
        if (isAccessRefusal(e)) {
          clearPrivateProjection();
          setError('Access changed. Owner review and a successful refresh are required before continuing.');
        } else {
          setDirectoryError('More people could not be loaded. Retry the same page.');
        }
      }
    } finally {
      if (mounted.current) setPeopleBusy(false);
    }
  }
  async function olderPolicies() {
    if (!policies.length || loading || locked) return;
    setLoading(true);
    const sequence = requests.current;
    try {
      const more = await staffTime.policies(target, policies[policies.length - 1].revision, abort.current?.signal);
      if (mounted.current && sequence === requests.current && currentScope()) {
        setPolicies(rows => [...rows, ...more]);
        setHasOlder(more.length === 50);
      }
    } catch (e) {
      if (mounted.current && sequence === requests.current && currentScope()) {
        if (isAccessRefusal(e)) {
          clearPrivateProjection();
          setError('Access changed. Owner review and a successful refresh are required before continuing.');
        } else {
          setError(e instanceof Error ? e.message : 'Older revisions could not be loaded.');
        }
      }
    } finally {
      if (mounted.current && sequence === requests.current) setLoading(false);
    }
  }
  return <div className="mx-auto max-w-6xl space-y-5 pb-8">
    <header className="flex flex-wrap items-end justify-between gap-4">
      <div><p className="mb-1 text-xs font-semibold uppercase tracking-[0.18em] text-accent">Staff workspace</p>
        <h1 className="text-2xl font-bold">Time & attendance</h1>
        <p className="mt-1 text-sm text-fg-muted">Individual records. Actual breaks. Clear monthly history.</p></div>
      <button className="btn btn-ghost" disabled={busy || loading} onClick={() => void load()}><RefreshCw size={16} /> Refresh records</button>
    </header>
    <section className={GLASS} aria-label="Choose attendance history">
      <div className="grid gap-4 sm:grid-cols-2">
        {canList ? <Label title="Person"><select className="input" value={target} disabled={locked} onChange={e => {
            setTarget(e.target.value);
            setDialog(null);
          }}>
          <option value={me.user_id}>{me.name} · me</option>
          {target !== me.user_id && !users.some(u => u.id === target) && <option value={target}>{selectedName} · retained selection</option>}
          {users.filter(u => u.id !== me.user_id).map(u => <option key={u.id} value={u.id}>{u.name}{u.departed ? ' · departed' : u.status !== 'active' ? ' · suspended' : ''}</option>)}
        </select></Label> : <div><p className="text-sm text-fg-muted">Person</p><p className="mt-3 font-semibold">{me.name} · my time</p></div>}
        <Label title="Month"><input className="input" aria-label="Attendance month" type="month" min="2000-01" max="2100-12" required value={month} disabled={locked} onChange={e => {
            if (/^\d{4}-\d{2}$/.test(e.target.value)) {
              monthChosen.current = true;
              setMonth(e.target.value);
            }
          }} /></Label>
      </div>
      {canList && <p className="mt-3 text-xs text-fg-muted">{users.length} people loaded{peopleCursor ? ' · more history is available below' : ''}</p>}
      {canList && peopleCursor && <button className="btn btn-ghost mt-3" disabled={peopleBusy || locked} onClick={() => void morePeople()}>{peopleBusy ? 'Loading people…' : 'Load more people'}</button>}
      {directoryError && <p className="mt-3 text-sm text-accent-warn">{directoryError}</p>}
    </section>
    {error && <ErrorText>{error}</ErrorText>}
    {pending && <section className={`${GLASS} border-accent-warn/50`} aria-label="Unconfirmed staff action">
      <h2 className="font-semibold">Keep this request together</h2><p className="mt-2 text-sm text-fg-muted">The response is unconfirmed. Refresh records, then retry the exact request if needed. A new request is blocked until this one is resolved. Keep this signed-in browser session open.</p>
      <button className="btn btn-primary mt-3" disabled={busy || loading || !reconciled} onClick={() => void submit()}>Retry same request</button>
    </section>}
    {notice && <p role="status" className="text-sm text-accent-good">{notice}</p>}
    {loading && !view && <p role="status" className="text-sm text-fg-muted">Loading working-time records…</p>}
    {view && <>
      <section className="grid gap-3 sm:grid-cols-3" aria-label="Monthly attendance totals">
        <Metric title="Recorded presence · closed" value={duration(view.closed_gross_seconds)} icon={<Clock size={18} />} />
        <Metric title="Recorded work · after breaks" value={duration(view.closed_work_seconds)} icon={<ShieldCheck size={18} />} />
        <Metric title="Open entry · provisional" value={duration(view.provisional_open_seconds)} icon={<CalendarDays size={18} />} />
      </section>
      <section className={GLASS} aria-label="Current attendance action">
        <div className="flex flex-wrap items-center justify-between gap-4"><div><h2 className="font-semibold">{selectedName} · {open ? 'clocked in' : 'not clocked in'}</h2>
          <p className="mt-1 text-sm text-fg-muted">{open ? `Started ${timestamp(open.clock_in_at, view.timezone)}` : 'New clock-ins use the current validated branch.'}</p></div>
          <button className="btn btn-primary" disabled={locked || loading || !live || !canRecord || !open && (!branchId || inactive)} onClick={() => {
            if (open || target !== me.user_id) setDialog('clock');else if (branchId) void submit({
              kind: 'self-in',
              body: {
                branch_id: branchId
              }
            });
          }}>{open ? 'Record actual break & clock out' : 'Clock in'}<ArrowRight size={16} /></button>
        </div>
        {inactive && <p className="mt-3 text-sm text-fg-muted">Departed or suspended staff: retained records remain available. A new clock-in is disabled.</p>}
        {!canRecord && <p className="mt-3 text-sm text-fg-muted">This account does not have permission to record its own time.</p>}
        {!branchId && !open && <p className="mt-3 text-sm text-accent-warn">Choose a validated branch terminal before clocking in.</p>}
        <p className="mt-3 text-xs text-fg-muted">Actual recorded breaks are separate from any planned break allowance. Attendance does not create a salary payment.</p>
      </section>
      {view.schedule && <section className={GLASS}><h2 className="font-semibold">Planned schedule</h2><p className="mt-2 text-sm">{view.schedule.daily_presence_minutes} min presence · {view.schedule.daily_break_minutes} min planned break · {view.schedule.daily_presence_minutes - view.schedule.daily_break_minutes} min planned work</p>
        <p className="mt-1 text-xs text-fg-muted">Effective {view.schedule.effective_from} · {DAYS.filter((_, i) => view.schedule!.working_weekday_mask & 1 << i).join(', ')}. This is a plan, not recorded attendance.</p></section>}
      {view.review_attendance_ids.length > 0 && <ErrorText>Some entries need Owner review. Work totals are withheld where breaks or overlapping history cannot be determined.</ErrorText>}
      <section className={`${GLASS} overflow-hidden`} aria-label="Attendance entries"><h2 className="mb-4 flex items-center gap-2 font-semibold"><History size={17} /> Monthly entries</h2>
        <p className="mb-3 text-xs text-fg-muted">Times shown in {view.timezone}. Snapshot {timestamp(view.as_of, view.timezone)}.</p>
        {!view.entries.length ? <p className="text-sm text-fg-muted">No entries overlap this month.</p> : <div className="space-y-3">{view.entries.map(row => <article key={row.id} className="flex flex-wrap items-start justify-between gap-3 rounded-xl border border-bg-border/60 bg-bg-raised/40 p-4">
          <div><p className="text-sm font-semibold">{timestamp(row.clock_in_at, view.timezone)}</p><p className="mt-1 text-sm text-fg-muted">{row.clock_out_at ? `Until ${timestamp(row.clock_out_at, view.timezone)}` : 'Still open · provisional'}</p><p className="mt-1 text-xs text-fg-muted">{row.clock_out_at ? row.break_confirmed === true ? `Recorded break ${row.break_minutes} min` : 'Actual break unconfirmed · Owner review' : 'Confirm actual break at clock-out'} · {row.source}</p></div>
          {canManage && <button className="btn btn-ghost" disabled={locked} onClick={() => setDialog(row)}><Pencil size={14} /> Correct entry</button>}
        </article>)}</div>}
      </section>
      {canManage && <StaffPayEstimateCard key={`${view.user_id}:${view.month}:${view.as_of}:${scope}`} summary={view} />}
      {canManage && <section className={GLASS} aria-label="Owner draft salary policies"><div className="flex flex-wrap items-center justify-between gap-3"><div><p className="text-xs font-semibold uppercase tracking-wider text-accent">Draft only</p><h2 className="mt-1 font-semibold">Salary proposals & revisions</h2></div><button className="btn btn-ghost" disabled={locked || loading || inactive || !policiesReady} onClick={() => setDialog('policy')}>New draft revision</button></div>
        <p className="mt-3 text-sm text-fg-muted">No approved overtime rate, deductions or payable amount. These records do not post payroll or expenses.</p>
        {view.policy && <p className="mt-3 text-sm">Month reference proposal: {inr(view.policy.monthly_salary_minor)} · effective {view.policy.effective_from} · revision {view.policy.revision}</p>}
        {!view.policy_covers_full_month && <p className="mt-2 text-sm text-fg-muted">A full-month reference is unavailable without one policy covering the whole month.</p>}
        {policyError && <p role="alert" className="mt-3 text-sm text-accent-warn">{policyError}</p>}
        <ol className="mt-4 space-y-3">{policies.map(p => <li key={p.id} className="rounded-xl border border-bg-border/60 p-3"><p className="text-sm font-semibold">Revision {p.revision} · {inr(p.monthly_salary_minor)} / month · draft</p><p className="mt-1 text-xs text-fg-muted">Effective {p.effective_from} · presence {p.daily_presence_minutes} min / break {p.daily_break_minutes} min</p><p className="mt-2 whitespace-pre-wrap break-words text-sm">{p.reason}</p></li>)}</ol>
        {!policies.length && <p className="mt-3 text-sm text-fg-muted">No draft policy recorded. The proposal defaults are not an active agreement.</p>}
        {hasOlder && <button className="btn btn-ghost mt-3" disabled={locked || loading} onClick={() => void olderPolicies()}>Load older revisions</button>}
      </section>}
    </>}
    {dialog === 'clock' && <ClockDialog remoteError={error} open={open} owner={target !== me.user_id} disabled={locked} onClose={() => setDialog(null)} onSave={(minutes, reason) => {
      if (open) void submit(target === me.user_id ? {
        kind: 'self-out',
        body: {
          attendance_id: open.id,
          break_minutes: minutes
        }
      } : {
        kind: 'owner-out',
        userId: target,
        body: {
          attendance_id: open.id,
          break_minutes: minutes,
          reason
        }
      });else if (branchId) void submit({
        kind: 'owner-in',
        userId: target,
        body: {
          branch_id: branchId,
          reason
        }
      });
    }} />}
    {dialog === 'policy' && canManage && <PolicyDialog remoteError={error} month={month} latest={policies[0]} disabled={locked} onClose={() => setDialog(null)} onSave={action => void submit({
      kind: 'policy',
      userId: target,
      body: action
    })} />}
    {dialog && typeof dialog === 'object' && canManage && <CorrectionDialog remoteError={error} row={dialog} disabled={locked} onClose={() => setDialog(null)} onSave={body => void submit({
      kind: 'correction',
      userId: target,
      attendanceId: dialog.id,
      body
    })} />}
  </div>;
}
function Metric({
  title,
  value,
  icon
}: {
  title: string;
  value: string;
  icon: ReactNode;
}) {
  return <div className={GLASS}><p className="flex items-center gap-2 text-xs text-fg-muted">{icon}{title}</p><p className="mt-3 text-2xl font-semibold tabular-nums">{value}</p></div>;
}
function ClockDialog({
  remoteError,
  open,
  owner,
  disabled,
  onClose,
  onSave
}: {
  remoteError: string | null;
  open?: StaffAttendance;
  owner: boolean;
  disabled: boolean;
  onClose: () => void;
  onSave: (minutes: number, reason: string) => void;
}) {
  const [minutes, setMinutes] = useState('0');
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  function save(e: FormEvent) {
    e.preventDefault();
    const n = wholeMinutes(minutes);
    if (n === null || owner && !reason.trim()) {
      setError('Enter whole break minutes and an Owner reason.');
      return;
    }
    onSave(n, reason.trim());
  }
  return <Modal open contentClassName="staff-time-form" title={open ? 'Confirm actual time & break' : 'Record staff arrival'} onClose={() => {
    if (!disabled) onClose();
  }}><form onSubmit={save} className="space-y-4">
    {open && <Label title="Actual break taken (minutes)"><input className="input" type="number" min="0" max="1440" step="1" required value={minutes} disabled={disabled} onChange={e => setMinutes(e.target.value)} /></Label>}
    <p className="text-sm text-fg-muted">Record what actually happened. A planned break is not automatically deducted.</p>
    {owner && <Label title="Reason for recording another person's time"><textarea className="input" required maxLength={500} value={reason} disabled={disabled} onChange={e => setReason(e.target.value)} /></Label>}
    {error && <ErrorText>{error}</ErrorText>}{remoteError && <ErrorText>{remoteError}</ErrorText>}<button className="btn btn-primary w-full" disabled={disabled}>{open ? 'Confirm clock-out' : 'Confirm clock-in'}</button>
  </form></Modal>;
}
function PolicyDialog({
  remoteError,
  month,
  latest,
  disabled,
  onClose,
  onSave
}: {
  remoteError: string | null;
  month: string;
  latest?: StaffWorkPolicy;
  disabled: boolean;
  onClose: () => void;
  onSave: (body: Extract<StaffTimeAction, {
    kind: 'policy';
  }>['body']) => void;
}) {
  const [salary, setSalary] = useState(latest ? minorToRupeesInput(latest.monthly_salary_minor) : '');
  const [presence, setPresence] = useState(String(latest?.daily_presence_minutes ?? 720));
  const [breaks, setBreaks] = useState(String(latest?.daily_break_minutes ?? 60));
  const [mask, setMask] = useState(latest?.working_weekday_mask ?? 127);
  const [effective, setEffective] = useState(`${month}-01`);
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  function save(e: FormEvent) {
    e.preventDefault();
    const amount = parseRupeesToMinor(salary);
    const p = wholeMinutes(presence);
    const b = wholeMinutes(breaks);
    if (amount === null || amount > 1_000_000_000_000 || p === null || p === 0 || b === null || b >= p || !mask || !reason.trim() || !/^\d{4}-\d{2}-\d{2}$/.test(effective)) {
      setError('Review the amount, planned hours, working days, effective date and required reason.');
      return;
    }
    onSave({
      expected_revision: latest?.revision ?? 0,
      effective_from: effective,
      monthly_salary_minor: amount,
      daily_presence_minutes: p,
      daily_break_minutes: b,
      working_weekday_mask: mask,
      overtime_multiplier_basis_points: null,
      reason: reason.trim()
    });
  }
  return <Modal open contentClassName="staff-time-form" title="New draft salary revision" onClose={() => {
    if (!disabled) onClose();
  }}><form onSubmit={save} className="space-y-4"><p className="text-sm text-fg-muted">A proposal only. Saving does not approve overtime, deductions or payments. Every revision remains in history.</p>
    <Label title="Monthly salary proposal"><input className="input" inputMode="decimal" required value={salary} disabled={disabled} onChange={e => setSalary(e.target.value)} /></Label>
    <div className="grid gap-3 sm:grid-cols-2"><Label title="Planned presence (minutes / day)"><input className="input" type="number" min="1" max="1440" required value={presence} disabled={disabled} onChange={e => setPresence(e.target.value)} /></Label><Label title="Planned break (minutes / day)"><input className="input" type="number" min="0" max="1439" required value={breaks} disabled={disabled} onChange={e => setBreaks(e.target.value)} /></Label></div>
    <fieldset disabled={disabled}><legend className="mb-2 text-sm text-fg-muted">Planned working days</legend><div className="flex flex-wrap gap-2">{DAYS.map((day, i) => <label key={day} className="chip gap-2"><input type="checkbox" checked={Boolean(mask & 1 << i)} onChange={() => setMask(m => m ^ 1 << i)} />{day}</label>)}</div></fieldset>
    <Label title="Effective from"><input className="input" type="date" min="2000-01-01" max="2100-12-31" required value={effective} disabled={disabled} onChange={e => setEffective(e.target.value)} /></Label>
    <Label title="Reason for this revision"><textarea className="input" required maxLength={500} value={reason} disabled={disabled} onChange={e => setReason(e.target.value)} /></Label>
    {error && <ErrorText>{error}</ErrorText>}{remoteError && <ErrorText>{remoteError}</ErrorText>}<button className="btn btn-primary w-full" disabled={disabled}>Save draft revision</button>
  </form></Modal>;
}
function CorrectionDialog({
  remoteError,
  row,
  disabled,
  onClose,
  onSave
}: {
  remoteError: string | null;
  row: StaffAttendance;
  disabled: boolean;
  onClose: () => void;
  onSave: (body: Extract<StaffTimeAction, {
    kind: 'correction';
  }>['body']) => void;
}) {
  const [entered, setEntered] = useState(() => utcInput(row.clock_in_at));
  const [exited, setExited] = useState(() => row.clock_out_at ? utcInput(row.clock_out_at) : '');
  const [open, setOpen] = useState(row.clock_out_at === null);
  const [breaks, setBreaks] = useState(row.clock_out_at && row.break_confirmed !== true ? '' : String(row.break_minutes));
  const [reason, setReason] = useState('');
  const [error, setError] = useState<string | null>(null);
  function save(e: FormEvent) {
    e.preventDefault();
    try {
      const start = readUtcInput(entered, row.clock_in_at);
      const end = open ? null : readUtcInput(exited, row.clock_out_at ?? undefined);
      const n = wholeMinutes(breaks);
      if (n === null || open && n !== 0 || !reason.trim() || Date.parse(start) > Date.now() || end && (Date.parse(end) < Date.parse(start) || Date.parse(end) > Date.now() || n * 60000 > Date.parse(end) - Date.parse(start))) throw new Error('Review the times, actual break and required correction reason.');
      onSave({
        expected_updated_at: row.updated_at,
        clock_in_at: start,
        clock_out_at: end,
        break_minutes: n,
        reason: reason.trim()
      });
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Review the correction.');
    }
  }
  return <Modal open contentClassName="staff-time-form" title="Correct attendance with a reason" onClose={() => {
    if (!disabled) onClose();
  }}><form onSubmit={save} className="space-y-4"><p className="text-sm text-fg-muted">These inputs are UTC, not your browser's local timezone. The entry version is checked before saving.</p>
    <Label title="Clock in (UTC)"><input className="input" type="datetime-local" step="1" required value={entered} disabled={disabled} onChange={e => setEntered(e.target.value)} /></Label>
    <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={open} disabled={disabled} onChange={e => setOpen(e.target.checked)} />Entry is still open</label>
    {!open && <Label title="Clock out (UTC)"><input className="input" type="datetime-local" step="1" required value={exited} disabled={disabled} onChange={e => setExited(e.target.value)} /></Label>}
    <Label title="Actual break (minutes)"><input className="input" type="number" min="0" max="1440" required value={breaks} disabled={disabled} onChange={e => setBreaks(e.target.value)} /></Label>
    <Label title="Reason for correction"><textarea className="input" required maxLength={500} value={reason} disabled={disabled} onChange={e => setReason(e.target.value)} /></Label>
    {error && <ErrorText>{error}</ErrorText>}{remoteError && <ErrorText>{remoteError}</ErrorText>}<button className="btn btn-primary w-full" disabled={disabled}>Save correction</button>
  </form></Modal>;
}
