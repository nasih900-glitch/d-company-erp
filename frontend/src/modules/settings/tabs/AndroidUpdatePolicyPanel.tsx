import { useCallback, useEffect, useRef, useState } from 'react';
import { androidUpdatePolicy, type AndroidUpdatePolicyDTO, type AndroidUpdatePolicyWriteDTO } from '@/lib/erp-api';
import { useAuth } from '@/modules/auth/AuthContext';

export function policyWriteBody(policy: AndroidUpdatePolicyDTO, mode: 'optional' | 'required', reason: string): AndroidUpdatePolicyWriteDTO | null {
  if (reason.trim().length < 3 || reason.trim().length > 500) return null;
  const common = { mode, expected_revision: policy.revision, reason: reason.trim() };
  if (mode === 'optional') return common;
  const release = policy.active_release;
  if (!release || release.status !== 'active' || !/^[a-f0-9]{64}$/.test(release.policy_fingerprint ?? '')) return null;
  return { ...common, release_id: release.id, expected_release_fingerprint: release.policy_fingerprint };
}

export default function AndroidUpdatePolicyPanel() {
  const { me } = useAuth();
  const authority = me?.release_control_access === true
    ? `${me.company_id}|${me.user_id}|${me.branch_id ?? ''}` : null;
  const currentAuthority = useRef(authority);
  currentAuthority.current = authority;
  const generation = useRef(0);
  const invalidate = useCallback(() => { generation.current += 1; }, []);
  const [state, setState] = useState<{authority: string; policy: AndroidUpdatePolicyDTO} | null>(null);
  const [mode, setMode] = useState<'optional' | 'required'>('optional');
  const [reason, setReason] = useState('');
  const [reviewed, setReviewed] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  useEffect(() => {
    const operation = ++generation.current;
    setState(null); setReason(''); setReviewed(false); setBusy(false); setError(null); setNotice(null);
    if (authority) {
      void androidUpdatePolicy.get().then(policy => {
        if (generation.current !== operation || currentAuthority.current !== authority) return;
        setState({authority, policy}); setMode(policy.mode === 'required' ? 'required' : 'optional');
      }).catch((failure: unknown) => {
        if (generation.current === operation && currentAuthority.current === authority) setError(message(failure));
      });
    }
    return invalidate;
  }, [authority, invalidate]);
  if (!authority) return null;
  const policy = state?.authority === authority ? state.policy : null;
  const body = policy ? policyWriteBody(policy, mode, reason) : null;
  async function apply() {
    if (!body || !reviewed || busy || !authority || state?.authority !== authority) return;
    const operation = ++generation.current;
    setBusy(true); setError(null); setNotice(null);
    try {
      const updated = await androidUpdatePolicy.set(body);
      if (generation.current !== operation || currentAuthority.current !== authority) return;
      setState({authority, policy: updated}); setReason(''); setReviewed(false);
      setNotice(`Update policy saved at revision ${updated.revision}. Saved tablet work stays on each device.`);
    } catch (failure) {
      if (generation.current === operation && currentAuthority.current === authority) {
        setState(null); setReviewed(false);
        setError(`${message(failure)} Refresh policy before retrying if another owner changed it.`);
      }
    } finally {
      if (generation.current === operation && currentAuthority.current === authority) setBusy(false);
    }
  }
  async function refresh() {
    const operation = ++generation.current;
    setBusy(true); setError(null); setReviewed(false);
    try {
      const updated = await androidUpdatePolicy.get();
      if (generation.current !== operation || currentAuthority.current !== authority || !authority) return;
      setState({authority, policy: updated}); setMode(updated.mode === 'required' ? 'required' : 'optional');
    } catch (failure) {
      if (generation.current === operation) { setState(null); setReviewed(false); setError(message(failure)); }
    } finally { if (generation.current === operation) setBusy(false); }
  }
  return <section className="card space-y-3" aria-label="Android update policy">
    <h3 className="font-bold">Optional / Required updates</h3>
    <p className="text-sm text-fg-muted">This policy affects all direct Android installations, including your own app. Android still requires normal installation approval. Saved sales and Sync work are never deleted.</p>
    {policy ? <>
      <p>Current: <strong>{policy.mode === 'inherit' ? 'Deployment policy (unchanged)' : policy.mode === 'required' ? 'Required' : 'Optional'}</strong> · revision {policy.revision} · minimum supported build {policy.minimum_supported_version_code}</p>
      <p className="text-sm text-fg-muted">Optional keeps the deployment safety floor at build {policy.environment_minimum_version_code}; older unsafe builds remain blocked. Build 55 checks about once a second while online and in the foreground. Offline and older apps can react only after their next server check. Explicit Optional hides the optional banner on builds below 55 so their previous Required block can clear.</p>
      <div className="flex flex-wrap gap-3">
        <label><input type="radio" name="update-mode" checked={mode === 'optional'} onChange={() => {setMode('optional'); setReviewed(false);}} disabled={busy}/> Optional (supported builds)</label>
        <label><input type="radio" name="update-mode" checked={mode === 'required'} onChange={() => {setMode('required'); setReviewed(false);}} disabled={busy}/> Required active release</label>
      </div>
      {mode === 'required' ? <p>{policy.active_release ? `Target: ${policy.active_release.version_name} · build ${policy.active_release.version_code} · manifest ${policy.active_release.manifest_sha256}` : 'No active release. Offer a verified release before making it required.'}</p> : null}
      <label className="block text-sm">Reason<textarea className="input mt-1 w-full" maxLength={500} value={reason} disabled={busy} onChange={event => {setReason(event.target.value); setReviewed(false);}} /></label>
      <label className="block text-sm"><input type="checkbox" checked={reviewed} onChange={event => setReviewed(event.target.checked)} disabled={busy}/> I reviewed the global effect and selected release.</label>
      <button className="btn btn-primary" type="button" disabled={!body || !reviewed || busy} onClick={() => void apply()}>{busy ? 'Checking and saving…' : 'Apply update policy'}</button>
    </> : <p>Policy has not been loaded. No update policy is assumed.</p>}
    {error ? <p role="alert" className="text-accent-bad">{error}</p> : null}
    {notice ? <p role="status">{notice}</p> : null}
    <button className="btn btn-ghost" type="button" disabled={busy} onClick={() => void refresh()}>Refresh policy</button>
  </section>;
}
function message(error: unknown): string {
  const known = error as {response?: {data?: {error?: {message?: string}}}; message?: string};
  return known?.response?.data?.error?.message || known?.message || 'Update policy could not be confirmed.';
}
