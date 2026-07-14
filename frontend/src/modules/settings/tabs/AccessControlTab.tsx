/**
 * Access Control — protected-owner-only. Toggle which roles can reach which
 * feature modules, live, without a code deploy. A blank cell means "use the
 * role's built-in default"; an explicit toggle overrides that default for
 * this company until cleared.
 */
import { useEffect, useState } from 'react';
import { AlertCircle, Check, Loader2, RotateCcw, X } from 'lucide-react';

import { accessControl, type AccessControlDTO } from '@/lib/erp-api';
import { roleLabel } from '@/lib/roles';

const MODULE_LABEL: Record<string, string> = {
  pos: 'POS',
  tables: 'Tables',
  menu: 'Menu',
  inventory: 'Inventory',
  gaming: 'Gaming',
  finance: 'Finance',
  ocr: 'OCR',
  staff: 'Staff',
  insights_reports: 'Insights / Reports',
};

export default function AccessControlTab() {
  const [data, setData] = useState<AccessControlDTO | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [busyKey, setBusyKey] = useState<string | null>(null);

  useEffect(() => {
    accessControl.get()
      .then(setData)
      .catch((e) => setError((e as Error).message))
      .finally(() => setLoading(false));
  }, []);

  function cellKey(role: string, module: string) {
    return `${role}:${module}`;
  }

  async function setCell(role: string, module: string, allowed: boolean | null) {
    const key = cellKey(role, module);
    setBusyKey(key); setError(null);
    try {
      const updated = await accessControl.update(role, module, allowed);
      setData((prev) => prev && {
        ...prev,
        cells: prev.cells.map((c) => (c.role_code === role && c.module === module ? updated : c)),
      });
    } catch (e) { setError((e as Error).message); }
    finally { setBusyKey(null); }
  }

  if (loading) {
    return <div className="card flex items-center gap-3 text-fg-muted"><Loader2 className="animate-spin" size={16}/> Loading…</div>;
  }
  if (!data) {
    return (
      <div className="card text-fg-muted">
        {error || 'Access control is only visible to the protected owner.'}
      </div>
    );
  }

  const roleCodes = Object.keys(data.roles);
  const cellByKey = new Map(data.cells.map((c) => [cellKey(c.role_code, c.module), c]));

  return (
    <div className="space-y-4 max-w-4xl">
      <div className="card">
        <h3 className="font-bold mb-1">Feature access by role</h3>
        <p className="text-xs text-fg-muted mb-3">
          Click a cell to allow or block that role's access to a feature. The reset icon clears
          an explicit override and goes back to the role's default. This only affects roles other
          than the protected owner, who always has full access.
        </p>
        {error && (
          <div className="mb-3 p-2.5 rounded-lg bg-accent-bad/10 border border-accent-bad/40 text-accent-bad text-sm flex items-center gap-2">
            <AlertCircle size={14}/> {error}
          </div>
        )}
        <div className="overflow-x-auto">
          <table className="w-full text-sm border-collapse">
            <thead>
              <tr>
                <th className="text-left p-2 border-b border-bg-border">Role</th>
                {data.modules.map((m) => (
                  <th key={m} className="text-center p-2 border-b border-bg-border whitespace-nowrap">
                    {MODULE_LABEL[m] ?? m}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {roleCodes.map((role) => (
                <tr key={role} className="border-b border-bg-border/60 last:border-0">
                  <td className="p-2 font-medium" title={data.roles[role]}>{roleLabel(role)}</td>
                  {data.modules.map((module) => {
                    const cell = cellByKey.get(cellKey(role, module));
                    if (!cell) return <td key={module}/>;
                    const key = cellKey(role, module);
                    return (
                      <td key={module} className="p-2 text-center">
                        <div className="inline-flex items-center gap-1">
                          <button
                            disabled={busyKey === key}
                            onClick={() => setCell(role, module, !cell.allowed)}
                            className={`w-7 h-7 rounded-lg flex items-center justify-center border transition
                              ${cell.allowed
                                ? 'bg-accent-good/15 border-accent-good/40 text-accent-good'
                                : 'bg-accent-bad/10 border-accent-bad/30 text-accent-bad'}`}
                            title={cell.allowed ? 'Allowed — click to block' : 'Blocked — click to allow'}
                          >
                            {busyKey === key
                              ? <Loader2 className="animate-spin" size={13}/>
                              : cell.allowed ? <Check size={13}/> : <X size={13}/>}
                          </button>
                          {cell.override !== null && (
                            <button
                              className="text-fg-muted hover:text-accent p-0.5"
                              title={`Explicit override — reset to default (${cell.default_allowed ? 'allowed' : 'blocked'})`}
                              onClick={() => setCell(role, module, null)}
                            >
                              <RotateCcw size={11}/>
                            </button>
                          )}
                        </div>
                      </td>
                    );
                  })}
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </div>
  );
}
