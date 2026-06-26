/**
 * Live receipt — renders from the backend's OrderDTO. Same Rule-46 layout
 * as the demo Receipt, but every field is what the backend actually computed
 * and persisted (invoice_no, CGST/SGST split, round-off, place of supply).
 */
import { COMPANY } from '@/lib/demo-data';
import { inr, inrInWords } from '@/lib/inr';
import type { OrderDTO } from '@/lib/erp-api';

const datestr = (d: Date) =>
  `${String(d.getDate()).padStart(2, '0')}-` +
  ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][d.getMonth()] +
  `-${d.getFullYear()}`;
const timestr = (d: Date) =>
  `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

export default function LiveReceipt({ order }: { order: OrderDTO }) {
  const now = new Date();
  const isPlatformDelivery = order.type === 'delivery' && !!order.delivery_via && order.delivery_via !== 'inhouse';
  return (
    <div className="font-mono text-xs leading-relaxed text-fg print:text-black bg-bg-raised print:bg-white rounded-xl p-5 print:p-0">
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

      <div className="flex justify-between gap-2">
        <div><b>{isPlatformDelivery ? 'Platform Delivery Bill' : 'Tax Invoice'}</b></div>
        <div className="text-right">
          <div>No.  {order.invoice_no ?? '—'}</div>
          <div>Date {datestr(now)} {timestr(now)}</div>
        </div>
      </div>
      <div className="flex justify-between text-[10px] text-fg-muted print:text-black/70">
        <span>
          {order.type.replace('_', ' ')}
          {order.delivery_via ? ` · ${order.delivery_via.replace('_', ' ')}` : ''}
        </span>
        <span>FY {order.fiscal_year}</span>
      </div>
      {(order.customer_name || order.customer_phone) && (
        <div className="text-[10px] text-fg-muted print:text-black/70 mt-1">
          Customer: {order.customer_name || '—'}{order.customer_phone ? ` · ${order.customer_phone}` : ''}
        </div>
      )}

      <Dashed/>

      <div className="grid grid-cols-[1fr_auto_auto] gap-x-3 gap-y-1">
        <div className="text-fg-muted print:text-black/70">Item (HSN/SAC)</div>
        <div className="text-right text-fg-muted print:text-black/70">Qty × Rate</div>
        <div className="text-right text-fg-muted print:text-black/70">Amt</div>

        {order.lines.map((l, i) => (
          <Line key={i} l={l}/>
        ))}
      </div>

      <Dashed/>

      <div className="space-y-0.5">
        <Row label="Subtotal (taxable)" v={order.subtotal_minor} />
        {order.discount_minor > 0 && <Row label="Membership discount" v={-order.discount_minor} />}
        {order.cgst_minor > 0 && <Row label="CGST" v={order.cgst_minor} />}
        {order.sgst_minor > 0 && <Row label="SGST" v={order.sgst_minor} />}
        {order.igst_minor > 0 && <Row label="IGST" v={order.igst_minor} />}
        {isPlatformDelivery && <Row label="D Company GST" v={0} />}
        {order.round_off_minor !== 0 && <Row label="Round off" v={order.round_off_minor} />}
        <div className="border-t border-bg-border print:border-black/30 mt-1 pt-1 flex justify-between font-bold text-base">
          <span>GRAND TOTAL</span><span>{inr(order.total_minor)}</span>
        </div>
      </div>

      <Dashed/>

      <div className="text-[10px] text-fg-muted print:text-black/70 space-y-0.5">
        <div>Place of supply: {order.place_of_supply_state_code ?? '32'}-{COMPANY.state_name}</div>
        {isPlatformDelivery && <div>GST on restaurant service payable by platform under section 9(5).</div>}
        <div>Reverse charge: No</div>
        <div className="mt-2 break-words">
          Amount in words: {inrInWords(order.total_minor)}
        </div>
      </div>

      <Dashed/>

      <div className="text-center text-[10px] text-fg-muted print:text-black/70 mt-2">
        This is a computer-generated invoice; signature not required.<br/>
        <span className="text-accent print:text-black">Thank you for visiting D Company.</span>
      </div>
    </div>
  );
}

function Line({ l }: { l: OrderDTO['lines'][number] }) {
  return (
    <>
      <div className="break-words">
        {l.name}
        <span className="text-fg-muted print:text-black/70"> ({l.hsn_or_sac || '—'})</span>
      </div>
      <div className="text-right text-fg-muted print:text-black/70 whitespace-nowrap">
        {l.qty} × {inr(l.unit_price_minor, { withSymbol: false })}
      </div>
      <div className="text-right whitespace-nowrap">{inr(l.line_total_minor)}</div>
    </>
  );
}
function Row({ label, v }: { label: string; v: number }) {
  const amount = v < 0 ? `-${inr(Math.abs(v))}` : inr(v);
  return <div className="flex justify-between"><span className="text-fg-muted print:text-black/80">{label}</span><span>{amount}</span></div>;
}
function Dashed() { return <div className="my-2 border-t border-dashed border-bg-border print:border-black/30" />; }
