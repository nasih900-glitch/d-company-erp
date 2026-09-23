# Code30.3 operational patch candidate

Code30.3 is an additive patch to the existing Code30 application. It does not
rebuild the ERP. Its coordinated identity is `v3.1.30`, Android installation
build `38`, Room schema `52`, and Alembic head `0082`. Its immutable base is
Code30.2 commit `3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01`
(`v3.1.29`, build `37`, Room `51`, Alembic `0078`).

The patch keeps the reviewed exact-candidate recovery for a tablet that retains
an old local Gaming overlay after the authoritative server session is gone. It
also adds atomic split tender, Web Gaming station-transfer parity, a
business-day shift view with exact opening and closing times, and protected
same-branch recovery for an eligible stale Android-origin shift on another
terminal. It also permits at most one second of tablet/server clock skew for a
durably captured shift opening while rejecting timestamps further in the
future. These are narrow changes to the existing system. The patch does not add
a generic station reset, database clear, or broad deletion control.

## Atomic payment and client parity

Web and Android may split one exact payable POS balance across two to five
unique rails: cash, UPI, card, QR and wallet. The backend commits all tender
legs, the one invoice, checkout-claim consumption, idempotent replay receipt
and Sheets outbox event in one transaction. Cash tender/change stays on the
cash leg and only the cash bill amount changes the expected drawer. Split mode
does not include tips. Android requires a live, verified bill and never queues a
new split collection offline; an interrupted confirmed settlement replays its
same durable plan and key.

Web Gaming now exposes the existing guarded station-transfer operation for an
active or paused session. Only an active, available station in the same branch
and of the same type is eligible. The server repeats the checks under locks and
preserves the original package, price, timer, customer, shift and staged items.

Web and Android group immutable drawer segments opened on the same IST date
into one business-day collection for presentation. The card shows the first
opening time and opener and the final confirmed close time and closer; raw
segments remain expandable and unchanged. A reopened day stays visibly open
until every segment has a confirmed close.

Ordinary shift reads and closes remain terminal-scoped. A separate protected
Web panel lists only open protocol-1 Android-origin shifts in the same company
and branch. A reviewer needs audit access plus `pos.shift.close`, must record
counted cash and a reason, and must attest that the origin tablet is
quarantined. The server preserves the normal blocker, cash, accounting,
idempotency and audit rules. Browser responses do not expose the raw Android
installation UUID. Ambiguous responses require exact idempotent replay; a
generic closed shift row is never accepted as proof that recovery ran.

## Recovery contract

A build-38 tablet that encounters a missing authoritative session reports its
exact immutable local start/stop evidence, station, branch, terminal,
installation, original actor, pricing snapshot, duration, amount, unresolved
child count, local evidence revision, and cryptographic candidate hash. The
backend accepts the report only when it matches the canonical Code30.1 cleanup
receipt and replay fences. Tenant, branch, terminal, installation, station and
session scope are enforced.

The owner Web ERP displays the exact branch, terminal, tablet installation and
build together with the station name/code, tablet-reported duration and amount,
and candidate hash. Approval is unavailable when duration or amount evidence is
missing. Approval records the approving owner, reason, idempotency key,
candidate hash and audit receipt. Approval does not directly alter the tablet.
The tablet must be online or reconnect on build `38` or later; its signed
request version must exactly match the persisted installation status. It
re-reports the same
candidate, receives the exact signed directive, retires only the matching local
overlay with a compare-and-set update, then acknowledges the result. Lost
acknowledgements are retryable. A confirmed acknowledgement is terminal.

If local evidence changes before application, the old reported or approved
record is preserved as `superseded` and a new revision must be reviewed.
Unresolved add-on or extension children block cleanup. Repeated invalid reports
are bounded and do not create an unlimited revision stream. The reconciliation
ledger is append-preserving and rejects deletion, identity changes and invalid
state transitions.

## Owner procedure

1. Keep normal business writes paused while investigating the affected station.
2. Update the affected tablet to exact signed build `38` and reconnect it to
   the production API. Web alone cannot rewrite an offline Room database.
3. In Web ERP, open **Gaming** and review the reported recovery candidate.
   Confirm the tablet installation and build, branch, terminal, station
   name/code, session, tablet-reported duration and amount, actor and candidate
   hash.
4. Approve only that exact candidate with a reason.
5. Keep the tablet connected until it applies and acknowledges the directive.
6. Refresh Web and tablet Gaming views. Confirm that the station is available,
   no active or payment-due overlay remains, and no order, charge, payment,
   receipt or customer record was created or removed by the cleanup.

There is deliberately no **clear all**, direct SQL, remote Room edit, or
unscoped force-available action. If no candidate appears, reconnect the exact
tablet first and inspect its installation diagnostics. A different candidate
hash or revision requires a fresh review.

Android distinguishes this tablet-only recovery from ordinary POS handoff.
Staff are told to finish work on the recorded terminal while its source shift
is open; protected-owner reconciliation is offered only after that source shift
has closed. The tablet exposes whether exact cleanup evidence is waiting for
owner review, waiting to apply, waiting to acknowledge, or failed to report, so
the workflow does not appear silently stuck.

## Preserved business behavior

Code30.3 preserves Code30.2 finance, receipt evidence, Google Sheets mirror,
POS, stock, reports, payments, refunds and updater behavior. Authorized users
may continue, stop, bill or complete another authorized user's Gaming or POS
work and may close the exact eligible branch/terminal shift. Original actor,
current actor and drawer/session attribution remain recorded. Refunds, voids,
privileged discounts, cleanup approval and release activation retain their
separate permissions.

Reward redemption and WhatsApp automation remain inactive. This patch does not
claim SMTP delivery; production SMTP is still provider-blocked and unverified.
It does not delete genuine business data or the historical Code30.2 evidence;
the separate owner-approved trial cleanup below removes only its pinned trial
cohort.

## Database changes

Alembic `0079` adds the tenant-scoped
`client_gaming_cleanup_reconciliation` ledger with unique current-candidate
constraints, revision checks, immutable evidence guards, valid-transition
guards and a downgrade refusal once evidence exists. It chains from `0078`.

Alembic `0080` permits the intermediate rows of one atomic split-payment
transaction while retaining the deferred exact-final-balance constraint at
commit. It chains from `0079`; partial and overpaid bundles cannot commit.

Alembic `0081` adds the immutable report-time app version and build identity to
the cleanup ledger without rewriting the published `0079` migration. It chains
from `0080`. An unexpected populated legacy `0079` ledger fails closed because
its historical report-time build cannot be reconstructed from a later device
heartbeat without fabricating audit evidence.

Alembic `0082` aligns the database with the captured-shift API's bounded
one-second clock-skew allowance. It does not rebase the tablet timestamp. A
timestamp beyond that limit is rejected, and downgrade refuses to rewrite an
immutable shift that used the allowance.

Room `52` adds cleanup evidence, directive and acknowledgement fields and the
`51 -> 52` migration. Upgrade acceptance must install build `38` over the
same-signed build `37` without uninstalling or clearing app data.

## Versioned trial-cleanup replay receipt

A later trial cleanup that deletes shifts must also record one v2 receipt in
`audit_log`, in the same transaction as the deletion, before the backend is
restarted. A shift's opening key lives only on the shift row, so no retained
idempotency receipt can absorb a delayed tablet replay once that row is gone.
Keyed `POST /pos/shifts/open` checks the Code30.1 receipt first (unchanged), then
every v2 receipt of the caller's company, before any shift lookup or write. It
refuses a fenced key regardless of payload with the same 409
`retired_cleanup_action` response as Code30.1. No migration is required.

The receipt row uses `action = 'verified_trial_cleanup'`,
`entity_type = 'TrialCleanupReceipt'`, `entity_id = request_id = cleanup_id`
(lower-case `[a-z0-9][a-z0-9.-]{7,63}`), a non-null actor and terminal, and
NULL `client_action_id`, `client_was_offline` and `synced_at`. These values
differ from every identity the Code30.2 post-cleanup installer gate treats as a
cleanup receipt, so that gate still sees exactly the one Code30.1 receipt. The
receipt's audit id must be greater than `28202`, which holds naturally in
production because the sequence is already past that. `before` holds exactly
`schema_revision` (four digits), `state_fingerprint` and `backup_sha256`.
`after` holds exactly `receipt_version = 2`, `cleanup_id`, `source_git_sha`,
`executor` (1–100 characters), `executed_at` (with time zone),
`deleted_counts` (non-negative integers, with `shifts` equal to the number of
deleted shift ids), `deleted_shift_ids` (sorted, unique, canonical UUIDs),
`replay_fence` and `evidence` (an object for invoices, Sheets event ids and
other records; the fence does not interpret it). Each fence entry has exactly
`action_type = 'shift_open'`, `action_key`, the shift's `opening_request_hash`,
`user_id`, `terminal_id` and `source_entity_id`. Entries are sorted by key,
unique, and each names a different deleted shift.

The fence fails closed: if any row carrying the v2 action or entity type is
malformed, or two share a cleanup id, every keyed shift opening in that company
is refused with HTTP 422 until an owner repairs the receipt. Nothing is
written. The cleanup runner must therefore validate the receipt before commit.

## One-time Code30.3 trial-data cleanup

`infra/scripts/cleanup-code30-3-trial-data.{sh,sql}` removes the owner-confirmed
trial cohort: the five shifts opened from 20 September 2026 and their 49
orders, 49 order lines, 47 payments, 61 Gaming sessions and 12 extensions,
pinned by exact ID and row hash from a fresh backup. It does not modify the
immutable Code30.1 cleanup. It keeps audit rows, idempotency receipts, the 47
delivered Sheets ledger rows (so they are never resent) and the invoice counter.
Invoice numbers `00024`–`00070` are retired and never reused; the next invoice is
`00071`. The owner deletes the matching 47 `ERP Mirror v1` rows by event ID,
which are listed in the receipt evidence.

Paid sales are protected by append-only triggers. The owner approved one
guarded exception. Inside a single SERIALIZABLE transaction the script
disables exactly `trg_payments_immutable`, `trg_orders_paid_source_integrity`,
`trg_order_lines_paid_source_integrity` and
`trg_gaming_session_extensions_immutable`, deletes the pinned rows, re-enables
the triggers and proves every trigger and trigger function on those tables is
byte-identical before commit. Any failed check rolls everything back.

Before deleting, it requires the pinned rows and business totals to be
unchanged, whole-database quiescence, no new foreign-key dependency, the
reviewed invoice counter, and a whole-database fingerprint equal to the value
rehearsed on a restored copy of a fresh backup. Afterwards it proves that
every retained row and unrelated table is unchanged and that exactly one v2
replay receipt was added. The default mode is a dry run that rolls back.

Operator order: pause business and quiesce tablets; deploy Code30.3 to `0082`
with the guarded installer; stop the backend; take a fresh backup; run the
runner's dry run and record its fingerprint; run `--apply` with that fingerprint
and backup; start the backend; reconcile DB, Web, Android and Sheets. The runner
refuses unless the live database and the restored backup are both at `0082`
and the stopped backend's image revision equals `--source-git-sha`. If any
trial row changes after these pins, regenerate and re-review the SQL.

The compatibility policy remains
`ANDROID_MIN_SUPPORTED_VERSION_CODE=8`,
`ANDROID_LATEST_VERSION_CODE=8`, and policy revision `1` until the owner
deliberately activates a verified release offer. A version bump or deployment
does not advertise the APK.

## Verification status

The Code30.3 source freeze is layered on immutable commit
`3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01`. The historical
`REVIEWED_CODE30_2_SHA256` map is validated from that Git object and is not
regenerated. A separate `REVIEWED_CODE30_3_SHA256` map covers every intended
Code30.3 production, test, protocol-fixture, identity and documentation path;
freeze-control files use the repository's established control-file exclusion.

Independent functional and security reviews passed for the initial candidate.
The post-review installer/verifier review also passed after adversarial checks
of nested JSON types, receipt provenance, triggers/functions, lifecycle churn,
legacy database heads, both quiescence gates and pre-promotion rollback. That
focused review recorded 84 passes and one expected macOS skip.

The current reviewed working tree passed **1,705 backend tests** with 21
intentional isolated-audit skips and no failures on a fresh PostgreSQL database
owned by role `erp`, as in CI, and migrated from `0001` through `0082`; **579 Web tests** across 95 files plus TypeScript,
zero-warning ESLint and the verified production build; and **1,136 Android JVM
tests** on each of debug, release and direct-release. Release/direct-release
lint, unsigned Play APK/AAB and unsigned direct-update APK builds also passed.
The fail-closed sharded emulator run discovered **339** exact runner identities
and executed the same **339 exactly once**: **336 functional** tests across four
fresh shards and all **3 physical-frame stress** tests in a separate fresh
process. There were no failures or errors and only the two expected
permission-dependent alarm assumption skips. The separate permission-granted
alarm lane then passed **2/2**, and the complete harness exited `0`. This run
includes the exact cleanup acknowledgement interruption/restart path after the
final identity-header change.

The harness discovers its inventory before execution, resets the app/test
processes and tablet viewport between lanes, archives each lane's
XML/log/report evidence, and fails closed on a missing, duplicate, unexpected,
failed or errored test, an unexpected skip, or a physical-frame stress test in
a functional shard. A test-only POS notice-layer assertion now uses the
visible touch keypad so it does not race the platform IME while that IME opens;
the full `PosEmptyCatalogueUiTest` class passed **6/6**. Production POS behavior
was not changed. The earlier cleanup-only counts remain useful baseline
evidence but do not cover the expanded patch.

Isolated rendered split-payment acceptance also passed on Web and Android for
one ₹50 sale split into ₹20 cash and ₹30 UPI, including ₹50 tendered, ₹30
change, exact receipt rows, stock and shift reconciliation, idempotent replay,
hard reload/app restart, and cleanup of the disposable fixtures. Those runs
used candidate `dca27bc`; later verifier, evidence-analyzer and shift-clock
corrections still require a new exact-head CI run, but did not change the split
payment implementation.

These results complete the local full Android instrumentation gate. They do
not replace the release commit, exact-SHA hosted CI, protected signing, the
same-signer in-place upgrade, production reconciliation, deployment/staging/
offer controls or physical-device gates.

The earlier local release-control phase also passed the coordinated identity
validator, the layered verifier preserving all 491 Code25 baseline test files,
369 layered freeze tests, 190 historical installer/freeze guards, two
freeze-path safety tests, 64 root Android release-contract tests with 124
subtests, and 53 physical-audit-lane contract tests. These results also overlap;
they are contract/source evidence rather than CI, package, deployment or device
proof. After the final harness and documentation edits stopped, the regenerated
Code30.3 map froze 138 reviewed delta files, the focused release-control suite
passed **711** tests with one expected macOS skip, and the complete root release
suite passed **1,135** tests with two expected skips. After the later clock-skew,
post-cleanup verifier and evidence-analyzer corrections and the versioned trial-cleanup replay receipt and trial cleanup, the refreshed Code30.3
map freezes **147** reviewed delta files, the standalone verifier preserved all
491 baseline test files, and the complete root release suite passed **1,149**
tests with two expected skips. `git diff --check` also passed.

The following remain separate pending gates at this candidate phase:

- release commit and protected GitHub CI on that exact clean commit;
- protected `v3.1.30` tag workflow;
- signed build-38 APK and manifest verification;
- same-signer build-37-to-38 in-place upgrade without data clearing;
- five production image scans and runtime identity checks;
- guarded backend/Web deployment through Alembic `0082`;
- inactive APK staging and bound-owner activation;
- authenticated production smoke and exact stale-station recovery;
- physical Redmi Pad 2, printer, alarm, battery-management and shop-day
  acceptance.

No local build, emulator result, source test, deployment, staged row, active
offer, installation or physical acceptance may be inferred from another gate.

## Release and offer order

After independent source/security/release review and green CI, create
`v3.1.30` only from the exact reviewed clean commit. Verify the protected
workflow's manifest, Git SHA, release ref, package `cloud.dcompany.erp`,
version `3.1.30`, code `38`, APK hash, byte size and expected signing
certificate.

Before production maintenance, pause writes and perform a fresh read-only
preflight for tablet sync/outboxes, open shifts, Gaming sessions, held orders,
Sheet delivery state, current runtime identity and database head. Deploy only
the exact reviewed commit with the guarded installer, fresh backup, disposable
restore, rollback evidence and authenticated smoke checks. Confirm backend/Web
`3.1.30` and Alembic `0082`.

The installer independently repeats a global business-quiescence query before
maintenance and after Caddy, backend and frontend stop. Any open shift,
open/held order, active/paused or ended-unsettled Gaming session, unresolved
refund/payment workflow, kitchen cancellation acknowledgement, or unresolved
Sheets delivery aborts the cutover. Its retained Code30.2 outbox exception is
accepted only when the exact cleanup receipt, installation identity, trigger
definitions and audited remote-key lifecycle still match the pinned evidence.

Then prove the signed in-place Android upgrade on an isolated emulator. Stage
the exact APK and manifest **inactive** with
`ops/stage_android_release.py --apply`. Staging must still show **Review &
offer**. The bound owner reviews and activates it only after every earlier gate
passes. Once active, the same record shows **Withdraw**; that means the offer is
already active. Each tablet user still approves Android installation.

If a gate fails, stop before the next trust boundary. Restore production through
the guarded rollback procedure if deployment began; withdraw an active offer to
stop new downloads. Never overwrite the immutable Code30.2 tag, APK, manifest,
hashes or release records.
