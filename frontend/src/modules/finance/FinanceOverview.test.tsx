// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import FinanceScreen from './FinanceScreen';
import type { ReportDataDTO } from '@/lib/erp-api';
const m=vi.hoisted(()=>({daily:vi.fn(),expenses:vi.fn()}));
vi.mock('@/modules/auth/AuthContext',()=>({useAuth:()=>({me:{effective_permissions:['finance.read']}})}));
vi.mock('@/components/ui/Notifications',()=>({useNotifications:()=>({success:vi.fn(),error:vi.fn()})}));
vi.mock('@/hooks/useRealtimeRefresh',()=>({useRealtimeRefresh:()=>undefined}));
vi.mock('@/lib/erp-api',()=>({reports:{daily:m.daily},finance:{businessMetrics:async()=>null,listExpenses:m.expenses,listBranches:async()=>[]},pos:{receiptBusiness:async()=>({gst_registration_type:'unregistered'})},insights:{costingCoverage:async()=>null},settings:{listExpenseCategories:async()=>[]},shifts:{list:async()=>[]}}));
const report={accounting_basis:'operational_receipt',period:'daily',label:'Synthetic day',period_start:'2026-11-13',period_end:'2026-11-13',fiscal_year:'26-27',unissued_paid_orders_count:0,tips_collected_minor:0,membership_refunds_issued_minor:0,net_payments_received_minor:21000,gross_revenue_minor:31500,payments_received:{cash_minor:31500,upi_minor:0,card_minor:0,bank_minor:0,qr_minor:0,wallet_minor:0,other_minor:0,total_minor:31500},revenue:{food_minor:31500,gaming_minor:0,hookah_minor:0,event_tickets_minor:0,memberships_minor:0,delivery_aggregator_minor:0,other_minor:0,manual_collections_minor:0,discounts_and_points_redeemed_minor:0,rounding_income_minor:0,rounding_expense_minor:0,round_off_minor:0,total_minor:31500},tax_collected:{cgst_minor:500,sgst_minor:500,igst_minor:0,cess_minor:0,total_minor:1000},expense_total_minor:0,net_profit_minor:20000,manual_collections_minor:0,orders_count:3,tickets_count:0,avg_ticket_minor:10500,expenses:[],cogs_minor:0,gross_profit_minor:20000,depreciation_minor:0,receipt_corrections_minor:0,refunds_issued_minor:10500,refunded_tips_minor:0,settled_refunds_issued_minor:10500,net_revenue_minor:20000} satisfies ReportDataDTO;
let root:Root,host:HTMLDivElement;
beforeEach(()=>{vi.clearAllMocks();(globalThis as unknown as {IS_REACT_ACT_ENVIRONMENT:boolean}).IS_REACT_ACT_ENVIRONMENT=true;m.daily.mockResolvedValue(report);m.expenses.mockResolvedValue([]);host=document.createElement('div');document.body.append(host);root=createRoot(host);});
afterEach(()=>{act(()=>root.unmount());document.body.replaceChildren();});
async function render(){await act(async()=>root.render(<FinanceScreen/>));}
function row(label:string){const item=[...host.querySelectorAll('div')].find(n=>n.textContent===label);if(!item?.parentElement?.parentElement)throw Error('Missing '+label);return item.parentElement.parentElement.textContent;}
function button(text:string){const b=[...host.querySelectorAll<HTMLButtonElement>('button')].find(n=>n.textContent?.trim()===text||n.getAttribute('aria-label')===text);if(!b)throw Error('Missing '+text);return b;}
it('renders the actual recognized-refund bridge and keeps the authoritative server net',async()=>{
  await render();expect(row('Recorded gross revenue')).toContain('315');expect(row('Less: recognized refunds (excluding returned tips)')).toContain('105');expect(row('Less: recorded indirect tax')).toContain('10');expect(row('Net revenue after recorded tax')).toContain('200');
});
it('recognized store-credit refunds and returned tips are distinct from settled cash and receipt corrections',async()=>{
  m.daily.mockResolvedValue({...report,receipt_corrections_minor:5000,refunds_issued_minor:10500,refunded_tips_minor:500,settled_refunds_issued_minor:0,net_revenue_minor:15500});await render();
  expect(row('Less: no-funds receipt corrections')).toContain('50');expect(row('Less: recognized refunds (excluding returned tips)')).toContain('100');expect(row('Net revenue after recorded tax')).toContain('155');
});
it.each(['receipt_corrections_minor','refunds_issued_minor','refunded_tips_minor'])('missing %s is unknown detail, never confirmed zero',async key=>{
  m.daily.mockResolvedValue({...report,[key]:undefined});await render();expect(host.textContent).toContain('missing details are not confirmed zero');expect(row('Net revenue after recorded tax')).toContain('200');
});
it('known zero offsets omit unnecessary rows while the no-tax branch still shows server net',async()=>{
  m.daily.mockResolvedValue({...report,refunds_issued_minor:0,refunded_tips_minor:0,tax_collected:{...report.tax_collected,total_minor:0,cgst_minor:0,sgst_minor:0},net_revenue_minor:31500});await render();
  expect(host.textContent).not.toContain('Less: recognized refunds');expect(host.textContent).not.toContain('Some revenue offset details');expect(row('Net revenue')).toContain('315');
});
it('section selection is exposed and the named expense refresh remains a read-only recovery action',async()=>{
  await render();expect(button('Overview').getAttribute('aria-pressed')).toBe('true');await act(async()=>button('Expenses').click());expect(button('Overview').getAttribute('aria-pressed')).toBe('false');expect(button('Expenses').getAttribute('aria-pressed')).toBe('true');
  m.expenses.mockRejectedValueOnce(new Error('Synthetic read failure'));await act(async()=>button('Refresh expenses').click());expect(host.textContent).toContain('Synthetic read failure');await act(async()=>button('Refresh expenses').click());expect(m.expenses).toHaveBeenCalledTimes(3);expect(host.textContent).not.toContain('Synthetic read failure');
});
