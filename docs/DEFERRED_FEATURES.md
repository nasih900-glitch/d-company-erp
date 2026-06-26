# Deferred features — need your input before I build them

Everything below is designed and partially scaffolded in the database — but each one
needs a credential, decision, or service signup from you before I can wire it end-to-end.
When you're ready for any of these, paste me the requested info and I'll have it live
within a session.

---

## 1. WhatsApp alerts (birthday wishes, low-stock, partner pings)

**Needs:** A Meta WhatsApp Business API account OR a Twilio account.

- **Cheapest path:** Twilio (`api.twilio.com/2010-04-01/Accounts/{SID}/Messages.json`) — ~₹0.40 per message, instant signup with UK debit card.
- **Cleaner path:** Meta WhatsApp Business — free messages within 24-hour window, ~₹0.50 outside it. Needs a Facebook Business Manager, takes 1-3 days to approve.

Paste me: **Twilio Account SID + Auth Token + a verified WhatsApp Sender number** OR **WhatsApp Business API token + phone-number-id**.

What I'll build immediately: ingredient low-stock → WhatsApp Nasih + Shemeer + Rafi. Customer birthday → WhatsApp them a "free coffee on us" coupon.

---

## 2. Daily P&L email at 8 AM IST to all 3 partners

**Needs:** SMTP credentials.

Pick one:
- **Resend** (resend.com) — simplest, 3000 free emails/month, just an API key
- **Mailgun** — 5000 free/month, regional support including India
- **Gmail App Password** — free if you don't mind it coming from a Gmail address
- **Amazon SES** — cheapest at scale

Paste me: **`SMTP_HOST`, `SMTP_PORT`, `SMTP_USER`, `SMTP_PASSWORD`, `FROM_EMAIL`**.

What I'll build: at 8am IST every day, an HTML email lands in your three inboxes with yesterday's revenue, expenses, net profit, and a one-tap link to the full Reports page.

---

## 3. Kitchen Display System (KDS) — real-time push to kitchen iPad

**Needs:** A decision from you — push via WebSocket or polling?

- **WebSocket** (faster, more "magic"): needs ASGI WebSocket route, kitchen iPad keeps connection open.
- **Polling** (simpler, robust): kitchen iPad checks `/orders?since=...` every 3s.

What I'll build: a `/kitchen` route, no shell, full-screen tiles per order with "Started → Plating → Ready" state buttons; server gets a push when a ticket goes Ready.

I recommend polling — simpler, survives flaky wifi at the café. **Reply "polling" or "websocket"** and I'll build it.

---

## 4. Combo deals / Happy hour pricing

**Needs:** Your actual combo deals + happy-hour rules.

The DB column `Order.discount_minor` already exists; pricing engine accepts discounts. Just need the rule schema:

- Combo: "PS5 1hr (₹149) + 2 drinks @ ₹100 each → ₹399, save ₹50"
- Happy hour: "All food −20% Mon-Thu 3-6pm"
- BOGO: "Buy 2 mocktails, get 3rd free"

Send me your top 5 combos and 2-3 happy-hour rules and I'll wire them into the cart with one-tap apply.

---

## 5. Auto-reconciliation with your existing Drive matcher

**Needs:** I need to read your existing `matchByDateAndAmountPrecision()` Apps Script to understand the format it expects in the Transactions tab.

Paste me the full text of that script (from the Apps Script editor) and I'll add a one-way bridge: ERP expenses → written into your Transactions tab in the format your matcher expects. Then your existing receipt-matching automation already covers the bill-attachment side.

---

## 6. Payment gateway for online bookings

**Needs:** Razorpay account (most common in India).

For PS5 / hookah pre-bookings from the public menu page, customer pays ₹100 deposit upfront → confirmed seat.

Paste me: **Razorpay Key ID + Key Secret**.

What I'll build: deposit checkout on `/menu` for PS5/hookah/event slots, refund logic if customer cancels >2hrs ahead.

---

## 7. Power BI / Looker direct connector

**Needs:** A decision — do you actually use Power BI, or is CSV good enough?

**Right now:** I've shipped `GET /api/v1/reports/gstr1.csv?yyyy_mm=2026-06` (and gstr3b.csv). You can hit those URLs from any browser tab while logged in and they download as CSV ready to upload to gst.gov.in.

**If you want Power BI direct connection:** I'll add an OAuth-protected `/api/v1/exports/dataset?from=...&to=...` returning JSON in the shape Power BI expects, plus a tiny user-readable instruction page.

Reply "yes Power BI" if you actually use it, or "CSV is fine" if not.

---

## 8. Multi-branch UI (Calicut, Trivandrum, etc.)

**Needs:** You opening a second branch.

The database is fully multi-branch already (every model is scoped by branch_id). The UI defaults to your single branch for simplicity. When you sign the lease on the second branch, ping me and I'll add a branch picker in the top bar — takes about an hour.

---

## 9. Membership / subscription model

**Needs:** Razorpay (see #6) + your subscription tiers.

Example tiers I could build:
- **D Club Silver** ₹999/month — unlimited PS5 weekdays, 10% off food
- **D Club Gold** ₹1999/month — unlimited everything weekdays, 15% off food, 1 free event/month
- **D Club Premium** ₹3999/month — unlimited everything, 25% off food, priority booking

Send me your tier names + pricing + perks and I'll build it.

---

## Summary — what's ready vs deferred

| Feature | Status |
|---|---|
| Customer DB + loyalty points | ✓ live |
| Recipe-driven inventory deduction | ✓ live |
| GSTR-1 / GSTR-3B CSV export | ✓ live |
| Public menu page (QR-ready) | ✓ live |
| Hookah revenue stream | ✓ live |
| WhatsApp alerts | ⚠ needs Twilio/Meta creds |
| Daily P&L email | ⚠ needs SMTP creds |
| KDS | ⚠ needs your push-vs-poll decision |
| Combo / happy hour | ⚠ needs your rule list |
| Drive-matcher bridge | ⚠ needs your Apps Script source |
| Online deposits | ⚠ needs Razorpay |
| Power BI direct | ⚠ needs your "yes/no" |
| Multi-branch UI | ⚠ when you open branch 2 |
| Memberships | ⚠ needs Razorpay + tier defs |
