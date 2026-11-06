import { useEffect, useRef, useState } from 'react';
import { useParams } from 'react-router-dom';
import { api, readSessionGeneration } from '@/lib/api';
import {
  canRecoverOperator,
  isOperatorRecoveryBody,
  operatorRecoveryPath,
  operatorRecoveryRequest,
  recoveryTarget,
  validOperatorId,
  verifyOperatorRecoveryReceipt,
  type OperatorReceipt,
  type OperatorRecoveryBody,
  type OperatorRecoveryScope,
  type OperatorRecoveryState,
} from '@/lib/operator-recovery-api';
import { StableMutationIntent } from '@/lib/stable-mutation-intent';
import { useAuth } from '@/modules/auth/AuthContext';

export default function OperatorRecoveryScreen() {
  const { shiftId } = useParams();
  const { me, terminalId, terminalReady, terminalOptions } = useAuth();
  if (!me || !canRecoverOperator(me)) {
    return <section className="card" role="status">Only an Owner with till recovery access can open this page.</section>;
  }
  const branchId = terminalOptions.find((terminal) => terminal.id === terminalId)?.branch_id;
  if (!shiftId || !validOperatorId(shiftId) || !terminalReady || !terminalId || !branchId ||
      (me.branch_id !== null && me.branch_id !== branchId)) {
    return <section className="card" role="status">Select the tablet’s verified till and open its exact operator recovery link.</section>;
  }
  const scope = { actorId: me.user_id, companyId: me.company_id, branchId, terminalId, shiftId };
  const generation = readSessionGeneration();
  return <OperatorRecoveryPanel key={JSON.stringify([scope, generation])} scope={scope} generation={generation} />;
}

function OperatorRecoveryPanel({ scope: initialScope, generation }: { scope: OperatorRecoveryScope; generation: number }) {
  const [scope] = useState(initialScope);
  const [state, setState] = useState<OperatorRecoveryState | null>(null);
  const [reason, setReason] = useState('');
  const [notice, setNotice] = useState<string | null>(null);
  const [busy, setBusy] = useState(true);
  const [pending] = useState(() => new StableMutationIntent<OperatorRecoveryBody>({
    prefix: 'operator-recovery',
    storage: typeof sessionStorage === 'undefined' ? null : sessionStorage,
    storageKey: `operator-recovery:${scope.actorId}:${scope.companyId}:${scope.branchId}:${scope.terminalId}:${scope.shiftId}`,
    isPayload: isOperatorRecoveryBody,
  }));
  const [action, setAction] = useState(() => pending.peek());
  const active = useRef<AbortController | null>(null);
  const path = operatorRecoveryPath(scope);

  useEffect(() => {
    const controller = new AbortController();
    active.current = controller;
    void api.get<OperatorRecoveryState>(`${path}/operator-state`, operatorRecoveryRequest(scope, generation, controller.signal))
      .then(({ data }) => { if (!controller.signal.aborted) setState(data); })
      .catch(() => { if (!controller.signal.aborted) setNotice('The selected till could not be verified. Reopen the exact recovery link.'); })
      .finally(() => {
        if (active.current === controller) active.current = null;
        if (!controller.signal.aborted) setBusy(false);
      });
    return () => {
      active.current?.abort();
      active.current = null;
    };
  }, [generation, path, scope]);

  const target = state ? recoveryTarget(state, scope) : null;
  const trimmed = reason.trim();

  async function recover(readOnly: boolean) {
    if (busy || active.current !== null) return;
    const controller = new AbortController();
    active.current = controller;
    setBusy(true);
    setNotice(null);
    try {
      const currentAction = pending.peek() ?? (
        !readOnly && target && trimmed.length >= 12 && trimmed.length <= 500
          ? pending.resolve(`${target.resolvesId}:${target.revision}`, () => ({
              incoming_user_id: target.id,
              expected_revision: target.revision,
              approved: true,
              reason: trimmed,
              resolves_handover_id: target.resolvesId,
            }))
          : null
      );
      if (!currentAction) return;
      setAction(currentAction);
      if (!readOnly && (!target || target.id !== currentAction.payload.incoming_user_id ||
          target.revision !== currentAction.payload.expected_revision ||
          target.resolvesId !== currentAction.payload.resolves_handover_id)) {
        setNotice('The current handover changed. Check the saved recovery receipt.');
        return;
      }
      const response = readOnly
        ? await api.get<OperatorReceipt>(`${path}/operator-handover-receipt`, {
            ...operatorRecoveryRequest(scope, generation, controller.signal),
            params: { action_key: currentAction.idempotencyKey },
          })
        : await api.post<OperatorReceipt>(`${path}/operator-handovers/recover`, currentAction.payload,
            operatorRecoveryRequest(scope, generation, controller.signal, currentAction.idempotencyKey));
      verifyOperatorRecoveryReceipt(response.data, scope, currentAction.payload);
      const { data } = await api.get<OperatorRecoveryState>(`${path}/operator-state`,
        operatorRecoveryRequest(scope, generation, controller.signal));
      if (controller.signal.aborted || generation !== readSessionGeneration()) return;
      pending.confirmSuccess(currentAction);
      setAction(null);
      setState(data);
      setReason('');
      setNotice(data.shift_id === scope.shiftId && data.shift_status === 'open' &&
        data.latest_handover?.id === response.data.id && data.revision === response.data.to_revision &&
        data.operator_user_id === response.data.incoming_user_id
        ? 'Operator responsibility was restored. The cash shift remains open.'
        : 'This recovery was recorded, but the current till has changed. Review the latest state.');
    } catch {
      if (!controller.signal.aborted) setNotice('The outcome is unverified. Keep this action and check its receipt or retry it.');
    } finally {
      if (active.current === controller) active.current = null;
      if (!controller.signal.aborted) setBusy(false);
    }
  }

  return (
    <section className="card space-y-4 max-w-2xl">
      <div>
        <h1 className="text-xl font-semibold">Till operator recovery</h1>
        <p className="text-sm text-fg-muted">Return responsibility to the previous cashier. The shift, games, bills and attendance stay as recorded. No cash is counted.</p>
      </div>
      {state?.operator_name && <p>Current operator: {state.operator_name}</p>}
      {notice && <p role="status">{notice}</p>}
      {action ? (
        <div className="space-y-2">
          <p className="text-sm">A saved recovery still needs verification. Its reason and action key are held for the exact retry.</p>
          <button type="button" className="btn btn-primary" disabled={busy} onClick={() => void recover(true)}>Check recovery receipt</button>
          <button type="button" className="btn btn-ghost" disabled={busy} onClick={() => void recover(false)}>Retry saved recovery</button>
        </div>
      ) : target ? (
        <form className="space-y-3" onSubmit={(event) => { event.preventDefault(); void recover(false); }}>
          <label className="block">
            <span className="block">Reason for returning this till to {target.name}</span>
            <textarea className="input w-full" value={reason} maxLength={500} minLength={12} required
              disabled={busy} onChange={(event) => setReason(event.target.value)} />
          </label>
          <button type="submit" className="btn btn-primary" disabled={busy || trimmed.length < 12}>
            Confirm return to {target.name}
          </button>
        </form>
      ) : !busy && state && <p>No current handover is eligible for return on this till.</p>}
    </section>
  );
}
