# D Company ERP — current project state

Updated 2026-09-20. This handover describes the Code30.2 patch release line.
The immutable Code30.1 scope and completed evidence remain in
[`docs/CODE30_1_PATCH_CANDIDATE.md`](docs/CODE30_1_PATCH_CANDIDATE.md). Earlier
release records remain historical evidence and must not be rewritten as
Code30.2 proof. The canonical Code30.2 release identity is the immutable
`v3.1.29` tag together with the protected workflow's `release-manifest.json`;
local branch names, working-tree state and manually built packages are not
release identity.

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

## Repository and release identity

| Item | Current value |
|---|---|
| Authoritative checkout | `/Users/mohammednasih/.codex/worktrees/d-company-erp-code30-trial-patches-20260914` |
| Branch | `codex/code30-2-finance-sheets` |
| Release base | `3b534fdc76a5463475d58f44686f91608e1e2601` (`v3.1.28`, Code30.1) |
| Release tag | `v3.1.29` |
| Public label | Code30.2 |
| Product / Android identity | `3.1.29` / build `37` / package `cloud.dcompany.erp` |
| Android database | Room schema `51` |
| Backend database | Alembic head `0078` |
| Immutable release predecessor | Code30.1 `v3.1.28` / build `36` |
| Exact release source | `release-manifest.json.git_sha`, which must equal the commit resolved by `v3.1.29` |
| Exact Android artifact | The APK hash, size, signer, package, build, version, workflow run and release ref in that same manifest |

The branch contains the cumulative Code30.2 implementation, runtime-image
security correction, business-audit corrections, guarded production
trial-cleanup tooling and the fail-closed future-upgrade verifier for the
retained historical outbox counter. The exact delta is protected by the
Code30.2 source-freeze map. This file deliberately does not embed the commit
that contains itself: the protected release workflow records that source in
the manifest and must prove that `release_ref` is `v3.1.29` and `git_sha`
matches the live immutable tag before any artifact is trusted.

The 20 September read-only production preflight found the immutable Code30.1
source `v3.1.28`, backend `3.1.28`, database `0073` and Android offer build
`36`. A local build, emulator installation or passing source test cannot change
production or create an update offer; deployment, cleanup, staging and owner
activation require their separate recorded gates.

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

## Code30.2 release scope

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

### Guarded production trial cleanup

The repository now contains a one-time, rollback-by-default cleanup for the
exact audited Code30.1 production test rows. It pins the full-table and target
fingerprints, primary-key allowlists, migration `0078`, dependency graph and
expected counts; locks every public table without waiting; and verifies every
retained row and unrelated table after the attempted mutation. Apply mode also
requires a fresh dry-run fingerprint, the fresh backup file itself, the
canonical tracked 18-AVD quarantine evidence JSON and its independently
computed hash, a stopped immutable backend image, a completely clean checkout
at the exact deployed Git SHA and a named executor. Apply also binds the live
PostgreSQL and stopped backend containers to the canonical services of the
same Docker Compose project and refuses any running backend service container
in that project. It restores and dry-runs that backup in a disposable database,
then drops the restore on every exit. Normal owner sign-ins after the reviewed
snapshot are accepted only when every new pre-cleanup audit row has the exact
`login_success` shape and resolves to an active user. The frozen 1,271-row audit
prefix, exact 37-row deletion target and dynamic login-suffix hash remain
independently guarded and are bound into the durable receipt. The future
read-only verifier checks the retained prefix and that pre-receipt suffix while
allowing later normal business audit rows. The durable audit receipt fences all
deleted offline actions against replay. The exact retired Code30.1 test
installation remains fully unchanged with its one stale historical saved-action
report because the server UUID cannot be tied to an AVD. Its device
heartbeat/sync timestamps and all 29 immutable expired remote-assistance keys
remain historical evidence. The receipt records the unchanged snapshot with
null offline and sync markers and fences the 13 exact known deleted actions.
All 18 local AVDs are wiped, but that quarantine is not attributed to the
server installation identity. Future installers may accept the retained count
of one only when a read-only verifier proves the single canonical cleanup
receipt, exact retained installation and 29-key hashes, all 13 exact replay
fences, absence of every allowlisted deleted row, and no other pending device.
Any missing, changed, duplicate or partial evidence remains a hard deployment
failure. The cleanup has not been run in apply mode against production.

Detailed scope and release boundaries are in
[`docs/CODE30_2_PATCH_CANDIDATE.md`](docs/CODE30_2_PATCH_CANDIDATE.md), and the
Sheet contract is in [`docs/GOOGLE_SHEETS.md`](docs/GOOGLE_SHEETS.md).

## Current verification state

The last clean committed source completed the full emulator business audit with
390 checks: 16 sessions/orders/payments, four extensions, two add-ons, two
pauses, split cash/UPI settlement, discount/COGS/profit reconciliation,
cross-user operation, offline/reconnect and process restart. It ended with no
active session, blocked station or unbalanced drawer. The measured 30-frame UI
sample had 35.378 ms p95, 40.559 ms maximum and no frame above 50 ms, crash,
ANR or layout jump.

The cumulative Code30.2 source has recorded passes for 1,572 backend tests with
21 expected isolated-audit skips on Python 3.14.7 against a fresh PostgreSQL
database migrated from zero through `0078`; 526 Web tests, lint, type checking
and a production build; and 4,406 Android JVM tests, lint and from-scratch debug
builds. The repository-level release, installer, security, freeze and contract
suite passed 950 tests, two expected skips and 322 subtests. The canonical
API-35 tablet-profile lane passed its 333 ordinary device
tests plus two explicit granted-notification/deep-idle alarm proofs. A direct
Gradle run at the AVD's 2560x1800 default had first failed the intentionally
profile-sensitive inventory keyboard threshold; the canonical lane set and
verified the required 2560x1600, 320-dpi (1280x800 dp) profile and passed the
same test. The final audit-drift guard's cleanup and future-upgrade focus passed
28 tests, and the source-freeze verifier passed. The earlier baseline
post-cleanup query executed successfully on PostgreSQL 16 while correctly
rejecting a database without the required cleanup receipt; the final dynamic
guard must still pass the installer's fresh-backup restore dry run before any
live cleanup can apply.

An earlier hosted container run exposed Python 3.13.15 `CVE-2026-82049` and
Alpine zlib 1.3.2-r0 `CVE-2026-85091`. The committed correction moves the
backend and CI to Python 3.14.7, builds and attests Redis locally, and overlays
zlib 1.3.2 built from the official source plus the complete upstream chain
`e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca`,
`bbc2ccf3d0de267576b524b875c769a724a513b0`,
`df84af25dc1942490e1d1c899a07619152a46148`, and
`7235b0a581227c56a79a43ff828f8ef6794194c8` in backend, frontend, Caddy,
PostgreSQL and Redis. Each exact image gets a narrowly scoped OpenVEX document;
all other High/Critical findings still fail closed.

Protected CI run `35504974966` passed backend, Web, Android and both production
image lanes for the source immediately before the final audit-log drift guard.
The drift guard then passed its focused cleanup, future-upgrade, replay-fence,
freeze and PostgreSQL-16 query checks. The `v3.1.29` tag workflow remains the
authoritative final rerun because it rebuilds and verifies the exact source and
artifact recorded in the release manifest.

## Known release gaps

- Repeat the 390-step business audit on the final frozen commit.
- Build and scan all five exact production images in both hosted Docker-store
  lanes; retain SBOM, Grype, VEX, image-identity and runtime-probe evidence.
- Repeat the business trial with two authorized users across shift open/close,
  Gaming start/pause/extend/stop/handoff, POS settlement/refund, split payment,
  manual finance, receipt capture/upload/retry, restart, offline/reconnect,
  closed-shift correction, reports, Sheet reconciliation and cleanup.
- Create `v3.1.29` only from the exact reviewed clean source and require its
  protected workflow to pass every source, app and production-image lane.
- Produce the signed build-37 APK through that protected tag workflow and
  independently verify the manifest's release ref, Git source, checksum, size,
  package, version, build, workflow provenance and expected signer.
- Prove a normal same-signer in-place upgrade from exact signed
  `v3.1.28` / build `36` to build `37` without uninstalling or clearing
  Room/outbox state.
- Deploy backend/Web and Alembic `0078` through the guarded cutover with a
  verified production backup, disposable restore, rollback evidence and
  authenticated smoke checks.
- While all writers remain stopped, run the exact production cleanup dry run,
  review its fresh fingerprint, apply it once with the verified backup and
  emulator-quarantine evidence, then reconcile every final count.
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

Code30.2 is not live until the exact `v3.1.29` tag and its protected release
manifest pass, the signed build-37 APK is independently verified, the in-place
upgrade is proven, and the coordinated production, cleanup and Sheet cutover
complete. Inactive staging and owner activation follow those gates.
Physical-tablet acceptance remains separate.
