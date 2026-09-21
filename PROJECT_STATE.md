# D Company ERP — current project state

Updated 2026-09-21. This is the handover for the additive Code30.3 stale Gaming
recovery patch. Read
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
| Branch | `codex/code30-2-stale-session-reconcile` |
| Immutable Code30.2 base | `3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01` / `v3.1.29` |
| Current public label | Code30.3 |
| Candidate tag | `v3.1.30` |
| Product / Android identity | `3.1.30` / build `38` / `cloud.dcompany.erp` |
| Android database | Room schema `52`, migrating from `51` |
| Backend database | Alembic head `0079`, chaining from `0078` |
| Compatibility defaults | minimum `8`, latest offered `8`, policy revision `1` |
| Working tree | Intentionally dirty with the uncommitted Code30.3 candidate; preserve it until freeze review completes |
| Exact future release source | The final reviewed commit recorded by the protected `v3.1.30` workflow manifest |

The release freeze is layered. `REVIEWED_CODE30_2_SHA256` is validated against
the immutable Code30.2 Git object and must never be regenerated. The separate
`REVIEWED_CODE30_3_SHA256` map covers the current intended delta. Its control
files are excluded only to avoid the established self-hash cycle.

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

Code30.3 adds a narrow recovery protocol for an Android tablet that retains a
local Gaming overlay after the authoritative server session is already gone.

- Room `52` stores immutable cleanup evidence, revision, directive and
  acknowledgement state.
- A build-38 tablet reports an exact candidate only after normal authoritative
  reconciliation cannot find the server session.
- The report binds installation, tenant, branch, terminal, station, local
  action, server session, original actor, start/stop hashes, local snapshot,
  price, duration, amount, unresolved-child count and candidate SHA-256.
- Alembic `0079` adds an append-preserving reconciliation ledger with unique
  current-candidate indexes, immutable evidence guards, transition guards and
  downgrade refusal after evidence exists.
- Owner Web Gaming shows the exact station name/code, amount, duration and
  candidate evidence. The owner approves one candidate and records a reason.
- The tablet must reconnect. It applies only the matching directive with a
  compare-and-set retirement, retains evidence, then acknowledges the backend.
  A lost acknowledgement retries; an acknowledged result stops retrying.
- Changed evidence supersedes the preserved old report/approval and produces a
  new review revision. Unresolved children block cleanup and invalid reports
  are bounded.
- There is no generic **clear all**, direct database edit, remote Room rewrite
  or broad station reset. No bill, payment, receipt, customer or genuine
  business record is deleted by this protocol.
- `protocol-fixtures/gaming_cleanup_full_flow_v1.json` binds the same
  cross-language report, approval, directive and acknowledgement contract.

The owner procedure is: update and reconnect exact build `38`, review the
candidate in Web Gaming, confirm station/session/price/duration/hash, approve
with a reason, keep the tablet online until acknowledgement, then verify both
surfaces show the station available. Web cannot repair an offline tablet by
itself.

## Verification status

Independent functional verification passed and independent security review
passed. The latest security rerun recorded 39 passing focused backend tests
against disposable PostgreSQL, three passing dedicated real-PostgreSQL trigger
tests, the Android shared-root-fixture Room bridge at 1/1, passing focused
Android JVM tests, Web type checking plus 13 focused tests, Ruff, and
`git diff --check`. The writer's fresh-PostgreSQL audit/cleanup/migration suite
recorded 40 passes with one existing Argon2 deprecation warning. The final
Android repair gate, after which Android source did not change, recorded 82/82
disposable-emulator Gaming DAO and migration checks plus successful androidTest
compilation. The Web repair gate recorded 13 focused tests, type checking,
targeted ESLint, production build and asset verification. The focused
PostgreSQL results include the shared signed-backend flow and PII audit scan.
These results overlap and are not a repository-wide total.

This release-identity phase also passed the coordinated `v3.1.30` / build-38
validator; the layered verifier preserving all 491 Code25 baseline test files;
369 layered freeze tests; 190 historical installer/freeze guards; two
freeze-path safety tests; 64 root Android release-contract tests with 124
subtests; and 53 physical-audit-lane contract tests. These overlap and must not
be summed. A passing focused test remains source evidence only.

Repository-wide full suites, protected CI, a signed APK, same-signer
build-37-to-38 upgrade, production deployment, inactive staging, active offer,
and physical tablet/printer acceptance remain separate pending gates.

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
- Preserve Code30.2 tag, source, APK, manifest, hashes and candidate document.
- Do not claim Google Sheets delivery until the configured generation has a
  delivered connection test and a reconciled mirror row.
- Do not claim SMTP, printer, physical tablet, deployment or active offer from
  source/emulator evidence.

## Outstanding release gates

1. Obtain independent read-only release approval for the final frozen bytes.
2. Commit the reviewed candidate and run protected CI on that exact clean SHA.
3. Create `v3.1.30` only from the accepted commit and verify the protected
   signed build-38 manifest, APK, hash, size, package, version, source and
   signing certificate.
4. Prove a same-signer build-37-to-38 in-place upgrade without uninstalling or
   clearing Room/outbox state.
5. Pause business writes and run a fresh production preflight. Production
   runtime and database state are time-sensitive and have not been revalidated
   by this documentation edit.
6. Deploy the exact reviewed backend/Web source through the guarded installer,
   fresh backup and restore proof; verify runtime `3.1.30`, exact Git SHA,
   Alembic `0079`, health and authenticated smoke checks.
7. Stage the exact APK **inactive**. The owner then reviews and selects
   **Offer update**. An active record correctly shows **Withdraw**.
8. Update/reconnect the affected tablet, approve the exact stale candidate,
    wait for its acknowledgement, and verify Station 1 is available without
    changing genuine business data.
9. Record physical Redmi Pad 2, printer, alarms, OEM battery handling and
   shop-day acceptance when the hardware is available.

No tag, package, deployment, staged offer or production mutation has been
performed by the current identity/freeze/documentation phase.
