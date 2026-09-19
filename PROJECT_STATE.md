# D Company ERP — current project state

Updated 2026-09-19. This handover describes the in-progress Code30.2 patch.
The immutable Code30.1 scope and completed evidence remain in
[`docs/CODE30_1_PATCH_CANDIDATE.md`](docs/CODE30_1_PATCH_CANDIDATE.md). Earlier
release records remain historical evidence and must not be rewritten as
Code30.2 proof.

## Architecture and supported applications

- **Backend:** FastAPI/Pydantic 2, async SQLAlchemy, PostgreSQL 16, Alembic and
  Redis. Financial writes use integer minor units, tenant and branch scope,
  audit records and idempotency.
- **Web:** React 18, TypeScript and Vite against the shared backend API. The Web
  client requires a live connection and has no Android-style offline queue.
- **Android:** native Kotlin/Jetpack Compose in `android-native`, with Room,
  durable queued writes, reconciliation and the direct-install updater. This is
  the only supported Android source.
- **Operations:** Docker Compose runs PostgreSQL, Redis, backend, frontend and
  Caddy. The guarded installer performs provenance checks, quiesced backup and
  restore verification, migrations, health checks and rollback preparation.
- **Outside this release:** the archived Capacitor Android/iOS shells and Tauri
  wrapper are not supported clients. There is no supported native iOS
  distribution in Code30.2.

## Repository and candidate identity

| Item | Current value |
|---|---|
| Authoritative checkout | `/Users/mohammednasih/.codex/worktrees/d-company-erp-code30-trial-patches-20260914` |
| Branch | `codex/code30-2-finance-sheets` |
| Candidate base | `3b534fdc76a5463475d58f44686f91608e1e2601` (`v3.1.28`, Code30.1) |
| Intended patch tag | `v3.1.29` |
| Public label | Code30.2 |
| Product / Android identity | `3.1.29` / build `37` / package `cloud.dcompany.erp` |
| Android database | Room schema `51` |
| Backend database | Alembic head `0078` |
| Immutable release predecessor | Code30.1 `v3.1.28` / build `36` |

The working tree contains the cumulative, uncommitted Code30.2 implementation
and tests. Source-freeze hashes have deliberately not been regenerated. No
signed `v3.1.29` artifact exists yet, and Code30.2 has not been deployed,
staged, activated, offered or accepted on the Redmi Pad 2.

Production remains on the immutable Code30.1 release line while this candidate
is prepared. A local build, emulator installation or passing source test cannot
change production or create an update offer.

## Completed and known-good foundation

The inherited ERP includes authentication and permissions; POS orders,
payments, receipts and refunds; inventory and stock; shifts and cash
reconciliation; gaming sessions, extensions, pause/resume, POS handoff and
billing; finance, accounting, reports, staff, audit, diagnostics and the Android
updater.

Code30.1 established the preserved baseline for saved customer lookup, customer
playtime, deletion-replay protection, five pricing-card modes, exact
branch/terminal shift resolution and authorized cross-user routine operation.
An authorized user may continue, bill or complete another authorized user's POS
or gaming work and may close the exact branch/terminal shift. Refund, void,
privileged discount and release controls retain their separate permissions.
Reward redemption and WhatsApp messaging remain inactive.

## Code30.2 work present in the tree

### Manual finance and receipt evidence

- Authorized users can create manual expenses and manual collections in Web and
  Android with the applicable business date, category, amount, payment method,
  vendor or payer reference, invoice reference and note.
- A manual expense may retain up to five private JPEG, PNG, WebP or PDF evidence
  files, each no larger than 10 MiB. Android supports the system camera and file
  picker; Web supports browser camera/file selection.
- The backend verifies file bytes instead of trusting a filename or declared
  content type, stores immutable evidence and records append-only review
  history. Receipt bytes and private review notes remain behind ERP
  authentication and are never copied to Google Sheets.
- Android stores a pending expense and its receipt chunks atomically in Room,
  then synchronizes the parent before its attachments. Retry, restart,
  disconnection and acknowledgement loss must not duplicate the expense or a
  receipt. Rejected evidence remains visible for a deliberate retry or removal.
- Cash expenses, cash manual collections, cash tip payouts and cash supplier
  payments name the exact open same-branch shift drawer and apply one atomic
  cash effect. Authorized cross-user operation is supported on an eligible
  drawer; the actor and original drawer owner remain attributable.
- An ordinary void is allowed while the source drawer is open. Once that shift
  is closed, Web uses the authorized closed-shift correction flow instead of
  rewriting its saved closing cash. The immutable original stays in history; a
  full reversal is dated now, posted in the current reporting period and
  applied to a selected current same-branch drawer. Android displays corrected
  history and excludes it from active totals; correction entry is performed in
  Web ERP.

### Optional Google Sheets mirror

- `ERP Mirror v1` is a secondary append-only transaction mirror. PostgreSQL
  remains the authority and recovery source.
- Eligible committed POS, membership and manual-finance transactions and their
  reversals create tenant-scoped delivery events in the same database
  transaction as the business change.
- Each event is bound permanently to one configuration generation. Business
  events recorded before verification are held durably until a connection-test
  event for that exact generation succeeds.
- Delivery uses signed requests, stable event hashes, leasing, bounded retry and
  quarantine. Formula-like values are forced to text, and receipt bytes,
  customer personal information, private review notes and secrets are excluded.
- Correcting an unverified URL keeps its held generation and requires a fresh
  test. Once a connection is verified, URL/secret rotation or disconnect is
  refused while that generation has pending, leased or quarantined business
  events. Operators must let pending work drain and retry quarantined work
  before rotating or disconnecting.
- Old events are never silently retargeted to a new URL or secret. Replaying the
  same business fact returns its original immutable event rather than creating
  a duplicate in the new generation.
- The integration writes only to the separate `ERP Mirror v1` tab. It does not
  edit existing workbook tabs, import Sheet edits, backfill old records or
  replace database backup and restore.

Alembic `0074` through `0078` add receipt evidence, the Sheet outbox and
generation-bound configuration, modern cash drawer provenance, and immutable
closed-shift finance corrections. Room `49` through `51` add durable finance
evidence, drawer/correction state and compatibility handling.

Detailed scope and release boundaries are in
[`docs/CODE30_2_PATCH_CANDIDATE.md`](docs/CODE30_2_PATCH_CANDIDATE.md), and the
Sheet contract is in [`docs/GOOGLE_SHEETS.md`](docs/GOOGLE_SHEETS.md).

## Current verification state

Focused backend, Web and Android checks have passed during implementation,
including receipt validation and recovery, cash-source integrity, correction
accounting, migration guards, Sheet authentication/deduplication/configuration
rotation, and Web type/lint/build checks. Earlier complete Code30.1 gaming,
billing, offline and cross-user trial evidence remains a regression baseline.

Those checkpoints are not a final Code30.2 release verdict. Functional edits are
still converging, so the final exact-source backend, Web, Android variant,
API-35 instrumentation, migration rehearsal, repository-contract and business
trial runs remain pending. Final totals must be recorded only after those clean
runs finish. Freeze checks are expected to remain incomplete until the reviewed
file set and hashes are finalized.

## Known release gaps

- Complete clean exact-source backend, Web and Android suites, API-35
  instrumentation, release builds, migration rehearsal and repository checks.
- Repeat the business trial with two authorized users across shift open/close,
  Gaming start/pause/extend/stop/handoff, POS settlement/refund, split payment,
  manual finance, receipt capture/upload/retry, restart, offline/reconnect,
  closed-shift correction, reports, Sheet reconciliation and cleanup.
- Freeze and commit the exact reviewed source, then create `v3.1.29`.
- Produce the signed build-37 APK through protected GitHub CI and independently
  verify its manifest, checksum, size, package, source and expected signer.
- Prove a normal same-signer in-place upgrade from exact signed
  `v3.1.28` / build `36` to build `37` without uninstalling or clearing
  Room/outbox state.
- Deploy backend/Web and Alembic `0078` through the guarded cutover with a
  verified production backup, disposable restore, rollback evidence and
  authenticated smoke checks.
- Deploy and authorize the bound Apps Script, enter the one-time secret, deliver
  the current-generation connection test and reconcile an actual mirror row.
- Stage the exact verified APK inactive, then use the bound owner release
  control to activate it only after the coordinated production gates pass.
- Record physical Redmi Pad 2 installation, keyboard, printer, alarms, battery
  management and shop-day acceptance when the tablet is available.

## Regression protections and decisions to preserve

- Resolve shifts by company, branch and terminal. Never select an arbitrary
  first open shift.
- Keep authorized routine cross-user operations available while preserving the
  separate permissions for refund, void, privileged discount and release
  control.
- Keep money as integer minor units and preserve GST, rounding, drawer,
  double-entry, immutable snapshot and idempotency contracts.
- Never mutate a closed shift's saved cash. Record a reasoned, append-only
  current-period correction against a selected open same-branch drawer.
- Do not let receipt retry, offline replay or acknowledgement loss create a
  second expense, drawer movement, Sheet event or evidence file.
- Keep receipt bytes private. Do not place customer identity, private review
  notes, secrets or executable spreadsheet formulas in Sheets.
- Preserve each Sheet event's immutable configuration generation. Drain current
  work before rotating or disconnecting and never retarget superseded events.
- Preserve tenant/branch/terminal isolation, RBAC, audit rows, tombstones,
  captured offline time and customer deletion revisions.
- Do not activate rewards, WhatsApp, the compatibility minimum, an update offer
  or Google Sheets merely by deploying source.
- Never rebuild or overwrite `v3.1.28` or another signed predecessor. Every
  successor requires a higher build and same-signer upgrade proof.

## Release readiness and priorities

Code30.2 remains an active source candidate and is not ready for live business
use yet. Finish final review and exact-source verification, freeze the source,
obtain and verify the signed build-37 APK, prove the in-place upgrade, then
perform the coordinated production and Sheet cutover. Inactive staging and owner
activation follow those gates. Physical-tablet acceptance remains separate.
