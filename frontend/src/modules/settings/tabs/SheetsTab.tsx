import { useCallback, useEffect, useState } from 'react';
import {
  AlertTriangle, Check, CircleCheck, Copy, ExternalLink, FileText,
  Loader2, RefreshCw, Sheet, Unplug,
} from 'lucide-react';

import { LIVE_MODE } from '@/lib/demo';
import { settings, type GoogleSheetsMirrorStatusDTO } from '@/lib/erp-api';
import APPS_SCRIPT from '../apps-script.txt?raw';

const SECRET_PROPERTY = 'D_COMPANY_ERP_MIRROR_HMAC_SECRET';
type SheetsMessageTone = 'success' | 'warning' | 'error';

export function googleSheetsTestMessageTone(status: string): SheetsMessageTone {
  if (status === 'pending') return 'warning';
  if (status === 'disabled') return 'error';
  return 'success';
}

export default function SheetsTab() {
  const [status, setStatus] = useState<GoogleSheetsMirrorStatusDTO | null>(null);
  const [url, setUrl] = useState('');
  const [secret, setSecret] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [message, setMessage] = useState<{
    tone: SheetsMessageTone;
    text: string;
  } | null>(null);
  const [copied, setCopied] = useState<string | null>(null);

  const load = useCallback(async () => {
    if (!LIVE_MODE) return;
    try {
      const next = await settings.getGoogleSheetsMirror();
      setStatus(next);
      setUrl(next.webhook_url ?? '');
      setMessage(null);
    } catch (error) {
      setMessage({ tone: 'error', text: (error as Error).message });
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function copy(value: string, label: string) {
    await navigator.clipboard.writeText(value);
    setCopied(label);
    window.setTimeout(() => setCopied((current) => current === label ? null : current), 2000);
  }

  async function configure(rotateSecret = false) {
    if (!url.trim()) return;
    if (rotateSecret && !window.confirm(
      'Rotate the Google Sheets secret? Queued entries are preserved, and delivery will pause until you replace the Apps Script property and deliver a new test row.',
    )) return;
    setBusy(rotateSecret ? 'rotate' : 'configure'); setMessage(null);
    try {
      const result = await settings.configureGoogleSheetsMirror({
        webhook_url: url.trim(), rotate_secret: rotateSecret,
      });
      setStatus(result); setUrl(result.webhook_url ?? ''); setSecret(result.signing_secret);
      setMessage({
        tone: 'success',
        text: result.signing_secret
          ? 'Configuration saved. Copy the one-time secret into Apps Script, then send a test.'
          : 'Configuration saved. Send a test to verify delivery.',
      });
    } catch (error) {
      setMessage({ tone: 'error', text: (error as Error).message });
    } finally { setBusy(null); }
  }

  async function sendTest() {
    setBusy('test'); setMessage(null);
    try {
      const result = await settings.testGoogleSheetsMirror();
      setMessage({ tone: googleSheetsTestMessageTone(result.status), text: result.message });
      window.setTimeout(() => { void load(); }, 6000);
    } catch (error) {
      setMessage({ tone: 'error', text: (error as Error).message });
    } finally { setBusy(null); }
  }

  async function retryFailures() {
    setBusy('retry'); setMessage(null);
    try {
      const result = await settings.retryGoogleSheetsMirror();
      setStatus(result);
      setMessage({ tone: 'success', text: 'Failed mirror entries were queued again.' });
    } catch (error) {
      setMessage({ tone: 'error', text: (error as Error).message });
    } finally { setBusy(null); }
  }

  async function disconnect() {
    if (!window.confirm('Disconnect Google Sheets? Saved ERP records are not changed.')) return;
    setBusy('disconnect'); setMessage(null);
    try {
      const result = await settings.disconnectGoogleSheetsMirror();
      setStatus(result); setUrl(''); setSecret(null);
      setMessage({ tone: 'success', text: 'Google Sheets mirror disconnected.' });
    } catch (error) {
      setMessage({ tone: 'error', text: (error as Error).message });
    } finally { setBusy(null); }
  }

  if (!LIVE_MODE) {
    return <div className="card text-fg-muted">Google Sheets setup is available in live ERP mode.</div>;
  }

  return (
    <div className="space-y-4">
      <div className="card">
        <div className="flex flex-wrap items-start gap-4">
          <div className="rounded-xl bg-[#0F9D58]/15 p-3 text-[#0F9D58]"><Sheet size={24}/></div>
          <div className="min-w-0 flex-1">
            <h3 className="text-lg font-bold">Google Sheets transaction mirror</h3>
            <p className="text-sm text-fg-muted">
              The ERP server adds an authenticated, append-only copy of financial activity to
              <b> ERP Mirror v1</b>. Your existing workbook tabs remain unchanged.
            </p>
            <p className="mt-1 text-xs text-fg-muted">
              This is not a database backup or receipt-file backup. Transactions recorded after
              configuration are queued safely while verification is pending, then sent after a
              test row is delivered. History from before configuration is not backfilled.
            </p>
          </div>
          <StatusBadge status={status}/>
        </div>
        <div className="mt-5 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          <Metric label="Delivered (all time)" value={status?.delivered_count ?? 0}/>
          <Metric label="Waiting to send" value={status?.pending_count ?? 0}/>
          <Metric label="Held for verification" value={status?.held_count ?? 0}/>
          <Metric label="Needs attention" value={status?.quarantined_count ?? 0} warning/>
        </div>
        {Boolean(status?.held_count) && !status?.connection_verified && (
          <div className="mt-3 flex items-start gap-2 rounded-lg border border-accent-gold/40 bg-accent-gold/10 p-3 text-sm text-accent-gold">
            <AlertTriangle size={16} className="mt-0.5 shrink-0"/>
            <span>
              {status?.held_count} business {status?.held_count === 1 ? 'entry is' : 'entries are'} safely queued.
              Send and deliver a connection test to release {status?.held_count === 1 ? 'it' : 'them'}.
            </span>
          </div>
        )}
        {status?.last_delivered_at && (
          <p className="mt-3 text-xs text-fg-muted">
            Last delivered {new Date(status.last_delivered_at).toLocaleString('en-IN')}
          </p>
        )}
        {status?.last_error && (
          <div className="mt-3 flex items-start gap-2 rounded-lg border border-accent-bad/40 bg-accent-bad/10 p-3 text-sm text-accent-bad">
            <AlertTriangle size={16} className="mt-0.5 shrink-0"/><span>{status.last_error}</span>
          </div>
        )}
      </div>

      <div className="card space-y-4">
        <h3 className="font-semibold">Connect your existing workbook</h3>
        <SetupStep n={1} title="Install the secure mirror script">
          In your Google Sheet, open <b>Extensions → Apps Script</b>, replace Code.gs with this
          script, and save it.
          <div className="mt-2 flex flex-wrap gap-2">
            <button className="btn btn-ghost" onClick={() => void copy(APPS_SCRIPT, 'script')}>
              {copied === 'script' ? <Check size={14}/> : <Copy size={14}/>}
              {copied === 'script' ? 'Script copied' : 'Copy Apps Script'}
            </button>
            <a className="btn btn-ghost" href="https://script.google.com" target="_blank" rel="noreferrer">
              Open Apps Script <ExternalLink size={13}/>
            </a>
          </div>
        </SetupStep>
        <SetupStep n={2} title="Deploy the script as a web app">
          Choose <b>Deploy → New deployment → Web app</b>, execute as yourself, and allow access
          to anyone with the link. Copy the <b>/exec</b> web-app URL.
        </SetupStep>
        <SetupStep n={3} title="Connect the ERP">
          <label className="mt-2 block">
            <span className="text-xs text-fg-muted">Apps Script web-app URL</span>
            <input className="input mt-1 font-mono text-xs" value={url}
              onChange={(event) => setUrl(event.target.value)}
              placeholder="https://script.google.com/macros/s/…/exec"/>
          </label>
          <button className="btn btn-primary mt-2" disabled={!url.trim() || busy !== null}
            onClick={() => void configure(false)}>
            {busy === 'configure' ? <Loader2 size={14} className="animate-spin"/> : <CircleCheck size={14}/>}
            {status?.enabled ? 'Save connection' : 'Connect'}
          </button>
        </SetupStep>
        <SetupStep n={4} title="Add the one-time security secret">
          In Apps Script, open <b>Project Settings → Script Properties</b>. Add property
          <code className="mx-1 rounded bg-bg px-1 py-0.5">{SECRET_PROPERTY}</code> and paste the
          secret shown after connecting.
          {secret ? (
            <div className="mt-2 rounded-xl border border-accent-gold/40 bg-accent-gold/5 p-3">
              <p className="text-xs font-semibold text-accent-gold">Copy this now. It is shown only once.</p>
              <code className="mt-2 block break-all rounded bg-bg p-2 text-xs">{secret}</code>
              <div className="mt-2 flex flex-wrap gap-2">
                <button className="btn btn-ghost" onClick={() => void copy(secret, 'secret')}>
                  {copied === 'secret' ? <Check size={14}/> : <Copy size={14}/>}
                  {copied === 'secret' ? 'Secret copied' : 'Copy secret'}
                </button>
                <button className="btn btn-ghost" onClick={() => setSecret(null)}>Hide secret</button>
              </div>
            </div>
          ) : status?.secret_configured ? (
            <p className="mt-2 text-xs text-accent-good">A secret is configured. Rotate it only if the Apps Script copy was lost.</p>
          ) : (
            <p className="mt-2 text-xs text-fg-muted">Connect first to generate the secret.</p>
          )}
        </SetupStep>
        <SetupStep n={5} title="Send a test row">
          Transactions recorded after configuration are kept in the ERP outbox now. They are sent
          to the Sheet only after this test row is delivered and the status above shows Connected.
          <div className="mt-2 flex flex-wrap gap-2">
            <button className="btn btn-primary" disabled={!status?.enabled || busy !== null}
              onClick={() => void sendTest()}>
              {busy === 'test' ? <Loader2 size={14} className="animate-spin"/> : <FileText size={14}/>}
              Send test
            </button>
            <button className="btn btn-ghost" disabled={busy !== null} onClick={() => void load()}>
              <RefreshCw size={14}/> Refresh status
            </button>
          </div>
        </SetupStep>
        {message && (
          <div className={`flex items-start gap-2 rounded-lg border p-3 text-sm ${
            message.tone === 'success'
              ? 'border-accent-good/40 bg-accent-good/10 text-accent-good'
              : message.tone === 'warning'
                ? 'border-accent-gold/40 bg-accent-gold/10 text-accent-gold'
                : 'border-accent-bad/40 bg-accent-bad/10 text-accent-bad'
          }`}>
            {message.tone === 'success'
              ? <CircleCheck size={16}/>
              : <AlertTriangle size={16}/>}<span>{message.text}</span>
          </div>
        )}
      </div>

      <div className="card">
        <h3 className="font-semibold">Recovery controls</h3>
        <p className="mt-1 text-xs text-fg-muted">
          Temporary network failures retry automatically. Use retry after fixing the Apps Script
          URL or secret. A verified connection cannot change or disconnect while business entries
          are still waiting or need attention; drain or retry them first.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          {Boolean(status?.quarantined_count) && (
            <button className="btn btn-ghost" disabled={busy !== null} onClick={() => void retryFailures()}>
              {busy === 'retry' ? <Loader2 size={14} className="animate-spin"/> : <RefreshCw size={14}/>}
              Retry failed entries
            </button>
          )}
          {status?.enabled && (
            <button className="btn btn-ghost" disabled={busy !== null} onClick={() => void configure(true)}>
              {busy === 'rotate' ? <Loader2 size={14} className="animate-spin"/> : <RefreshCw size={14}/>}
              Rotate secret
            </button>
          )}
          {status?.enabled && (
            <button className="btn btn-ghost text-accent-bad" disabled={busy !== null}
              onClick={() => void disconnect()}>
              {busy === 'disconnect' ? <Loader2 size={14} className="animate-spin"/> : <Unplug size={14}/>}
              Disconnect
            </button>
          )}
        </div>
      </div>
      <div className="rounded-xl border border-bg-border bg-bg-raised/40 p-3 text-xs text-fg-muted">
        Google Sheets is a readable secondary transaction mirror. Receipt photos and PDFs remain
        private in ERP and are not copied into the sheet. The ERP database and encrypted server
        backup remain the recovery source because a spreadsheet cannot preserve every permission,
        sync, audit, and relational record safely.
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: GoogleSheetsMirrorStatusDTO | null }) {
  if (!status) return <div className="chip">Loading…</div>;
  if (!status.enabled) return <div className="chip">Not connected</div>;
  return status.connection_verified
    ? <div className="chip border-accent-good/40 text-accent-good">Connected</div>
    : <div className="chip border-accent-gold/40 text-accent-gold">Configured · verification pending</div>;
}

function Metric({ label, value, warning = false }: { label: string; value: number; warning?: boolean }) {
  return (
    <div className="rounded-xl border border-bg-border bg-bg-raised/50 p-3">
      <div className={`text-xl font-bold ${warning && value ? 'text-accent-bad' : ''}`}>{value}</div>
      <div className="text-xs text-fg-muted">{label}</div>
    </div>
  );
}

function SetupStep({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3 rounded-xl border border-bg-border p-3">
      <div className="grid h-7 w-7 shrink-0 place-items-center rounded-full bg-accent text-xs font-bold text-bg">{n}</div>
      <div className="min-w-0 flex-1">
        <div className="font-semibold text-sm">{title}</div>
        <div className="mt-1 text-xs leading-relaxed text-fg-muted">{children}</div>
      </div>
    </div>
  );
}
