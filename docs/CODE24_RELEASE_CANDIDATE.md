# D Company ERP 3.1.13 (code 24) audit candidate

Historical candidate: the immutable `v3.1.13` release failed before signing.
It is superseded by [Code 25 / 3.1.14](CODE25_RELEASE_CANDIDATE.md), which retains
this audit scope and adds the verified rejected-start dialog correction.
The evidence below remains Code 24 evidence, not a signed Code 25 release claim.

This was an **unsigned** candidate, not a declaration of signing,
deployment, activation, approval, partner installability or Redmi readiness. It
supersedes unsigned Code 23 without rewriting the immutable `v3.1.12` tag. Code
21 (`3.1.10`) remains the immutable signed direct-channel predecessor for the
required in-place upgrade proof, and minimum-compatible client code 8 remains
unchanged. The coordinated candidate identity is `3.1.13` / code `24`, with
database migrations through `0071`.

## Corrections under verification

- Android preserves original Gaming, direct POS, shift, expense, asset and
  partner-capital commands when a response or local confirmation is uncertain.
  HTTP 408 is not a definitive rejection. Original payment amounts, shift
  counts, captured stop times and idempotency keys are retained.
- Confirmed Finance writes are saved atomically with their server receipt;
  a later failed list refresh cannot turn a successful write into a rejection.
- An incomplete partner ownership configuration no longer blocks valid P&L
  and metrics. Its allocation-only warning replaces any old allocation in the
  same cache transaction; it is never normalised into invented shares or profit.
- Invalid server session timestamps fail safely without inventing a new start
  time or silently dropping a configured deadline.
- Covered ordinary diagnostics storage/scheduling failures are isolated from
  app operations. This is not immunity to fatal VM or platform failures.
  Cancellation and retained crash evidence are preserved.
- Web realtime callbacks cannot retire a newer connection. An authenticated
  connection acknowledgement triggers an authoritative refresh.
- Older Finance responses cannot overwrite newer results. Refresh failures
  retain visibly stale last-verified figures and provide a retry action.
- The web dashboard defaults to the server's shop business day, not the
  browser's local date. Missing product costs are explicitly provisional and
  cannot produce a reassuring profit-confidence indicator.
- Migration 0067 fixes table-specific Finance insert validation. Concurrent
  tip payouts serialize against the same payable balance; relevant refunds use
  the same company lock. Future or timezone-free payout timestamps are rejected.
- Migration 0068 adds authoritative, reasoned Gaming pause/resume receipts.
  Migration 0069 marks the captured-opening protocol revision and privately
  records the originating Android installation identity. Any authorised staff
  on that installation can close the shift; Code 21 retains its exact causal-key
  fallback. Ordinary browser, reinstall, and different-device closes fail safe
  so unsynchronised work cannot be stranded. A protected audit owner has a
  separate reasoned, acknowledged, idempotent, audited web recovery flow after
  quarantining the original installation;
  migration 0070 stores Gaming Centre product-category eligibility independently
  from editable display names; and migration 0071 safely binds already-deployed
  Code 21 cash-expense outbox rows to the exact open shift drawer with a durable
  replay receipt. Code 24 continues to offer only non-cash Finance expenses.
- Multi-query financial reports use a single read-only consistent database
  snapshot, without changing financial write transaction isolation.
- Product setup retains empty categories so their first item can be created.
  Gaming Centre category guidance is explicit without exposing hidden cafe
  products. Payment completion clears obsolete in-flight warnings. Migration
  0070 converts the previously name-derived Gaming Centre category decision to
  a stored classification, so later display-name edits do not silently remove
  an already classified category from new-sale surfaces.
- Receipt and Finance timestamp labels are explicitly in the shop's IST zone.
  Station timer reads are localised to the timer body while status/overtime
  changes still update the action card.

The detailed evidence, measured physical-device limits and remaining activation
gates are in [the production audit](CODE24_PRODUCTION_AUDIT.md).

## Redmi diagnostic capability

After the matching backend and app are installed, privacy-filtered crash, ANR,
API-failure and sync-stall evidence can be delivered to web System Health.
Diagnostic UUIDs are durable and retried without duplicate incidents. Reports
captured offline wait for an authenticated connection. OS exit/crash evidence
may require the next healthy app launch. Pre-login evidence is quarantined
when ownership cannot be proved.

This is passive monitoring, not an automatic whole-app financial test, screen
recording, or proof that flicker and pricing are correct. It does not create
sales, close shifts or manipulate real data to test itself. A protected owner
can inspect System Health; employees can use Help for context and written
feedback. Physical Redmi acceptance remains separate from cloud-device tests.

## Deployment and delivery gates

1. Complete final source, migration, backend, web, Android and isolated
   cross-device operational tests; reconcile receipts, payments and shift cash.
2. Preserve the old tablet's pending outbox and reconcile it. Never uninstall,
   clear app storage or delete records to make a queue appear empty.
3. Use the normal coordinated server installer with maintenance checks,
   quiesced backup, restoration proof, migration and rollback readiness.
4. Build an immutable signed APK with the existing approved signing lineage.
   Verify package, code, version, byte size, SHA-256 and hosted HTTPS bytes.
5. Prove an in-place upgrade from the signed Code 21 predecessor with preserved
   data, then register and activate
   the owner-authorized server update. Android installation still needs consent.
6. Obtain authenticated target-Redmi smoke, offline/restart and alarm evidence.

The previously waiting Code 23 signing run was cancelled after new audit
defects were reproduced. No APK from that retired candidate should be activated.
