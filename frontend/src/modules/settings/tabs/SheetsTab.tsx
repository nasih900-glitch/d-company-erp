/**
 * Settings → Google Sheets integration setup wizard.
 *
 * Moved from the old single-page SettingsScreen so settings is now tabbed.
 */
import { useEffect, useState } from 'react';
import {
  Sheet, Copy, Check, ExternalLink, AlertTriangle,
  CircleCheck, Loader2, X, FileText,
} from 'lucide-react';

import {
  getSettings, setWebhookUrl, testConnection,
} from '@/lib/google-sheets';
import APPS_SCRIPT from '../apps-script.txt?raw';

export default function SheetsTab() {
  const [settings, setSettings] = useState(getSettings());
  const [url, setUrl] = useState(settings.url ?? '');
  const [testing, setTesting] = useState(false);
  const [testResult, setTestResult] = useState<{ ok: boolean; message: string } | null>(null);
  const [copied, setCopied] = useState(false);

  useEffect(() => { setUrl(settings.url ?? ''); }, [settings.url]);

  async function save() {
    setTesting(true); setTestResult(null);
    const trimmed = url.trim();
    const result = await testConnection(trimmed);
    setTestResult(result);
    if (result.ok) {
      setWebhookUrl(trimmed);
      setSettings(getSettings());
    }
    setTesting(false);
  }

  function disconnect() {
    setWebhookUrl(null);
    setSettings(getSettings());
    setUrl('');
    setTestResult(null);
  }

  async function copyScript() {
    await navigator.clipboard.writeText(APPS_SCRIPT);
    setCopied(true);
    setTimeout(() => setCopied(false), 2000);
  }

  return (
    <div>
      <div className="card mb-6">
        <div className="flex items-start gap-4 mb-5">
          <div className="p-3 rounded-xl bg-[#0F9D58]/15 text-[#0F9D58]"><Sheet size={24}/></div>
          <div className="flex-1">
            <h3 className="text-lg font-bold">Google Sheets</h3>
            <p className="text-sm text-fg-muted">
              Every order, ticket, event, and P&amp;L report automatically appears as
              a row in your sheet's <b>Operations</b> tab. Your other tabs stay untouched.
            </p>
          </div>
          <Status connected={!!settings.url} lastSync={settings.last_sync_at}/>
        </div>

        {!settings.url && (
          <Wizard apps_script={APPS_SCRIPT} copied={copied} onCopy={copyScript}/>
        )}

        <div className="mt-4 pt-4 border-t border-bg-border">
          <label className="block">
            <span className="text-xs text-fg-muted">Web app URL</span>
            <input value={url} onChange={(e) => setUrl(e.target.value)}
              placeholder="https://script.google.com/macros/s/AKfycb…/exec"
              className="input mt-1 font-mono text-xs"/>
          </label>
          {testResult && (
            <div className={`mt-3 p-3 rounded-lg text-sm flex items-start gap-2 ${
              testResult.ok ? 'bg-accent-good/15 text-accent-good border border-accent-good/40'
                            : 'bg-accent-bad/15 text-accent-bad border border-accent-bad/40'
            }`}>
              {testResult.ok ? <CircleCheck size={16}/> : <AlertTriangle size={16}/>}
              <span>{testResult.message}</span>
            </div>
          )}
          {settings.last_error && !testResult && (
            <div className="mt-3 p-3 rounded-lg text-sm flex items-start gap-2 bg-accent-bad/15 text-accent-bad border border-accent-bad/40">
              <AlertTriangle size={16}/>
              <div>
                <div className="font-medium">Last sync failed: {settings.last_error}</div>
                <div className="text-xs text-accent-bad/80 mt-1">
                  Test the connection again. If it keeps failing, redeploy your Apps Script.
                </div>
              </div>
            </div>
          )}
          <div className="flex gap-2 mt-4">
            <button onClick={save} disabled={testing || !url.trim()}
              className="btn btn-primary disabled:opacity-40 disabled:cursor-not-allowed">
              {testing ? <Loader2 size={14} className="animate-spin"/> : <CircleCheck size={14}/>}
              {settings.url ? 'Re-test & save' : 'Test connection & save'}
            </button>
            {settings.url && (
              <button onClick={disconnect} className="btn btn-ghost">
                <X size={14}/> Disconnect
              </button>
            )}
          </div>
        </div>
      </div>

      <div className="card">
        <h3 className="font-semibold mb-3 flex items-center gap-2">
          <FileText size={16}/> What appears in your sheet
        </h3>
        <p className="text-xs text-fg-muted leading-relaxed">
          All entries land in a single tab called <b>Operations</b> with 17 columns:
          Date · Time · Type · ID · Description · Customer · Qty · Taxable · CGST · SGST · IGST ·
          Round-off · Total · Method · Cashier · GSTIN · Place of supply.
          <br/><br/>
          The <b>Type</b> column tells you what each row is: <b>Order</b>, <b>Ticket</b>, <b>Event</b>,
          <b> Daily Report</b>, <b>Monthly Report</b>, <b>Quarterly Report</b>, <b>Yearly Report</b>.
          Rows are idempotent on the ID column — if the same invoice arrives twice, the existing row is overwritten in place.
        </p>
      </div>
    </div>
  );
}

function Status({ connected, lastSync }: { connected: boolean; lastSync: string | null }) {
  if (!connected) return <div className="chip">Not connected</div>;
  return (
    <div className="text-right">
      <div className="chip border-accent-good/40 text-accent-good">
        <span className="w-1.5 h-1.5 rounded-full bg-accent-good inline-block mr-1.5 animate-pulse"/>
        Connected
      </div>
      {lastSync && (
        <div className="text-[10px] text-fg-muted mt-1">
          Last sync {new Date(lastSync).toLocaleString('en-IN', { hour: '2-digit', minute: '2-digit', day: '2-digit', month: 'short' })}
        </div>
      )}
    </div>
  );
}

function Wizard({ apps_script, copied, onCopy }: {
  apps_script: string; copied: boolean; onCopy: () => void;
}) {
  return (
    <div className="bg-bg-raised rounded-xl p-4 space-y-4">
      <Step n={1} title="Open your Google Sheet">
        Use the existing sheet you already have, or create a new blank one at{' '}
        <a href="https://sheets.new" target="_blank" rel="noreferrer" className="text-accent inline-flex items-center gap-1">
          sheets.new <ExternalLink size={12}/>
        </a>. A new tab called <b>Operations</b> will be added automatically; your other tabs stay untouched.
      </Step>
      <Step n={2} title="Open the script editor">
        In the menu bar: <b>Extensions → Apps Script</b>. A new tab opens with an empty <code>Code.gs</code>.
      </Step>
      <Step n={3} title="Paste this script and save">
        <button onClick={onCopy} className="btn btn-ghost mt-2 !min-h-[36px] !py-2">
          {copied ? <Check size={14} className="text-accent-good"/> : <Copy size={14}/>}
          {copied ? 'Copied!' : 'Copy the Apps Script'}
        </button>
        <details className="mt-2">
          <summary className="text-xs text-fg-muted cursor-pointer hover:text-fg">View script ({apps_script.split('\n').length} lines)</summary>
          <pre className="text-[10px] bg-bg p-2 rounded mt-1 max-h-48 overflow-auto font-mono">{apps_script.slice(0, 600)}…</pre>
        </details>
        <div className="text-xs text-fg-muted mt-2">Click the disk icon (Save).</div>
      </Step>
      <Step n={4} title="Deploy as a Web app">
        <b>Deploy → New deployment</b>. Type: <b>Web app</b>. Execute as: <b>Me</b>. Who has access: <b>Anyone with the link</b>. Click <b>Deploy</b>; authorize when prompted.
      </Step>
      <Step n={5} title="Copy the Web app URL">
        Paste the URL into the box below and click <b>Test connection & save</b>.
      </Step>
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: React.ReactNode }) {
  return (
    <div className="flex gap-3">
      <div className="w-7 h-7 rounded-full bg-accent text-bg grid place-items-center text-xs font-bold flex-shrink-0">{n}</div>
      <div>
        <div className="font-semibold text-sm">{title}</div>
        <div className="text-xs text-fg-muted mt-0.5">{children}</div>
      </div>
    </div>
  );
}
