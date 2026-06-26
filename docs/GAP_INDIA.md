# Gap analysis — current scaffold vs India / Kerala compliance

Read this alongside [`INDIA_TAX_COMPLIANCE.md`](INDIA_TAX_COMPLIANCE.md). Each row says: what the law / business needs, where we stand, and what to do.

**Status legend:** ✅ done · 🟡 partial · 🔴 missing · ⚪ deferred (V2 / not needed yet)

---

## Tenant & company identity

| Need | Status | Where | What to do |
|---|---|---|---|
| GSTIN on the company | 🔴 | `models/tenant.py:Company` only has `gstin` column — never read/rendered | Wire it through schemas, validate format (15 chars, state-prefix match) |
| State code on company / branch | 🔴 | No column | Add `state_code` (`'32'` for Kerala) on `branches` — derives place of supply |
| FSSAI licence number | 🔴 | No column | Add `fssai_license_no` on `branches` (it's per-premises, not per-company) |
| Local body trade licence | 🔴 | No column | Add `trade_license_no` on `branches` |
| Default state for place-of-supply | 🔴 | — | Derive from `branches.state_code`; override per order if delivery elsewhere |
| GST registration type | 🔴 | No column | Add `gst_registration_type` enum on `companies`: `regular` / `composition` / `unregistered` |
| Composition scheme flag | 🔴 | — | When set, suppress tax lines on bill; emit "Bill of Supply" not "Tax Invoice" |

## Customer (recipient) identity on the order

| Need | Status | Where | What to do |
|---|---|---|---|
| B2B customer GSTIN capture | 🔴 | `orders` only has `customer_name`/`customer_phone` | Add `customer_gstin` + `customer_address` + `customer_state_code` |
| Auto-trigger when amount ≥ ₹50k for B2C name capture | 🔴 | — | Service-layer validation |
| Distinguish B2B / B2C / B2C(L) for GSTR-1 categorization | 🔴 | — | Derive from `customer_gstin` presence + total amount + place-of-supply mismatch |

## Tax computation

| Need | Status | Where | What to do |
|---|---|---|---|
| CGST + SGST split on orders | 🟡 | `orders` has single `tax_minor` | Add `cgst_minor`, `sgst_minor`, `igst_minor`, `cess_minor` — keep `tax_minor` as derived sum |
| CGST + SGST split per line | 🟡 | `order_lines.line_total_minor` collapses everything | Add per-line `cgst_minor`, `sgst_minor`, `igst_minor`, `cess_minor`, `taxable_value_minor`, `tax_rate` |
| HSN/SAC per line | 🔴 | `order_lines` has menu_item_id but no HSN snapshot | Snapshot `hsn_code` + `sac_code` at line-write time (menu can change, history can't) |
| Menu item HSN/SAC | 🔴 | `menu_items` has no HSN | Add `hsn_code` (6 chars) and `tax_rate` (decimal) — make these required at menu-item save |
| Gaming station SAC | 🔴 | `stations` has no SAC | Add `sac_code` (default `'999692'`) and `tax_rate` (default `0.18`) |
| Price-inclusive vs exclusive | 🔴 | `menu_items.base_price_minor` ambiguous | Add `price_includes_tax` boolean on menu items; default `true` for café (menu prices include tax) |
| Reverse charge flag on order | 🔴 | — | Add `is_reverse_charge` on orders (almost always false; needed for landlord-rent-from-unregistered case) |
| Place of supply per order | 🔴 | — | Add `place_of_supply_state_code` on `orders`; default to branch state code |
| Intra-state vs inter-state derivation | 🔴 | — | Service-layer: if `place_of_supply == branch_state` → CGST+SGST, else IGST |
| Aggregator-route order (Section 9(5)) | 🔴 | — | Add `delivery_via` enum: `inhouse` / `zomato` / `swiggy` / `other`; when not inhouse, zero out tax columns |
| Round-off line | 🟡 | `orders.total_minor` already integer | Add explicit `round_off_minor` so the breakdown reconciles on the printed bill |
| Tips / voluntary service charge (post-tax) | 🔴 | — | Add `tip_minor` on orders; never feeds the tax base; ledger as "Tips Payable" liability |
| Discount before vs after tax | 🟡 | `order_lines.discount_minor` exists | Confirm service layer subtracts discount BEFORE computing tax (pre-supply discount) |

## Invoice numbering

| Need | Status | Where | What to do |
|---|---|---|---|
| Sequential per-branch series per FY | 🔴 | `orders.idempotency_key` is closest thing | Add `invoice_no` (string, unique per branch per FY) with format `D/{branch_code}/{FY}/{seq}` |
| Restart on 1 April | 🔴 | — | New `invoice_counters` table: `(branch_id, fiscal_year, series, next_seq)` |
| No-gaps guarantee even on void | 🔴 | — | Void = `status='void'` but `invoice_no` retained and counted; never reuse a number |
| Max 16 chars | 🔴 | — | Validate format in service layer |
| Allowed chars `A-Z a-z 0-9 - /` | 🔴 | — | Regex validation `^[A-Za-z0-9/-]{1,16}$` |

## E-invoice + e-way bill (V2-ready)

| Need | Status | Where | What to do |
|---|---|---|---|
| IRN field on order | ⚪ | — | Add nullable `irn` (32 char), `irn_acknowledged_at`, `irn_ack_no` |
| Signed QR code from IRP | ⚪ | — | Add `e_invoice_qr` (text/base64) |
| E-invoice push retry / outbox | ⚪ | — | New table `e_invoice_outbox` for IRP submissions; worker picks them up |
| E-way bill number on inbound GRN | ⚪ | `grns` has no field | Add `eway_bill_no` on `grns` (supplier-generated, we just record) |
| Switch on when turnover crosses threshold | ⚪ | — | Company-level flag `e_invoicing_enabled`; default false |

## Bill of Supply (composition mode)

| Need | Status | Where | What to do |
|---|---|---|---|
| Different document title | 🔴 | — | Renderer picks template based on `company.gst_registration_type` |
| Suppress tax columns | 🔴 | — | Template branch |
| "Not eligible to charge tax" stamp | 🔴 | — | Template branch |
| Owner-paid 5% NOT on customer bill | 🔴 | — | Service layer: no tax line, just `total_minor = sum(line_totals)`; ledger still posts the 5% as expense to owner |

## Receipt / bill rendering

| Need | Status | Where | What to do |
|---|---|---|---|
| FSSAI line in receipt header | 🔴 | No receipt renderer yet | Add receipt template service (PDF + thermal ESC/POS) |
| GSTIN line | 🔴 | — | Same renderer |
| Trade-licence line | 🔴 | — | Same renderer |
| HSN per line | 🔴 | — | Same renderer |
| CGST + SGST split | 🔴 | — | Same renderer |
| Amount in words ≥ ₹1 lakh | 🔴 | — | Use `num2words` (Indian style) |
| "Computer-generated invoice" line | 🔴 | — | Same renderer |
| QR code (e-invoice OR payment) | 🔴 | — | Same renderer |
| Indian number format (`1,00,000`) | 🔴 | — | Locale formatter |
| DD-Mon-YYYY date | 🔴 | — | Locale formatter |
| Bilingual (English + Malayalam) optional | ⚪ | — | Template variant |

## Payment methods

| Need | Status | Where | What to do |
|---|---|---|---|
| UPI as a `Payment.method` value | 🟡 | Already enum includes `upi`, `qr` | OK |
| Static UPI QR (VPA-only) | 🔴 | — | New table `branch_payment_methods` with VPA/merchant ID |
| Dynamic UPI QR (amount + ref encoded) | 🔴 | — | Service generates `upi://pay?pa=...&am=...&tr=...&tn=...` string and renders QR |
| Razorpay / Cashfree webhook → auto-mark paid | 🔴 | — | New endpoint `/api/v1/pos/webhooks/razorpay` validating HMAC, looking up by `ref_external` |
| Card MDR booking | 🔴 | — | Finance module: when payment method is card, accrue an expense for MDR (configurable %) |
| Cash drawer reconciliation already wired | ✅ | `shifts` has `expected_minor` / `counted_minor` / `variance_minor` | OK |

## Statutory filings

| Need | Status | Where | What to do |
|---|---|---|---|
| GSTR-1 JSON / CSV export | 🔴 | — | New endpoint `/api/v1/finance/exports/gstr-1?period=YYYY-MM` producing the GSTN-prescribed JSON |
| GSTR-3B summary export | 🔴 | — | Same module, different shape |
| GSTR-9 annual aggregator | ⚪ | — | V2 once 12 months of GSTR-1/3B exist |
| CMP-08 (composition) | 🔴 (if composition) | — | Same export module |
| TDS (24Q / 26Q) quarterly | ⚪ | — | Payroll + AP modules |
| EPF (ECR) monthly | ⚪ | — | Payroll module |
| ESI monthly | ⚪ | — | Payroll module |
| Kerala professional-tax half-yearly | 🔴 | — | Payroll: compute, accrue as liability, export remittance file |
| Local-body trade-licence renewal reminder | 🔴 | — | Notifications module (V2) |

## Chart of accounts (India-tuned)

Already partially seeded in `scripts/seed.py`. India-specific additions needed:

| Account | Type | Notes |
|---|---|---|
| Output CGST Payable | Liability | One per rate? Single account is fine; group by rate at reporting |
| Output SGST Payable | Liability | Same |
| Output IGST Payable | Liability | Same |
| Output Cess Payable | Liability | Cess on tobacco/lux (currently n/a for café) |
| Input CGST Credit (ITC) | Asset | When buying with GST, ITC accrues here. Composition: don't use. |
| Input SGST Credit (ITC) | Asset | |
| Input IGST Credit (ITC) | Asset | |
| Reverse-Charge GST Payable | Liability | When café pays GST under RCM (e.g. rent from unregistered landlord) |
| TDS Payable | Liability | Per section: 192 (salary), 194C (contract), 194I (rent), 194J (professional), 194H (commission) |
| TCS Receivable | Asset | TCS collected by aggregators on D Company's supplies |
| EPF Payable | Liability | Monthly |
| ESI Payable | Liability | Monthly |
| Professional Tax Payable | Liability | Half-yearly |
| Gratuity Provision | Liability | Annual provision |
| Tips Payable to Staff | Liability | Customer tips collected on card/UPI to be paid out to staff |
| Round-off | Revenue / Expense | Tiny clearing account |
| MDR (Card Charges) | Expense | Visa/MasterCard merchant discount rate |
| UPI Charges | Expense | Mostly zero now, but track for future |
| Aggregator Commission | Expense | Zomato/Swiggy commission slab |
| FSSAI Licence Fee (Asset/Expense) | Expense | Annual |
| Trade Licence Fee | Expense | Annual |

## Reference data tables to add

| Table | What it holds | Why |
|---|---|---|
| `state_codes` | The 36 Indian state/UT codes (Kerala = 32) | Place-of-supply, GSTIN parsing |
| `gst_rate_slabs` | (0, 0.25%, 3%, 5%, 12%, 18%, 28%) with CGST/SGST split rules | UI dropdowns + validation |
| `hsn_codes` | HSN catalogue subset relevant to café (chapters 09, 11, 17, 18, 19, 21, 22) | Menu-item attribution |
| `sac_codes` | SAC catalogue subset (9963 restaurant, 9996 amusement, 9971 financial) | Service attribution |
| `tds_sections` | 192, 194C, 194I, 194J, 194H — with rates and thresholds | Accounts payable |
| `kerala_pt_slabs` | Half-yearly PT slabs | Payroll |
| `epf_esi_config` | Rates + wage ceilings | Payroll |
| `tax_engine_versions` | Version + effective date for the whole tax computation | Audit: which rules were in effect on this old invoice? |

## Service-layer / business rules

| Rule | Status | Notes |
|---|---|---|
| Restaurant supply taxed at 5% as one mixed supply (no per-ingredient HSN tax math) | 🔴 | Hard-code in OrderPricingService |
| If `delivery_via in ('zomato','swiggy')` → zero GST on this invoice (aggregator pays it under 9(5)) | 🔴 | Same service |
| If `place_of_supply != branch_state` → IGST instead of CGST+SGST | 🔴 | Same service |
| If `company.gst_registration_type == 'composition'` → no tax lines, owner-paid GST goes to expense account | 🔴 | Same service |
| Round invoice total to nearest rupee, emit `round_off` line | 🔴 | Same service |
| Invoice number: per-branch per-FY sequence, atomic counter | 🔴 | New `InvoiceNumberService` with row-level lock on `invoice_counters` |
| Tip collected on UPI/Card → automatic JE to "Tips Payable" liability | 🔴 | PaymentService |
| MDR auto-accrual on card payments | 🔴 | PaymentService |
| Manager override required for refund > X or void > Y | 🟡 | Permission `pos.refund` exists but no amount threshold | Add `discount.large` config per role |

## What's intentionally deferred (V2)

| Item | Why |
|---|---|
| Full e-invoicing push | D Company turnover < ₹5 cr in year one. Schema is ready; integration when threshold matters. |
| GSTR-9 annual aggregator | Needs 12 months of GSTR-1/3B data first. |
| TDS deduction engine | Triggers only when D Company crosses certain payment thresholds. |
| Bilingual Malayalam invoice | English bill is legally sufficient. Add when customer-facing demand is real. |
| Alcohol module | Kerala restricts alcohol heavily. Different tax engine entirely. |
| Multi-state expansion | Schema already multi-tenant by `company_id` + `branch_id` + `state_code`. Wire when second branch opens. |

---

## Suggested implementation order

1. **Phase 1 — schema** (this session): add the tax columns, GSTIN/FSSAI columns, place-of-supply column. Migration `0002_india_compliance.py`. New reference tables. (Task #10.)
2. **Phase 2 — pricing engine**: rewrite `OrderPricingService` with the India rules. Add unit tests for: 5% standalone, 18% gaming, 9(5) aggregator zero, IGST inter-state, composition zero-tax-on-bill. **One full session.**
3. **Phase 3 — receipt renderer**: thermal printer (ESC/POS) + PDF + email templates with all mandatory fields. **Half session.**
4. **Phase 4 — statutory exports**: GSTR-1, GSTR-3B JSON exporters. **One session.**
5. **Phase 5 — e-invoicing**: only when turnover crosses ₹5 cr. Integration with NIC IRP sandbox first.
6. **Phase 6 — payroll**: PT, EPF, ESI compliance. **One session per scheme.**
