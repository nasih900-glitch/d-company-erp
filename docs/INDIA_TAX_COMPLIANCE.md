# India / Kerala Tax & Billing Compliance — D Company ERP

**Verified:** May 2026.  **Jurisdiction:** Kerala café + gaming lounge.  **Scope:** Everything the POS, finance, and inventory modules need to be legally correct.

This is the single source of truth for compliance. If the code disagrees with this doc, fix the code. If a law changes, update this doc *first*.

---

## 1. GST — the big picture

India runs a destination-based **Goods and Services Tax** (GST) with four parallel collections:

| Tax | When it applies | Collected by |
|---|---|---|
| **CGST** (Central GST) | Intra-state supply | Centre |
| **SGST** (State GST) | Intra-state supply | State (Kerala) |
| **IGST** (Integrated GST) | Inter-state supply OR import/export | Centre, then split |
| **UTGST** (Union Territory GST) | Within a UT (Lakshadweep etc.) | UT |
| **Compensation Cess** | Luxury / demerit goods (tobacco, large cars). Extended till **March 2026**, then under review | Centre |

For Kerala-based café operations, **CGST + SGST split applies** to every local sale. A bill for a customer from another state who walks in is still treated as intra-state (point of supply is the restaurant). Delivery to another state would use IGST.

**Rate** = headline rate. **CGST = SGST = headline ÷ 2.** So 5% GST = 2.5% CGST + 2.5% SGST. Always store both halves separately in the database — auditors will ask for it.

## 2. GST rates that apply to D Company

| Service | Headline GST | CGST | SGST | ITC available? | Notes |
|---|---|---|---|---|---|
| **Standalone restaurant — dine-in** | 5% | 2.5% | 2.5% | **No** | Default for D Company |
| **Standalone restaurant — takeaway** | 5% | 2.5% | 2.5% | No | Same rate as dine-in |
| **Standalone restaurant — home delivery** | 5% | 2.5% | 2.5% | No | If you deliver directly |
| Restaurant in "specified premises" (hotel room > ₹7,500/night) | 18% | 9% | 9% | **Yes** | Not D Company unless you're in a 5-star |
| Outdoor catering | 5% (composition) or 18% (regular) | — | — | Mixed | Event catering |
| **Gaming lounge / amusement** (PS5, VR, simulators) | 18% | 9% | 9% | **Yes** | SAC 999692 — amusement & recreation |
| Tournament entry fee (skill, in-premises) | 18% | 9% | 9% | Yes | Same SAC |
| **Composition-scheme restaurant** | 5% on turnover | — | — | No | Paid by owner, NOT collected from customer |

The AC vs non-AC distinction was removed in November 2017. Don't ask. Don't store it.

**Important nuance — the gaming side:** the Promotion and Regulation of Online Gaming Act, 2025 (in force from 1 May 2026) outlaws **online real-money gaming**. This does NOT affect a physical PS5 lounge where customers pay per hour to use a console on the premises — that's a recreation service, taxed at 18% under SAC 999692. Don't confuse the two.

## 3. Food delivery aggregators (Swiggy / Zomato / Uber Eats)

Since **1 January 2022** (Section 9(5) of CGST Act), the aggregator is treated as the deemed restaurant for GST purposes. So:

- Aggregator collects 5% from the customer and pays it to GST.
- The café does NOT charge GST on its commission slip from the aggregator.
- The café still reports the supply in GSTR-1 with a clear marker that it went through a 9(5) supplier.
- The aggregator deducts their commission + their TCS + transaction charges; what hits the café's bank is the net.

In the ERP: when an order is marked `type = 'delivery'` AND `delivery_via != 'inhouse'`, GST should be **zero on the café's invoice** (the aggregator's invoice carries it). Track aggregator orders separately for reconciliation.

## 4. Composition scheme — is D Company eligible?

| Criterion | Threshold | D Company |
|---|---|---|
| Aggregate turnover | ≤ ₹1.5 crore/year | Probably yes initially |
| Single state | Yes | ✅ Kerala only |
| Serves alcohol | **NOT allowed under composition** | If you ever do, exit composition |
| Inter-state supply | Not allowed | ✅ Local only |

**Composition restaurant pays a flat 5% on turnover, no ITC, no GST on the bill.** Bill becomes a "Bill of Supply" instead of a Tax Invoice. The **customer pays the menu price as-is — no tax line.** This is a huge simplification for a small café.

**Trade-offs of composition:**

- ✅ Simpler: quarterly CMP-08 + annual GSTR-4 (vs monthly GSTR-1 + GSTR-3B)
- ✅ Customer-facing bill is simpler (no tax breakdown)
- ❌ Cannot claim ITC on supplies (so the 18% you pay on the coffee machine is sunk cost)
- ❌ Cannot do inter-state supply or B2B with a registered buyer who wants ITC
- ❌ Cannot serve alcohol — full stop
- ❌ Cannot operate a gaming lounge under composition (different rate, different scheme)

**For D Company specifically:** the café-and-gaming-lounge combo probably can't use composition because gaming services aren't restaurant turnover and need separate registration logic. The ERP should support **both modes** because the right answer depends on real numbers.

## 5. GST registration thresholds

| Business type | Threshold (aggregate turnover/year) |
|---|---|
| Services (gaming lounge counts) | ₹20 lakh |
| Goods (food, if treated as goods) | ₹40 lakh |
| Restaurant (mixed supply, treated as service) | ₹20 lakh |
| Special category states | ₹10 lakh |
| **Composition scheme** | ₹1.5 crore (₹75 lakh special states) |

Kerala is **not** a special category state for GST purposes. Standard ₹20 lakh threshold applies to D Company.

## 6. HSN & SAC codes — what to store

**HSN** = Harmonized System of Nomenclature (for goods). **SAC** = Services Accounting Code (for services). Both are 6-digit numbers. The first 2 digits are the chapter, next 2 the heading, last 2 the subheading. E-invoicing now mandates 6 digits; manual entry on the GST portal was disabled in May 2025 (must select from dropdown).

| Turnover | Digits required on invoice |
|---|---|
| < ₹1.5 cr | None (but B2B always needs it) |
| ₹1.5 cr – ₹5 cr | 2 digits (chapter) |
| > ₹5 cr | 4 digits (heading) |
| E-invoicing (≥ ₹5 cr) | 6 digits (subheading) |

The ERP should **always store 6 digits** even if the customer-facing bill prints fewer. Decide truncation at render-time.

### Common HSN codes for a café

| Item | HSN | GST | Notes |
|---|---|---|---|
| Coffee, roasted | 0901 21 / 0901 22 | 5% | Most café coffee |
| Tea | 0902 | 5% | |
| Bottled water | 2201 | 18% | |
| Aerated drinks (Coke etc.) | 2202 10 | 28% + 12% cess | High-tax, reseller pays this |
| Fruit juice | 2202 99 | 12% | |
| Bread, pastries (incl. croissants) | 1905 | 5% / 18% | Plain bread 5%, fancy 18% |
| Cakes & pastries (sweet) | 1905 90 | 18% | |
| Ice cream | 2105 00 | 18% | |
| Sandwiches & wraps (prepared) | 2106 | 5% (as restaurant supply) | |
| Chocolate (confectionery) | 1806 | 18% | |

**Key insight:** when a customer buys "1 cappuccino" the **restaurant supply rule** dominates over the HSN of the underlying ingredients. The whole bill is taxed at 5% as restaurant service — you don't tax the milk at 5% and the beans at 5% separately. HSN per item is mainly for inventory/cost-tracking and B2B invoices.

### SAC codes for D Company services

| Service | SAC | GST |
|---|---|---|
| Restaurant service (food & beverage at premises) | 996331 | 5% |
| Outdoor catering | 996334 | 5% / 18% |
| Amusement parks & recreational services (gaming sessions + screening tickets) | 999692 | 18% |
| Renting of equipment without operator (PS5 hourly?) | 997319 | 18% |
| Event management | 998596 | 18% |

**Projector screening tickets** (football match, IPL, movies, esports) are taxed under **SAC 999692** at **18% GST** — same engine as gaming sessions. Each ticket is its own supply with its own tax breakdown; sequential ticket numbers (`EVT-YYYYMMDD-NNNN`) act as the audit trail. Refunds/cancellations keep the ticket row (status change only) so the audit log never has gaps.

## 7. The Bill / Tax Invoice — mandatory fields

Under **CGST Rule 46**, a tax invoice must contain ALL of these. Missing any → ₹25,000 penalty per invoice + customer's ITC gets denied (which means B2B customers will refuse to pay you again).

**Header:**
1. Supplier name (legal entity)
2. Supplier address (registered place of business)
3. Supplier **GSTIN** (15 chars)
4. Invoice number — unique per FY, ≤ 16 chars, sequential no gaps, only `A-Z a-z 0-9 - /`
5. Invoice date
6. (For B2B) Recipient name, address, GSTIN
7. (For B2C ≥ ₹50,000) Recipient name & address
8. **Place of supply** (state code, e.g. `32-Kerala`) — even for B2C, must be derivable

**Line items:**
9. Description of goods/service
10. **HSN/SAC** (digits per the rule above)
11. Quantity + unit of measure
12. Taxable value per line
13. **Tax rate**
14. Tax amount split: **CGST**, **SGST**, **IGST**, **Cess** (whichever applies)
15. Discount (if any) — must be before tax for it to reduce taxable value

**Footer:**
16. Total invoice value (words AND figures for amounts ≥ ₹1 lakh)
17. **Reverse charge** indicator (Yes/No) — mostly No for a café
18. **Signature** — physical or digital; for e-invoice the IRN replaces the signature
19. (If e-invoice applicable) **IRN** + **QR code**
20. **FSSAI licence number** (Food Safety regulation — separate from GST but mandatory)

### Bill of Supply (for composition scheme)
Same fields **minus tax-rate and tax-amount columns**, and the document must say **"Bill of Supply, Not eligible to charge tax on supplies"** at the top.

### Invoice numbering — implementation rules
- Series like `D/2026-27/00001` works (`/` is allowed). Restart numbering on **1 April** each financial year.
- One sequence per "place of business" (a multi-branch café needs a unique series per branch — usually prefix with branch code: `D/MN/2026-27/00001`).
- **No gaps** — even cancelled/voided invoices must be retained as cancelled, never deleted. Your sequence generator must be transactional.

## 8. E-invoicing (electronic invoice on the IRP)

Currently mandatory for any taxpayer whose **aggregate turnover crossed ₹5 crore in any FY since 2017-18.** Once you're in, you're always in — even if turnover drops later.

| Threshold (since 2017-18) | E-invoice required | Reporting window |
|---|---|---|
| < ₹5 cr | No | — |
| ₹5 cr – ₹10 cr | Yes | No deadline (best practice within 24h) |
| ≥ ₹10 cr | Yes | **Within 30 days of invoice date** (since April 2025) |

D Company is probably under ₹5 cr in year one — no e-invoicing. But **build the system as if it will be mandatory** because: (a) the threshold is widely expected to drop to ₹3 cr or ₹2 cr in the next year or two; (b) e-invoicing also unlocks **e-way bill** automation and **GSTR-1 autofill**, which save real time.

**E-invoice flow:**
1. Cashier creates invoice in our POS.
2. Our system pushes JSON to the Invoice Registration Portal (IRP).
3. IRP returns: **IRN** (32-char hash), **signed QR code**, **acknowledgement number**.
4. Our system stamps IRN + QR on the printed/PDF invoice.
5. GSTR-1 of that period autofills from the IRP data.

If the IRP is offline, you have 24 hours to push. The customer still gets the original invoice; the IRN is added when the IRP comes back.

## 9. E-way bill (for goods movement)

Mandatory for inter-state movement of goods > ₹50,000. For a café, this almost never triggers — except when:
- Receiving stock from outside Kerala (supplier generates it)
- Sending the coffee machine for repair across a state line
- Bulk catering equipment movement for events outside Kerala

The POS doesn't need to generate e-way bills; the inventory module just needs to **accept and store the e-way bill number** that comes with incoming GRNs.

## 10. Returns calendar (regular taxpayer)

| Return | What | Frequency | Due | Notes |
|---|---|---|---|---|
| **GSTR-1** | Outward supplies (line-level) | Monthly | 11th of next month | QRMP if turnover ≤ ₹5cr → quarterly |
| **GSTR-3B** | Summary + tax payment | Monthly | 20th of next month | QRMP → quarterly, but tax paid monthly via PMT-06 |
| **GSTR-9** | Annual return | Annual | 31 Dec following FY | Mandatory if turnover > ₹2 cr |
| **GSTR-9C** | Reconciliation statement | Annual | Same as 9 | Only if turnover > ₹5 cr |
| **GSTR-4** | Composition annual | Annual | 30 April | Composition only |
| **CMP-08** | Composition quarterly | Quarterly | 18th of month after quarter | Composition only |

QRMP (Quarterly Return Monthly Payment) scheme: lets businesses with turnover ≤ ₹5 cr file GSTR-1 and 3B quarterly while still paying tax monthly via challan. **D Company is QRMP-eligible** in year one — opt in for sanity.

## 11. Reverse charge mechanism (RCM)

In a few cases, the *recipient* pays GST instead of the supplier. Relevant for a café:

- **Renting a commercial property from an unregistered landlord** — café pays 18% under RCM. (If landlord IS registered, no RCM.)
- **GTA (Goods Transport Agency)** services — 5% RCM if GTA hasn't opted to charge forward.
- **Lawyer / Director sitting fees** — RCM, but usually n/a for a small café.
- **Security services from non-body-corporate** — RCM.

The journal entries are different: you debit an "Expense" and credit "Output IGST/CGST/SGST under RCM" — then claim it back as ITC. Two journal lines for what looks like one expense.

## 12. TDS (Tax Deducted at Source) — when D Company is the deductor

Once turnover crosses certain limits (FY 2025-26), D Company must deduct TDS on:

| Payment | Rate | Threshold (per FY) | Section |
|---|---|---|---|
| Salary | as per slab | Any | 192 |
| Rent (commercial property) | 10% | ₹2.4 lakh | 194-I |
| Professional fees (CA, designer) | 10% | ₹30,000 | 194J |
| Contract payment | 1% (indiv) / 2% (other) | ₹30k per single bill or ₹1L total | 194C |
| Commission | 5% | ₹15,000 | 194H |

TDS deducted goes to the IT department via challan; supplier sees the deduction in their Form 26AS. **Quarterly TDS return (24Q/26Q) due 31st of the month after quarter.**

## 13. TCS (Tax Collected at Source) — when e-commerce is involved

Aggregators (Zomato/Swiggy) collect **1% TCS** on the net taxable value of supplies through them and remit it. D Company sees this as a TCS credit it can claim against output GST via GSTR-2A reconciliation.

## 14. Kerala state-specific

### Kerala Flood Cess (KFC) — DON'T add it
Levied 1 August 2019 at 1% for two years. **Expired on 31 July 2021**. Currently inactive. The schema and POS should NOT compute KFC. We add a **flag-able state cess module** so we can switch one back on quickly if the state ever does another emergency levy — but no field on the bill for now.

### Kerala Professional Tax
Levied by local body (Panchayat / Municipality / Corporation) on every employee earning > ₹12,000 per half-year. Slabs (FY 2026-27, applicable in most Kerala local bodies):

| Half-year salary | Half-year PT |
|---|---|
| Up to ₹11,999 | ₹0 |
| ₹12,000 – ₹17,999 | ₹120 |
| ₹18,000 – ₹29,999 | ₹180 |
| ₹30,000 – ₹44,999 | ₹300 |
| ₹45,000 – ₹59,999 | ₹450 |
| ₹60,000 – ₹74,999 | ₹600 |
| ₹75,000 – ₹99,999 | ₹750 |
| ₹1,00,000 – ₹1,24,999 | ₹1,000 |
| ≥ ₹1,25,000 | ₹1,250 |

**Cap:** ₹2,500 per employee per year (₹1,250 × 2 halves). Employer deducts from salary and remits to local body by **31 August** (1st half) and **28/29 February** (2nd half). Penalty: 1%/month interest + up to 50% penalty.

The ERP **payroll module** should compute PT per employee per half-year. The **finance module** should book it as a liability when accrued and clear it when paid.

### Kerala Shops & Commercial Establishments Act
- Mandatory registration within 60 days of starting business.
- Weekly off + max working hours rules.
- The ERP doesn't need to track this directly, but **attendance + payroll** should warn if anyone goes over 48 hrs/week.

### Local body trade licence
- Issued by Kochi / Thiruvananthapuram / Kozhikode / etc. corporation depending on location.
- Annual renewal.
- Licence number should be printed on the bill alongside FSSAI.

### Kerala excise (if you ever serve alcohol)
- Kerala has **state-controlled** alcohol distribution via **Kerala State Beverages Corporation (Bevco)** — most cafés cannot directly serve liquor without a bar licence.
- Liquor is **outside GST** — VAT applies (currently 247% on Indian-made foreign liquor, the highest in India).
- A café typically can't get a bar licence without specific zoning + premises requirements + huge security deposit. **For V1, the ERP assumes D Company is alcohol-free.** If you later get a permit, alcohol becomes a completely separate module (different tax engine, different stock management, different licence display).

## 15. FSSAI — food safety, MANDATORY for every café

**14-digit FSSAI licence number** issued by the Food Safety and Standards Authority of India. Must be:

- **Displayed prominently** at the premises (entrance, billing counter, kitchen entry).
- **Printed on every bill and invoice.**
- **On the menu**, packaging, online listings (Zomato/Swiggy/website).

Categories (annual turnover thresholds revised April 2026):

| Category | Annual turnover | Fee | Issued in |
|---|---|---|---|
| Basic Registration | ≤ ₹12 lakh | ₹100/yr | ~7 days |
| State Licence | ₹12 lakh – ₹20 cr | ₹2,000–5,000/yr | 20-45 days (after inspection) |
| Central Licence | > ₹20 cr OR import/export OR multi-state | ₹7,500/yr | 20-45 days (after inspection) |

**Penalty for not displaying:** up to ₹2 lakh; for operating without a licence: up to ₹5 lakh + imprisonment.

The ERP `companies` table needs an `fssai_license_no` column. Receipt rendering must always include it.

## 16. ESI & EPF — staff statutory benefits

| Scheme | Applicability trigger | Employer share | Employee share |
|---|---|---|---|
| **EPF** (Provident Fund) | ≥ 20 employees | 12% of basic | 12% of basic |
| **EPS** (Pension, part of EPF) | within EPF | 8.33% of basic capped at ₹15k | — |
| **ESI** (Insurance) | ≥ 10 employees (in notified areas) AND wages ≤ ₹21k | 3.25% of gross | 0.75% of gross |
| **Gratuity** | ≥ 10 employees, after 5 years service | 15 days last drawn ÷ 26 × yrs | — |

Both EPF and ESI have **monthly returns** (ECR for EPF, monthly contribution for ESI). The ERP payroll module should produce both files in the prescribed format.

## 17. Payment methods — what the POS must support

| Method | Settlement | Charge | Notes |
|---|---|---|---|
| **Cash** | Instant | 0% | Most common still in tier-2 Kerala |
| **UPI** (Google Pay, PhonePe, Paytm) | Same day | 0% for ≤ ₹2k P2M; 0.5%-1.1% MDR above on PoS | The dominant digital method in India |
| **UPI QR (Static / Dynamic)** | Same day | Same as above | Static = one QR for all txns; Dynamic = per-bill QR with amount baked in |
| **BharatQR** | T+1 | 0.5%-1% MDR | Bank-network QR, less common than UPI now |
| **Debit / Credit card** | T+1 to T+2 | 0.4%-2.5% MDR | RuPay debit on UPI is free; Visa/MC ~2% |
| **Wallets** (Paytm, Mobikwik) | Same day | 0.5-1% | Mostly subsumed by UPI |
| **Cards via Razorpay / PayU / Pine Labs** | T+1 | 1.9-2.5% | Aggregator route, easier integration |

**For D Company V1:** start with Cash + UPI (static QR + dynamic QR) + Card. Wire UPI dynamic via a payment gateway like **Razorpay / Cashfree** — they hand back a webhook when the customer pays, so the POS can auto-complete the order without the cashier waiting.

UPI specifically:
- The merchant QR encodes a **VPA** (Virtual Payment Address, e.g. `dcompany@hdfcbank`)
- Per-bill **dynamic QR** also encodes amount + reference → cashier doesn't have to verify
- For amounts ≤ ₹2,000 person-to-merchant, **MDR is zero** by RBI mandate

## 18. Rounding

Round the **invoice total** to the nearest rupee. Show the round-off (max ±₹0.50) as a separate line. **Do not round individual line items** — that breaks the auditor's reconciliation between `sum(line.total) + tax = invoice.total`. CGST and SGST must each still balance their half.

## 19. Currency, dates, and i18n

- Default currency: **INR** (`₹`, ISO `INR`)
- Minor unit: **100 paise** per rupee
- Date format on bills: **DD/MM/YYYY** (or `DD-Mon-YYYY` like `20-May-2026`)
- Time: 12-hour with AM/PM is more readable for cashiers
- Address line allows Malayalam — store UTF-8, render with system fonts
- Number format: Indian numbering (`1,00,000` not `100,000`) for amounts ≥ ₹1 lakh

## 20. State codes (for GSTIN and place of supply)

GSTIN format: `[StateCode][PAN][EntityCode][Z][Checksum]` — 15 chars total. Kerala state code = **32**. Examples:

- A Kerala individual: `32ABCDE1234F1Z5`
- Place of supply on every Kerala-resident order = `32-Kerala`
- If a customer orders for delivery to Chennai: place of supply = `33-Tamil Nadu` → use IGST instead of CGST+SGST.

## 21. Discounts — the boring rule that traps people

Two kinds of discount:

| Kind | When | Effect on GST |
|---|---|---|
| **Pre-supply discount** (shown on invoice) | At time of sale | Reduces taxable value. Tax computed on `price − discount`. |
| **Post-supply discount** (credit note later) | After sale, e.g. for volume rebate | Must be linked to original invoice + agreed in contract; otherwise it does NOT reduce taxable value. |

So a "₹50 off coupon" applied at the till = pre-supply, reduces taxable value, no problem. A "buy 100 coffees this month, get 10% back" rebate next month = post-supply credit note, must reference the original invoices.

## 22. Service charge — voluntary only

The **CCPA (Central Consumer Protection Authority)** guidelines of **July 2022** (and the Delhi High Court ruling of January 2025 that mostly upheld them) say:

- Service charge **cannot be added automatically** to the bill.
- Cannot be levied as a percentage or as a condition of entry.
- Cannot be added to GST and taxed.
- Customer may choose to add it; the bill must show it as **voluntary tip**, paid AFTER tax.

The POS should support **adding a tip post-tax** as a separate line, never auto-compute a "service charge %". The tip flows to a separate ledger account (`Liability — Tips Payable to Staff`).

## 23. Concrete bill layout — D Company café

```
                            D COMPANY
                  No. 12, MG Road, Kochi, Kerala
                          GSTIN: 32ABCDE1234F1Z5
                       FSSAI Lic: 12345678901234
                  TIN/Trade Lic: KMC/2026/CAFE/0042

  Tax Invoice                                  No.  D/MN/2026-27/00231
                                               Date 20-May-2026  18:42
                                               Table T-04 / Dine-in
                                               Cashier: Anish K.

  --------------------------------------------------------------------
  HSN  Item                          Qty   Rate    Disc   Taxable Amt
  --------------------------------------------------------------------
  9963 Cappuccino                    2     180.00  0.00    342.86
  9963 Chocolate Croissant           1     150.00  0.00    142.86
  9963 Loaded Fries                  1     250.00  0.00    238.10
  --------------------------------------------------------------------
                                          Subtotal (taxable)   723.82

                                          CGST @ 2.5%           18.10
                                          SGST @ 2.5%           18.10
                                          --------                ---
                                          Total tax             36.20

                                          Round off             (0.02)
                                          --------                ---
                                          GRAND TOTAL          760.00
  --------------------------------------------------------------------

  Place of supply: 32-Kerala
  Payment: UPI @ dcompany@hdfcbank   Ref: 645829137245

  Amount in words: Seven Hundred Sixty Rupees Only.

  This is a computer-generated invoice; signature not required.
  Thank you for visiting D Company. Tips are voluntary.
```

Notes on the layout:
- Menu price already INCLUDES tax (`180.00` cappuccino is what the customer reads on the menu and what they expect to pay). The bill works backwards: `taxable = price ÷ 1.05`. Storing both inclusive and exclusive price avoids confusion.
- HSN/SAC: `9963` (restaurant service) for everything sold at the table. The recipe ingredients have their own HSN for inventory but it doesn't show on the customer bill.
- Round-off shown explicitly so the cashier and the auditor can both reconcile.
- "Computer-generated" line replaces the wet signature.

For a **gaming session** the layout is similar but:
- SAC `999692` (amusement & recreation) at 18%.
- Line description includes start/end time + station code (`PS5-03  19:00 → 21:30  2h 30m`).
- Rate per hour displayed.
- Tax breakdown: 9% CGST + 9% SGST.

## 24. Audit trail requirements

GST law requires **6-year retention** of all invoices and supporting documents from the end of the relevant financial year. The audit_log table must:

- Never delete or overwrite entries.
- Be partitioned by month (already planned in our `audit_log` design).
- Be backed up off-site monthly.
- Record the actor for every invoice issuance, modification, void, refund.

## 25. The Kerala-specific bill, in plain English

For 99% of D Company café transactions, the customer sees:

1. **GST: 5%** (split 2.5% CGST + 2.5% SGST) — taxes restaurant supply on the bill, paid to govt.
2. **GST: 18%** (split 9% CGST + 9% SGST) — taxes gaming session, paid to govt.
3. **No KFC** — flood cess expired.
4. **No service charge** — voluntary tip line only.
5. **FSSAI + trade licence numbers** displayed at the bottom.
6. **Round-off to nearest rupee**, shown as a line.
7. **UPI / Card / Cash** payment, with reference number captured if digital.

That's the whole picture for the storefront. Statutory filings (GSTR-1, 3B, 9, professional tax challan, TDS) happen in the back-office monthly.

---

## Sources verified (May 2026)

GST rates on restaurants:
- [GST on Restaurants in India 2026 — Complete Tax Guide (DineOpen)](https://www.dineopen.com/blog/gst-on-restaurant-india-guide.html)
- [GST on Food and Restaurants 2026 — Complete Rate Guide (MyGSTIndia)](https://mygstindia.in/blog/gst-food-restaurants-2026.html)
- [Restaurant GST Rates 2026: 5% vs 18% Rules Explained (Restoyantra)](https://restoyantra.com/blog/restaurant-gst-rates-india-5-vs-18)

E-invoicing thresholds:
- [E-Invoice Limit in India: Updated Guide 2026 (Gimbooks)](https://www.gimbooks.com/blog/e-invoice-limit-in-india/)
- [What is e-Invoicing Under GST? (ClearTax)](https://cleartax.in/s/e-invoicing-gst)

Online gaming GST + 2026 Act:
- [Promotion and Regulation of Online Gaming Act, 2025 — guide (A2Z Taxcorp)](https://a2ztaxcorp.net/indias-online-gaming-revolution-a-complete-guide-to-the-promotion-and-regulation-of-online-gaming-w-e-f-may-1-2026/)
- [Online Gaming Tax in India 2025 (TaxRoutine)](https://taxroutine.com/online-gaming-tax-india-guide-2025/)

Kerala Flood Cess:
- [Kerala Flood Cess — Applicability & Rate (IndiaFilings)](https://www.indiafilings.com/learn/kerala-flood-cess/)
- [Kerala Flood Cess in GST — all you need to know (Sleekbill)](https://sleekbill.in/kerala-flood-cess/)

Invoice format & HSN:
- [GST Invoice Format 2026 — 16 Mandatory Fields (RedPulse)](https://redpulsesoftware.in/blog/gst-invoice-format-guide-2026)
- [HSN Code on GST Invoice (IndiaFilings)](https://www.indiafilings.com/learn/hsn-code-invoice)
- [GST Invoice: Format, Rules, Types and Mandatory Details (ClearTax)](https://cleartax.in/s/gst-invoice)

Kerala Professional Tax:
- [Professional Tax Kerala (ClearTax)](https://cleartax.in/s/professional-tax-kerala)
- [Kerala Professional Tax Slabs FY 2026-27 (EligibilityTools)](https://eligibilitytools.in/tools/professional-tax/kerala/)

FSSAI licence:
- [FSSAI License for Restaurants — Complete Registration Guide 2026 (DineOpen)](https://www.dineopen.com/blog/fssai-license-restaurant-complete-guide.html)
- [FSSAI Registration Process (ClearTax)](https://cleartax.in/s/fssai-registration)

Composition scheme:
- [GST Composition Scheme: Rules, Turnover Limit, Rate, Benefits (ClearTax)](https://cleartax.in/s/gst-composition-scheme)
- [GST Composition Scheme for Restaurants (IndiaFilings)](https://www.indiafilings.com/learn/gst-composition-scheme-restaurants)
