/**
 * Live receipt — renders from the backend's OrderDTO. Same Rule-46 layout
 * as the demo Receipt, but every field is what the backend actually computed
 * and persisted (invoice_no, CGST/SGST split, round-off, place of supply).
 */
import { inr, inrInWords } from '@/lib/inr';
import type { OrderDTO } from '@/lib/erp-api';
import {
  formatPlaceOfSupply,
  receiptDocumentTitle,
  type ReceiptBusinessDetails,
} from './receipt-business';

const datestr = (d: Date) =>
  `${String(d.getDate()).padStart(2, '0')}-` +
  ['Jan','Feb','Mar','Apr','May','Jun','Jul','Aug','Sep','Oct','Nov','Dec'][d.getMonth()] +
  `-${d.getFullYear()}`;
const timestr = (d: Date) =>
  `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;

export default function LiveReceipt({
  order,
  business,
}: {
  order: OrderDTO;
  business: ReceiptBusinessDetails;
}) {
  const now = new Date();
  const isPlatformDelivery = order.type === 'delivery' && !!order.delivery_via && order.delivery_via !== 'inhouse';
  const documentTitle = receiptDocumentTitle(business, isPlatformDelivery);
  const isGstDocument = documentTitle === 'Tax Invoice' || documentTitle === 'Bill of Supply';
  return (
    <div className="font-mono text-xs leading-relaxed text-fg print:text-black bg-bg-raised print:bg-white rounded-xl p-5 print:p-0">
      <div className="text-center">
        <div className="text-base font-bold tracking-wide">{business.supplierName.toUpperCase()}</div>
        {business.branchName && business.branchName !== business.supplierName && (
          <div className="text-[10px] text-fg-muted print:text-black/70">Branch: {business.branchName}</div>
        )}
        {business.address && <div className="text-[10px] text-fg-muted print:text-black/70">{business.address}</div>}
        {(business.gstin || business.fssaiLicenseNo || business.tradeLicenseNo) && (
          <div className="text-[10px] text-fg-muted print:text-black/70 mt-1">
            {business.gstin && <>GSTIN: {business.gstin}<br/></>}
            {business.fssaiLicenseNo && <>FSSAI Lic: {business.fssaiLicenseNo}<br/></>}
            {business.tradeLicenseNo && <>Trade Lic: {business.tradeLicenseNo}</>}
          </div>
        )}
      </div>

      <Dashed/>

      <div className="flex justify-between gap-2">
        <div><b>{documentTitle}</b></div>
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
      {business.cashierName && (
        <div className="text-[10px] text-fg-muted print:text-black/70">Cashier: {business.cashierName}</div>
      )}
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
        <Row label={isGstDocument ? 'Subtotal (taxable)' : 'Subtotal'} v={order.subtotal_minor} />
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
        {isGstDocument && <div>Place of supply: {formatPlaceOfSupply(order.place_of_supply_state_code ?? business.stateCode)}</div>}
        {isPlatformDelivery && <div>GST on restaurant service payable by platform under section 9(5).</div>}
        {isGstDocument && <div>Reverse charge: No</div>}
        <div className="mt-2 break-words">
          Amount in words: {inrInWords(order.total_minor)}
        </div>
      </div>

      <Dashed/>

      <div className="text-center text-[10px] text-fg-muted print:text-black/70 mt-2">
        This is a computer-generated document; signature not required.<br/>
        <span className="text-accent print:text-black">Thank you for visiting {business.brandName}.</span>
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
