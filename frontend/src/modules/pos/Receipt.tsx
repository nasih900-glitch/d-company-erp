/**
 * Kerala GST-compliant receipt.
 *
 * Every Rule-46 mandatory field is here:
 *  - Supplier name, address, GSTIN
 *  - Invoice number (per-branch per-FY series), date
 *  - Place of supply (32-Kerala)
 *  - HSN/SAC per line
 *  - CGST / SGST split (or IGST for inter-state)
 *  - Reverse charge: No
 *  - Round-off line
 *  - FSSAI licence number
 *  - Amount in words
 *  - "Computer-generated invoice" line in place of signature
 */
import { COMPANY } from '@/lib/demo-data';
import { inr, inrInWords } from '@/lib/inr';
import type { ReceiptData } from './POSScreen';

const date = (d: Date) =>
  `${String(d.getDate()).padStart(2, '0')}-` +
  ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][d.getMonth()] +
  `-${d.getFullYear()}`;
const time = (d: Date) =>
  `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

export default function Receipt({ data }: { data: ReceiptData }) {
  return (
    <div className="font-mono text-xs leading-relaxed text-fg print:text-black bg-bg-raised print:bg-white rounded-xl p-5 print:p-0">
      {/* Header */}
      <div className="text-center">
        <div className="text-base font-bold tracking-wide">{COMPANY.name.toUpperCase()}</div>
        <div className="text-[10px] text-fg-muted print:text-black/70">{COMPANY.address}</div>
        <div className="text-[10px] text-fg-muted print:text-black/70 mt-1">
          GSTIN: {COMPANY.gstin}<br/>
          FSSAI Lic: {COMPANY.fssai}<br/>
          Trade Lic: {COMPANY.trade_license}
        </div>
      </div>

      <Dashed/>

      {/* Invoice meta */}
      <div className="flex justify-between gap-2">
        <div><b>Tax Invoice</b></div>
        <div className="text-right">
          <div>No.  {data.invoice_no}</div>
          <div>Date {date(data.at)} {time(data.at)}</div>
        </div>
      </div>
      <div className="flex justify-between text-[10px] text-fg-muted print:text-black/70">
        <span>{data.type === 'dine_in' ? `Table ${data.table} · Dine-in` :
               data.type === 'takeaway' ? 'Takeaway' :
               'Delivery'}</span>
        <span>Cashier: Anish K.</span>
      </div>

      <Dashed/>

      {/* Line items */}
      <div className="grid grid-cols-[1fr_auto_auto] gap-x-3 gap-y-1">
        <div className="text-fg-muted print:text-black/70">Item (HSN)</div>
        <div className="text-right text-fg-muted print:text-black/70">Qty × Rate</div>
        <div className="text-right text-fg-muted print:text-black/70">Amt</div>

        {data.lines.map((l, i) => (
          <Line key={i} l={l} />
        ))}
      </div>

      <Dashed/>

      {/* Totals — GST split */}
      <div className="space-y-0.5">
        <Row label="Subtotal (taxable)" v={data.totals.taxable} />
        <Row label="CGST @ 2.5%"        v={data.totals.cgst} />
        <Row label="SGST @ 2.5%"        v={data.totals.sgst} />
        <Row label="Round off"          v={data.totals.round_off} />
        <div className="border-t border-bg-border print:border-black/30 mt-1 pt-1 flex justify-between font-bold text-base">
          <span>GRAND TOTAL</span><span>{inr(data.totals.total)}</span>
        </div>
      </div>

      <Dashed/>

      {/* Compliance footer */}
      <div className="text-[10px] text-fg-muted print:text-black/70 space-y-0.5">
        <div>Place of supply: {COMPANY.state_code}-{COMPANY.state_name}</div>
        <div>Payment: <b className="text-fg print:text-black uppercase">{data.method}</b>
          {data.method === 'upi' && '  ·  dcompany@hdfcbank  Ref: 645829137245'}
          {data.method === 'card' && '  ·  Visa ****1234  Auth: A4F92B'}
        </div>
        <div>Reverse charge: No</div>
        <div className="mt-2 break-words">Amount in words: {inrInWords(data.totals.total)}</div>
      </div>

      <Dashed/>

      <div className="text-center text-[10px] text-fg-muted print:text-black/70 mt-2">
        This is a computer-generated invoice; signature not required.<br/>
        <span className="text-accent print:text-black">Thank you for visiting D Company.</span><br/>
        Tips are voluntary.
      </div>
    </div>
  );
}

function Line({ l }: { l: ReceiptData['lines'][number] }) {
  return (
    <>
      <div className="break-words">
        {l.name}
        <span className="text-fg-muted print:text-black/70"> ({l.hsn})</span>
      </div>
      <div className="text-right text-fg-muted print:text-black/70 whitespace-nowrap">
        {l.qty} × {inr(l.unit_inclusive, { withSymbol: false })}
      </div>
      <div className="text-right whitespace-nowrap">{inr(l.line_inclusive)}</div>
    </>
  );
}

function Row({ label, v }: { label: string; v: number }) {
  return (
    <div className="flex justify-between">
      <span className="text-fg-muted print:text-black/80">{label}</span>
      <span>{inr(v)}</span>
    </div>
  );
}

function Dashed() {
  return <div className="my-2 border-t border-dashed border-bg-border print:border-black/30" />;
}
