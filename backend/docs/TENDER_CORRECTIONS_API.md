# Build 51 tender corrections and receipt investigation

All paths below are relative to `/api/v1`. Monetary values are integer minor
units. These APIs preserve original payments, invoices, stock, loyalty and
recorded shift closing evidence. They do not verify a bank statement or count
a physical drawer. No production reconciliation is performed by a migration.

## Authority and safe command sequence

`finance.tender.correct` has the same protected-owner ceiling as Audit. Role
overrides, ordinary owners and co-owners cannot grant it. The current database
assignment must also contain the company's `super_owner` role; legacy signed
access flags alone are insufficient. Tenant and terminal/branch rules remain
in force. These commands require an online server connection.

1. `POST /finance/tender-corrections/unlock` with
   `{branch_id: UUID, scope: "payment" | "daily" | "history", password: string}`.
   Response: `{token: string, expires_in: 300}`. Keep this purpose-specific token
   in memory and send it as `X-Tender-Token`; never store it with offline work.
2. Prepare the chosen operation and show its exact returned source amounts,
   date, timezone, scope and evidence label. No money changes during prepare.
3. Persist one client action's `Idempotency-Key` (1–160 characters) and exact
   commit body before sending it. A retry after a lost response must keep both.
   Changing reviewed facts requires a fresh prepare and a new action key.
4. Display the immutable receipt returned by commit. On an uncertain network
   result, recover with the same action; do not infer that a timeout rolled back.

All payloads forbid extra fields. Reasons must contain 3–500 characters after
trimming; an optional `evidence_reference` is limited to 200 characters. The
password and unlock token are not stored in correction receipts or Audit.
Token authority, current actor status/role, authentication version and intended
scope/branch are checked again before replay and commit.

## Known original Cash leg to UPI

`POST /finance/tender-corrections/payment/prepare`:

```text
request: {payment_id: UUID}
response:
 payment_id, order_id, invoice_no, branch_id
 business_date: date, business_timezone: IANA name
 source_fingerprint: 64 lowercase hex characters
 amount_minor: integer
 original_method: "cash", effective_method: "upi"
 before_cash_minor, before_upi_minor, after_cash_minor, after_upi_minor
 total_change_minor: 0
 evidence_kind: "owner_attestation"
 original_shift_record_unchanged: true
```

`POST /finance/tender-corrections/payment/commit` takes
`{payment_id, source_fingerprint, reason, evidence_reference?}` and returns a
`TenderReceipt` with HTTP 201, including on an exact durable replay.

Only one complete, positive, original Cash payment leg on an issued paid bill
with a closed original shift is eligible. Partial legs, chains, arbitrary
rails, settled refunds and unresolved refund tasks are refused. An applied
unallocated day correction also blocks a later known-bill correction for that
day until an explicit allocation/replacement workflow exists. The current
business day is allowed for a genuinely closed known-payment source; future
business dates are refused.

## Unallocated daily Cash/UPI actuals

`POST /finance/tender-corrections/daily/prepare`:

```text
request: {branch_id: UUID, business_date: date}
response:
 branch_id, business_date, business_timezone, source_fingerprint
 recorded_cash_minor, recorded_upi_minor
 recorded_total_minor: Cash + UPI only
 attestation_scope: "cash_upi_only"
 unchanged_other_rails: {rail: recorded_net_minor}
 recorded_all_rails_total_minor: Cash + UPI + unchanged other rails
 source_allocation: "unallocated_owner_attestation"
 physical_cash_count: false
```

`POST /finance/tender-corrections/daily/commit` takes the prepare branch/date/
fingerprint plus `{actual_cash_minor, actual_upi_minor, reason,
evidence_reference?}`. Actuals are nonnegative strict integers. The server
derives the residual; the client cannot submit its own posting delta.

- Equal recorded and attested **Cash + UPI** totals, with negative Cash delta
  and matching positive UPI delta, produce `kind="applied"`.
- A different Cash + UPI total produces `kind="unresolved"`, zero posting
  deltas and `financial_effect=false`. It is review evidence, not extra revenue.
- A no-op, reverse transfer or negative actual is refused.

Card/QR/Wallet are shown as recorded and unchanged, not independently recounted.
For example, Cash 100 / UPI 100 / Card 500 may become Cash 80 / UPI 120 /
Card 500: attested rails remain 200 and the overall recorded total remains 700.
Do not compare attested Cash + UPI directly to the all-rails total. Unknown
rails, legacy unscoped refunds, open relevant shifts and current/future daily
business dates require review under the existing daily readiness contract.

## TenderReceipt and protected history

Every commit receipt has:

```text
id: UUID, receipt_reference: "TC-<UUID>" | "DR-<UUID>"
operation: payment_method_corrected | daily_collections_reconciled | daily_actuals_recorded
financial_effect: boolean
company_id, branch_id, business_date, business_timezone
recorded_by: retained UUID, recorded_at: server timestamp
reason, evidence_kind="owner_attestation", evidence_reference: string|null
source_fingerprint, idempotency_key
source_allocation: identified_payment | unallocated_owner_attestation
total_difference_minor: integer
```

Payment receipts additionally carry `payment_id`, `order_id`, `invoice_no`,
`shift_id`, `original_method`, `effective_method`, `amount_minor`,
`original_tendered_minor`, `original_change_minor` and `accounting_at`.
Daily receipts carry `kind`, `before_cash_minor`, `before_upi_minor`,
`actual_cash_minor`, `actual_upi_minor`, `cash_delta_minor`, `upi_delta_minor`
and `total_difference_minor`. Fields belonging to the other variant are null.

`GET /finance/tender-corrections/history?branch_id=<UUID>` requires a fresh
history-scope token. Optional `business_date` and `payment_id` filters narrow
the retained receipts. `limit` is 1–100 (default 50); this is a bounded
newest-first result by recorded timestamp/UUID, not a full-history export.
Filter by the business date or payment when investigating older evidence.

## Additive changes to existing financial readers

Payment/receipt leg DTOs retain all existing fields and add:

```text
original_method: string|null
original_tendered_minor, original_change_minor: integer|null
tender_correction_id: UUID|null
tender_correction_recorded_at: timestamp|null
```

`method` is the effective method even for older compatible clients. A corrected
UPI leg has null effective `tendered_minor` and `change_minor`. Its retained
original Cash tender/change remain in the additive fields. The same projection
is used for receipt history, payment recovery/replay, split-payment recovery,
orders, customer purchase history and payment classification in reports.

`GET /pos/shifts` retains the original `expected_minor`, `counted_minor`,
`variance_minor` and original Cash/UPI collection totals. It adds:

```text
collection_basis: "original_recorded_shift"
tender_correction_minor: integer
reconstructed_cash_collections_minor: integer|null
reconstructed_upi_collections_minor: integer|null
reconstructed_expected_minor: integer|null
```

Render **Recorded close** separately from **Reconstructed bill tender**.
Never silently replace the recorded drawer count or variance. Unallocated
day-level adjustments belong to the separate day view, not a cashier's shift.

Reports add a typed `tender_reclassifications` list. Each row contains `id`,
`branch_id`, `business_date`, `business_timezone`, `kind`, `cash_delta_minor`,
`upi_delta_minor`, `total_difference_minor`,
`basis="unallocated_owner_attestation"`, and `recorded_at`. Keep gross payment
receipts and refunds separate. Applied daily deltas change effective net
classification only; unresolved records change no totals or ledger lines.

Accounting keeps the original Payment-derived lines and adds one balanced
UPI debit/Cash credit pair in the original `Payment.paid_at` cohort. Daily
unallocated pairs are explicitly date-only period evidence. Sheet totals use
the original **Shift-opening business date**, which may differ from payment
date across midnight/month boundaries. The normal daily scanner/outbox signs
a replacement summary revision. Its existing readiness/unsupported-rail
rules remain intact; generated Sheet cells are never overwritten directly.

New original-mode refunds select the effective corrected rail. Recovery of a
previously withdrawn refund uses its retained original request/method, even
after generic replay-cache expiry; it does not start a new payout.

## Receipt search and investigation

`GET /pos/receipts` keeps `q`, `limit` (1–100) and opaque `cursor` and adds:

- `date_from`, `date_to`: inclusive invoice-issued **shop dates**.
- `station_id`: retained session station UUID.
- `amount_minor`: exact original invoice total in minor units.
- `method`: effective payment method (`cash`, `card`, `upi`, `qr`, `wallet`).
- `staff_user_id`: retained payment recorder UUID; unknown legacy recorders
  stay unknown. Combined method/staff filters match the same payment leg.

All supplied filters intersect. Unknown, duplicate or invalid filters are
rejected. The filtered cursor binds normalized query, every filter, shop
timezone, company and branch. Reusing it with different filters fails rather
than silently skipping records. Existing unfiltered/query-only cursor formats
remain compatible. The page adds `date_filter_basis` and `shop_timezone`.

`GET /pos/receipts/{order_id}/investigation` retains normal `pos.read` receipt
authorization and returns typed `ReceiptInvestigationRead`:

- `receipt`: the existing complete retained receipt DTO.
- `participant_intervals`: interval/session/identity UUIDs, retained optional
  customer/guest identity, joining/leaving timestamps, play-elapsed positions,
  timing sources and actors, optional settlement/line UUIDs, `charge_basis`.
- `participant_charges`: one settlement-line UUID per identity, session and
  settlement IDs, `play_elapsed_ms`, `interval_count`, `charge_minor`, `basis`.
  Rejoins link to this one total; do not duplicate its charge on each interval.
- `addons`: retained addon/client-line/session/item UUIDs, item-name snapshot,
  quantity, unit/line amounts, creation actor/time and void actor/time/reason.
- `audit_source_references`, `historical_evidence_note`, `invoice_date_basis`.

No current catalogue/profile is presented as historical proof. Missing legacy
evidence is labelled. Audit references do not grant Audit access: follow-up
Audit reads still require the separate protected owner unlock.

## Errors, races and rollback

Normal errors use `{error: {code, message, details}}`: 401 for missing/wrong/
expired/revoked confirmation, 403 for authority ceilings, 404 for out-of-scope
sources, 422 for invalid/ineligible requests and 409 for stale sources or a
changed durable action. Never retry a stale preview with guessed values.

The company-wide fence is deliberately conservative: an unrelated branch's
relevant write may invalidate a preview. PostgreSQL validates direct inserts
under the same fence and original parent lock order. A whole command retries
deadlock/serialization failures at most three times with a fresh transaction;
exhaustion returns 409 with `details.retryable=true`. Retry that same saved
action after reconnection or recover its receipt.

Receipts, semantic Audit and normal summary outbox entries commit together.
UPDATE/DELETE/TRUNCATE of retained receipts are refused. Destructive migration
downgrade waits for pending writers before checking evidence and refuses once
receipts exist. A rollback must retain correction-aware readers and evidence.
