# D Company ERP — current project state

Updated 2026-09-22. This is the handover for the additive Code30.3 operational
patch. Read
[`docs/CODE30_3_PATCH_CANDIDATE.md`](docs/CODE30_3_PATCH_CANDIDATE.md) before
changing release identity, cleanup behavior or updater controls. Preserve
[`docs/CODE30_2_PATCH_CANDIDATE.md`](docs/CODE30_2_PATCH_CANDIDATE.md) and the
Code30.2 hash map as immutable predecessor evidence.

## Architecture

- **Backend:** FastAPI, Pydantic 2, async SQLAlchemy, PostgreSQL 16, Redis and
  Alembic. Business records are tenant-scoped, audited and idempotent; money is
  integer minor units.
- **Web ERP:** React 18, TypeScript and Vite against the shared backend. It is
  the owner control and review surface and does not have Android's offline
  queue.
- **Android:** native Kotlin/Jetpack Compose in `android-native`, Room,
  durable offline writes, conflict-aware replay and direct-install updates.
- **Operations:** Docker Compose runs PostgreSQL, Redis, backend, frontend and
  Caddy. The guarded installer owns backup, restore proof, migrations, health
  checks and rollback preparation.
- **Unsupported clients:** the archived Capacitor Android/iOS shells and Tauri
  wrapper are not release clients. There is no supported native iOS release in
  Code30.3.
- **Google Sheets:** PostgreSQL remains authoritative. The optional append-only
  `ERP Mirror v1` is a backup/reconciliation surface and never receives
  receipt bytes, secrets or customer personal data.

## Repository state and release identity

| Item | Current value |
| --- | --- |
| Authoritative checkout | `/Users/mohammednasih/.codex/worktrees/d-company-erp-code30-2-stale-session-reconcile` |
| Branch | `codex/code30-3-stale-gaming-recovery` |
| Immutable Code30.2 base | `3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01` / `v3.1.29` |
| Current public label | Code30.3 |
| Candidate tag | `v3.1.30` |
| Product / Android identity | `3.1.30` / build `38` / `cloud.dcompany.erp` |
| Android database | Room schema `52`, migrating from `51` |
| Backend database | Alembic head `0082`, preserving published cleanup migration `0079` and chaining through split-payment migration `0080` |
| Compatibility defaults | minimum `8`, latest offered `8`, policy revision `1` |
| Local freeze status | The final reviewed Code30.3 byte set passed the whole-delta review, regenerated hash map, freeze verifier and repository release-control suites. The release commit and its exact-head CI remain pending; check Git and PR 16 live rather than relying on this snapshot. |
| Exact future release source | The final reviewed commit recorded by the protected `v3.1.30` workflow manifest |

The release freeze is layered. `REVIEWED_CODE30_2_SHA256` is validated against
the immutable Code30.2 Git object and must never be regenerated. The separate
`REVIEWED_CODE30_3_SHA256` map covers the current intended delta. Its control
files are excluded only to avoid the established self-hash cycle.

PR 16's first hosted run, `35620190613`, passed backend, frontend, Android and
both Docker lanes at commit `b1f08b1502ce626e3fd0cead92b647f26df36ab4`.
That run is historical evidence only: post-review hardening changes require a
new exact-head run before merge or tagging.

## Completed and known-good foundation

The inherited ERP provides authentication and role permissions; branch and
terminal-scoped shifts; POS orders, payments, receipts and refunds; Gaming
sessions, package extensions, pause/resume, transfer, stop, POS handoff and
billing; inventory and stock; finance, accounting and reports; staff, audit and
diagnostics; durable Android sync; private expense receipt evidence; manual
finance entries; the optional Google Sheets mirror; and the owner-controlled
Android updater.

Authorized routine cross-user operation is intentional. An authorized user may
continue, stop, bill or complete another user's Gaming/POS work and may close
the exact eligible branch/terminal shift. Original actor, current actor,
session, drawer and audit attribution remain preserved. Refund, void,
privileged discount, stale-cleanup approval and release activation keep their
separate permissions.

Code30.2 remains the immutable finance/receipt/Sheets predecessor. Reward
redemption and WhatsApp automation remain inactive. SMTP delivery is
provider-blocked and unverified and must not be described as working.

## Code30.3 implementation

Code30.3 keeps the exact stale-Gaming recovery protocol and adds three requested
operational fixes without rebuilding the application: one atomic multi-rail
payment, Web/Android Gaming transfer parity, one-business-day shift
presentation with exact opening and closing times, and protected recovery for
an eligible stale Android-origin shift on another terminal in the same branch.

### Stale Gaming recovery

An Android tablet that retains a local Gaming overlay after the authoritative
server session is already gone uses this exact-candidate protocol.

- Room `52` stores immutable cleanup evidence, revision, directive and
  acknowledgement state.
- A build-38 tablet reports an exact candidate only after normal authoritative
  reconciliation cannot find the server session.
- Cleanup report and acknowledgement requests require a build-38-or-later
  installation whose signed request version exactly matches its persisted
  installation status. Ordinary heartbeats keep their existing compatibility.
- The report binds installation, tenant, branch, terminal, station, local
  action, server session, original actor, start/stop hashes, local snapshot,
  price, duration, amount, unresolved-child count and candidate SHA-256.
- Alembic `0079` adds an append-preserving reconciliation ledger with unique
  current-candidate indexes, immutable evidence guards, transition guards and
  downgrade refusal after evidence exists.
- Alembic `0081` adds immutable report-time app version/build evidence as a
  forward migration after `0080`; published `0079` history remains unchanged.
- Owner Web Gaming shows the exact station name/code, amount, duration and
  candidate evidence. The final approval surface also shows the branch,
  terminal, tablet installation and build; it labels the amount and duration
  as tablet evidence and refuses approval when either value is unavailable.
  The owner approves one candidate and records a reason.
- The tablet must reconnect. It applies only the matching directive with a
  compare-and-set retirement, retains evidence, then acknowledges the backend.
  A lost acknowledgement retries; an acknowledged result stops retrying.
- The tablet presents durable, non-sensitive states for incomplete evidence,
  report retry, owner review, approval/application and acknowledgement. While
  protected cleanup owns the row, ordinary station, add-on, void and POS
  actions are disabled so they cannot race the approved retirement.
- Changed evidence supersedes the preserved old report/approval and produces a
  new review revision. Unresolved children block cleanup and invalid reports
  are bounded.
- There is no generic **clear all**, direct database edit, remote Room rewrite
  or broad station reset. No bill, payment, receipt, customer or genuine
  business record is deleted by this protocol.
- `protocol-fixtures/gaming_cleanup_full_flow_v1.json` binds the same
  cross-language report, approval, directive and acknowledgement contract.

The owner procedure is: update and reconnect exact build `38`, review the
candidate in Web Gaming, confirm branch/terminal/tablet/build plus
station/session/price/duration/hash, approve with a reason, keep the tablet
online until acknowledgement, then verify both surfaces show the station
available. Web cannot repair an offline tablet by itself.

The guarded production installer now performs a global, fail-closed business
quiescence query immediately before maintenance and repeats it from a new
database snapshot after Caddy, backend and frontend stop. It blocks open
shifts, open/held orders, active/paused or ended-unsettled Gaming sessions,
unacknowledged kitchen cancellation work, unresolved POS or membership refund
and payment workflows, and pending/leased/quarantined Sheets deliveries. The
second check closes the race between the live preflight and writer shutdown.
The separate historical Code30.2 outbox verifier accepts only the exact cleanup
receipt and preserved installation evidence while allowing audited lifecycle
telemetry; trigger definitions and cleanup provenance are pinned exactly. Its
bounded JSON parser rejects duplicate keys, non-finite values, booleans or
floats substituted for integers, type drift and schema drift at every nested
receipt field.

### Atomic split tender

- Web and Android support two to five distinct rails from cash, UPI, card, QR
  and wallet after the backend has supplied the exact payable balance.
- `POST /pos/orders/{order_id}/payment-bundle` commits every leg, invoice,
  checkout-claim consumption, idempotent response and Google Sheets outbox
  event in one database transaction. Alembic `0080` permits intermediate rows
  only inside that transaction while the deferred exact-balance guard still
  rejects any partial or overpaid commit.
- Cash received and change belong only to the cash leg; only its bill amount
  increases the expected drawer. Split mode deliberately excludes tips.
- A lost response replays the same complete plan and idempotency key. Android
  persists the canonical plan in the existing settlement outbox and never
  offers split tender offline.
- Mixed-payment refunds require the existing explicit cash refund flow; an
  ambiguous `original` provider refund fails closed.

### Web Gaming transfer and shift-day presentation

- Web Gaming can move an active or paused session to an available active
  station of the same type and branch. The backend rechecks the exact source,
  target, shift and session under locks; price, package, timer, customer and
  staged items remain unchanged.
- Web and Android present all immutable shift segments opened on one IST date
  as one business-day collection. They show the first opening time/opener and
  the final confirmed closing time/closer, while retaining every raw shift for
  drawer and audit review. If any segment remains open, no earlier close is
  mislabelled as the final close.
- Web requests the latest 200 shift rows and warns when its oldest displayed
  day may be incomplete. Android retains the same 200-row boundary in its
  scoped history cache. A shift crossing midnight is grouped by its opening
  date; daily finance reports remain transaction-time based.
- Captured Android shift openings allow at most one second of positive
  tablet/server clock skew without rebasing the saved timestamp. Alembic
  `0082` enforces the same boundary; larger future timestamps fail closed, and
  downgrade refuses to rewrite a shift that used the allowance.

### Protected other-terminal Android shift recovery

- Ordinary shift listing and closing remain scoped to the signed-in terminal.
  Recovery candidates use a separate endpoint and never become the current
  terminal's active shift or enter its business-day totals.
- Only an open protocol-1 Android-origin shift in the same company and branch
  is eligible. The reviewer must have both audit access and
  `pos.shift.close`; Web/legacy shifts and cross-company/cross-branch rows fail
  closed.
- Recovery requires the exact counted cash, a reason and explicit attestation
  that the origin tablet is quarantined. The server reuses the normal blocker,
  cash, accounting and audit path while recording origin and recovery-actor
  terminal identities.
- Browser responses reveal only whether an installation identity was recorded.
  The raw installation UUID is echoed solely to the matching Android
  installation, and the locking query is tenant-scoped before `FOR UPDATE`.
- A lost or ambiguous response keeps the payload and idempotency key locked for
  exact replay. Generic shift history cannot be mistaken for proof that the
  protected recovery ran. HTTP 401/403 and permission loss discard protected
  rows and close the modal; transport/5xx errors retain disabled rows for safe
  retry.

## Verification status

The post-review hardening is complete. Independent installer review passed
after adversarial verification of nested JSON types, exact cleanup provenance,
trigger/function identity, historical lifecycle rules, legacy database heads,
the two quiescence checkpoints and rollback before promotion. Its final focused
run recorded 84 passes and one expected macOS-only skip.

The current reviewed working tree has passed these local source gates:

- backend: **1,652 passed, 21 intentionally skipped, 0 failed** on a new
  disposable PostgreSQL database owned by role `erp` (as CI) and migrated from
  `0001` through `0082`. An earlier local run as a different database role
  failed only the two post-cleanup verifier proofs, because the verifier pins
  guard-function owner `erp`; those proofs now assert the role up front;
- focused backend recovery/security: **24 passed** on another fresh database;
- Web: **95 files / 579 tests passed**, followed by TypeScript, zero-warning
  ESLint and verified Vite production build;
- Android JVM: **1,136 passed** for debug, release and direct-release variants;
- Android build: release/direct-release lint, unsigned Play APK/AAB and unsigned
  direct-update APK builds passed; and
- Android emulator: the fail-closed sharded run discovered **339** exact runner
  identities and executed the same **339 exactly once**: **336 functional**
  tests across four fresh shards and all **3 physical-frame stress** tests in a
  separate fresh process. It recorded no failures or errors, only the two
  expected permission-dependent alarm assumption skips. The separate
  permission-granted alarm lane then passed **2/2**, and the complete harness
  exited `0`.

The instrumentation harness now discovers the runner inventory before test
execution, resets the app/test processes and tablet viewport between lanes,
archives each lane's XML/log/report evidence, and rejects any missing,
duplicate, unexpected, failed or errored test, unexpected skip, or stress test
leaking into a functional shard. The exact post-response/pre-CAS Gaming cleanup
restart path remains covered. A test-only POS notice-layer assertion now enters
the discount through the visible touch keypad instead of racing the platform
IME while it opens; the complete `PosEmptyCatalogueUiTest` class passed
**6/6**. That stabilization does not change production POS behavior.

The earlier stale-recovery-only counts remain historical baseline evidence.
The current local results are source/build/emulator evidence only; the release
commit, exact-SHA hosted CI, protected signing, same-signer upgrade and
production/physical acceptance remain separate.

The earlier release-control phase also passed the coordinated `v3.1.30` /
build-38 validator; the layered verifier preserving all 491 Code25 baseline
test files; 369 layered freeze tests; 190 historical installer/freeze guards;
two freeze-path safety tests; 64 root Android release-contract tests with 124
subtests; and 53 physical-audit-lane contract tests. These overlap and must not
be summed. After the final Android harness and documentation edits stopped, the
regenerated Code30.3 map froze 138 reviewed delta files, the focused
release-control suite passed **711** tests with one expected macOS skip, and the
complete root release suite passed **1,135** tests with two expected skips.
After the later clock-skew, post-cleanup verifier and evidence-analyzer
corrections, the refreshed Code30.3 map freezes **143** reviewed delta files, the
standalone freeze verifier preserved all 491 baseline test files, and the
complete root release suite passed **1,145** tests with two expected skips.
`git diff --check` also passed. A passing local test remains source evidence
only.

The initial PR commit passed repository-wide protected CI, but the final
post-review commit has not. The release commit, exact-head CI, signed APK,
same-signer build-37-to-38 upgrade, production deployment, inactive staging,
active offer, and physical tablet/printer acceptance remain separate pending
gates.

## Known risks and regression protections

- Never select `openShifts[0]`; resolve company, branch and terminal exactly.
- Never change a closed shift's saved cash. Use append-only corrections.
- Keep money in integer minor units and preserve GST, rounding, double-entry,
  idempotency and immutable snapshot contracts.
- Never retire a tablet overlay from station name alone. Require exact scoped
  evidence, receipt binding, candidate hash, owner approval and tablet
  acknowledgement.
- Never delete or mutate cleanup reconciliation evidence. A changed snapshot
  creates a new revision and supersedes the old record.
- Do not raise `ANDROID_LATEST_VERSION_CODE` when building or deploying.
  Staging and owner activation are separate operations.
- Never uninstall or clear the tablet while it has unsynced work.
- Treat the automated split-payment contract, backend and helper coverage as
  source evidence. Exercise the rendered Web and Android editor, confirmation,
  receipt and reload flow against the isolated release backend before the
  production offer.
- Preserve Code30.2 tag, source, APK, manifest, hashes and candidate document.
- Do not claim Google Sheets delivery until the configured generation has a
  delivered connection test and a reconciled mirror row.
- Do not claim SMTP, printer, physical tablet, deployment or active offer from
  source/emulator evidence.

## Outstanding release gates

1. Commit the reviewed frozen candidate and run protected CI on that exact
   clean SHA.
2. Create `v3.1.30` only from the accepted commit and verify the protected
   signed build-38 manifest, APK, hash, size, package, version, source and
   signing certificate.
3. Prove a same-signer build-37-to-38 in-place upgrade without uninstalling or
   clearing Room/outbox state, then exercise the rendered Web and Android split
   tender editor through payment, receipt and reload against the isolated
   release backend.
4. Pause business writes and run a fresh production preflight. Historical
   preflight results cannot authorize this cutover; the global quiescence gate
   must report zero blockers both before and after writer shutdown.
5. Deploy the exact reviewed backend/Web source through the guarded installer,
   fresh backup and restore proof; verify runtime `3.1.30`, exact Git SHA,
   Alembic `0082`, health and authenticated smoke checks.
6. Stage the exact APK **inactive**. The owner then reviews and selects
   **Offer update**. An active record correctly shows **Withdraw**.
7. Update/reconnect the affected tablet, approve the exact stale candidate,
    wait for its acknowledgement, and verify Station 1 is available without
    changing genuine business data.
9. Record physical Redmi Pad 2, printer, alarms, OEM battery handling and
   shop-day acceptance when the hardware is available.

No tag, package, deployment, staged offer or production mutation has been
performed by the current identity/freeze/documentation phase.
