/**
 * OCR — Receipts (live).
 *
 *  - Drag/drop or pick a bill image (JPG, PNG, HEIC, PDF)
 *  - Backend extracts vendor / date / amount via Vision API + Tesseract
 *  - Verification queue: approve / reject items needing review
 *  - Approved items become Expense rows (next iteration auto-links to category)
 */
import { useEffect, useRef, useState } from 'react';
import {
  ScanLine, FileText, Check, X, AlertTriangle, Loader2, RefreshCw,
  Upload,
} from 'lucide-react';

import { inr } from '@/lib/inr';
import {
  ocr, settings, type OcrExtractionDTO, type BranchDTO,
} from '@/lib/erp-api';

const STATUS_COLOR: Record<string, string> = {
  parsed:       'border-accent/40 text-accent',
  needs_review: 'border-accent-gold/40 text-accent-gold',
  approved:     'border-accent-good/40 text-accent-good',
  duplicate:    'border-fg-muted/40 text-fg-muted',
  rejected:     'border-accent-bad/40 text-accent-bad',
};
const STATUS_LABEL: Record<string, string> = {
  parsed: 'Parsed', needs_review: 'Needs review',
  approved: 'Approved', duplicate: 'Duplicate', rejected: 'Rejected',
};

export default function OcrScreen() {
  const [rows, setRows] = useState<OcrExtractionDTO[]>([]);
  const [branches, setBranches] = useState<BranchDTO[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [uploading, setUploading] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const inputRef = useRef<HTMLInputElement>(null);

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [q, b] = await Promise.all([
        ocr.listQueue(),
        settings.listBranches().catch(() => []),
      ]);
      setRows(q); setBranches(b);
    } catch (e) {
      setErr((e as Error).message);
    } finally { setLoading(false); }
  }
  useEffect(() => { load(); }, []);

  async function handleFiles(files: FileList | null) {
    if (!files || !files.length) return;
    const branchId = branches[0]?.id;
    if (!branchId) {
      alert('Add a branch in Settings → Branches first');
      return;
    }
    setUploading(true);
    try {
      for (const file of Array.from(files)) {
        await ocr.upload(file, branchId);
      }
      await load();
    } catch (e) {
      alert((e as Error).message);
    } finally { setUploading(false); }
  }

  async function decide(id: string, decision: 'approve' | 'reject') {
    try { await ocr.verify(id, decision); await load(); }
    catch (e) { alert((e as Error).message); }
  }

  return (
    <div>
      <header className="flex items-end justify-between mb-6 flex-wrap gap-4">
        <div>
          <h2 className="text-2xl font-bold">OCR — Receipts</h2>
          <p className="text-fg-muted text-sm">
            Upload bills · extract vendor, date, amount · approve into expenses with one tap
          </p>
        </div>
        <button className="btn btn-ghost" onClick={load}><RefreshCw size={14}/></button>
      </header>

      {/* Upload zone */}
      <div
        onDragOver={(e) => { e.preventDefault(); setDragOver(true); }}
        onDragLeave={() => setDragOver(false)}
        onDrop={(e) => {
          e.preventDefault(); setDragOver(false);
          handleFiles(e.dataTransfer.files);
        }}
        onClick={() => inputRef.current?.click()}
        className={`card border-dashed border-2 text-center py-12 mb-6 cursor-pointer transition
          ${dragOver ? 'border-accent bg-accent/5' : 'border-bg-border hover:border-accent'}`}
      >
        <input ref={inputRef} type="file" multiple hidden
          accept="image/jpeg,image/png,image/heic,application/pdf"
          onChange={(e) => handleFiles(e.target.files)}/>
        {uploading ? (
          <>
            <Loader2 size={36} className="mx-auto mb-3 text-accent animate-spin"/>
            <p className="font-medium mb-1">Uploading &amp; running OCR…</p>
          </>
        ) : (
          <>
            <ScanLine size={36} className="mx-auto mb-3 text-fg-muted"/>
            <p className="font-medium mb-1">Drag &amp; drop a bill image or PDF</p>
            <p className="text-fg-muted text-sm">or tap to choose a file</p>
            <button type="button" className="btn btn-primary mt-4">
              <Upload size={14}/> Choose file
            </button>
            <p className="text-xs text-fg-muted mt-3">
              JPG · PNG · HEIC · PDF · auto-detects duplicates by SHA-256
            </p>
          </>
        )}
      </div>

      {err && (
        <div className="card mb-4 border-accent-bad/40 bg-accent-bad/10 text-accent-bad text-sm flex items-center gap-2">
          <AlertTriangle size={14}/> {err}
        </div>
      )}

      <h3 className="font-semibold mb-3 flex items-center gap-2">
        <FileText size={16}/> Verification queue · {rows.length} item{rows.length === 1 ? '' : 's'}
      </h3>
      {loading ? (
        <div className="card flex items-center gap-3 text-fg-muted">
          <Loader2 className="animate-spin" size={16}/> Loading…
        </div>
      ) : !rows.length ? (
        <div className="card text-fg-muted text-sm">
          No bills in the queue. Upload a receipt to extract vendor / date / amount automatically.
        </div>
      ) : (
        <div className="space-y-3">
          {rows.map((o) => (
            <div key={o.id} className="card flex items-center gap-4 flex-wrap">
              <div className="p-2 bg-bg-raised rounded text-fg-muted">
                <FileText size={20}/>
              </div>
              <div className="flex-1 min-w-[180px]">
                <div className="font-semibold">{o.vendor_name || '— unknown vendor —'}</div>
                <div className="text-xs text-fg-muted">
                  {o.invoice_date ? new Date(o.invoice_date).toLocaleDateString('en-IN') : 'no date'}
                  {o.invoice_no && ` · ${o.invoice_no}`}
                </div>
              </div>
              <div className="text-right">
                <div className="font-mono font-bold">
                  {o.amount_minor != null ? inr(o.amount_minor) : '—'}
                </div>
              </div>
              <div className={`chip ${STATUS_COLOR[o.status]}`}>
                {o.status === 'needs_review' && <AlertTriangle size={12} className="mr-1"/>}
                {STATUS_LABEL[o.status] || o.status}
              </div>
              {o.status === 'needs_review' && (
                <div className="flex gap-2">
                  <button className="btn btn-primary !min-h-[40px] !py-2"
                    onClick={() => decide(o.id, 'approve')}>
                    <Check size={14}/> Approve
                  </button>
                  <button className="btn btn-ghost !min-h-[40px] !py-2"
                    onClick={() => decide(o.id, 'reject')}>
                    <X size={14}/> Reject
                  </button>
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
