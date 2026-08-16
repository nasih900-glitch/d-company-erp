# Web ERP end-to-end run — 2026-08-16, against live production

Ran the real business flow end to end: seeded a menu, created a member, opened a
shift, took food and gaming sales, earned and redeemed D Coins, applied a manual
discount, then checked whether every screen agreed about the same money.

Driven through the API from the server itself (`/root/e2e/*.sh`, state in
`state.env` so a stopped run resumes). **Not** clicked through the React pages —
this session's browser policy blocks the host, so the UI itself was not visually
exercised.

---

## Result: the money reconciles exactly

Four sales were rung up, then compared against every screen that reports them.

| | Rang up | Reports/daily | Analytics |
| --- | --- | --- | --- |
| Orders | 4 | 4 | 4 |
| Total collected | ₹314.00 | 31400 | 31400 |
| Cash | ₹314.00 | 31400 | — |
| Food (gross) | ₹120.00 | 12000 | 12000 |
| Gaming | ₹204.00 | 20400 | 20400 |
| Discounts + coins | ₹10.00 | 1000 | 1000 |
| Avg ticket | ₹78.50 | 7850 | 7850 |

Reports and Analytics agree with each other and with reality, to the paisa.

### Flows verified

- **Order maths** — ₹20 + ₹40 = ₹60, tax 0 (tax-inclusive pricing, 0% rate).
- **Drawer** — float ₹1000 + ₹314 cash = ₹1314 expected; closed at **variance 0**.
- **Gaming billing** — 65s session → 2 billable minutes (ceiling), ₹120/hr → ₹4.00.
- **D Coins earned** — ₹200 gaming bill → **40 coins**, exactly 2 per ₹10.
- **D Coins redeemed** — 40 coins → **₹4.00 off**, exactly 10 coins per ₹1.
- **Manual discount** — ₹60 − ₹4 coins − ₹6 discount = **₹50.00 due**.
- **Points are gaming-only** — a food/drink sale earns nothing. Confirmed
  deliberate in `_compute_points_with_multiplier`: "the points program is a
  gaming rewards ladder, not a general discount".
- **Refunds** — all four invoices refunded, ₹314.00 returned, drawer balanced.

---

## Findings

### 1. No way to delete a customer — MEDIUM
`DELETE /customers/{id}` returns **405 Method Not Allowed**. There is no delete
or anonymise endpoint. A customer added by mistake, a duplicate, or someone who
asks for their data to be removed cannot be taken out of the system from either
the web app or the API.

Leftover from this run: one member `ZZ E2E Member` (phone 9999900001) that could
not be removed.

### 2. Finance AOV appears to count refunded orders — MEDIUM, unconfirmed
`/finance/metrics` reported `orders_count: 6` and `aov_minor: 6733` for the
month, when only 4 orders were taken. The extra two are trial sales that were
**refunded** earlier the same day.

6733 × 6 = ₹404 — which is the ₹314 actually collected plus the ₹90 refunded. So
refunded orders look like they are still counted in both the order count and the
average-ticket numerator.

Not yet proven in code; `finance/router.py:1639,1653-1654` reads
`report.avg_ticket_minor` and `metrics.orders_count` from two different sources,
which is where to start. Note a related fix was already made for the Growth tab
(refund-inflated revenue), so this may be the same bug in a place that was
missed.

### 3. A cash refund needs an open shift — LOW, probably correct
Refunding after the shift was closed fails with "cash refund requires exactly one
open shift for this terminal". That is defensible — cash comes out of a drawer,
so there must be a drawer — but it means an owner correcting yesterday's mistake
must open a shift first, and the message does not say so. Worth a line in the
error text.

---

## Not covered

The React pages themselves were never rendered or clicked. Everything above
tests the API the screens call, not the screens. Still outstanding: form
validation, error rendering, keyboard/tab behaviour, layout on a real display,
and the Kitchen/Tables/Inventory/Settings UIs.

Also untested: inventory depletion via recipes (no ingredients configured),
GSTR export, events/ticketing, OCR upload, reservations, and staff management.
