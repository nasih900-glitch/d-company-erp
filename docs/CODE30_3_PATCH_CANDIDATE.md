# Code30.3 stale Gaming recovery patch candidate

Code30.3 is an additive patch to the existing Code30 application. It does not
rebuild the ERP. Its coordinated identity is `v3.1.30`, Android installation
build `38`, Room schema `52`, and Alembic head `0079`. Its immutable base is
Code30.2 commit `3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01`
(`v3.1.29`, build `37`, Room `51`, Alembic `0078`).

The patch addresses a narrow failure mode: a tablet can retain an old local
Gaming session overlay after the authoritative server session has already been
completed and removed. That local evidence can keep a station showing Payment
due while the Web ERP correctly has no active session to close. Code30.3 adds a
reviewed, exact-candidate recovery flow. It does not add a generic station
reset, database clear, or broad deletion control.

## Recovery contract

A build-38 tablet that encounters a missing authoritative session reports its
exact immutable local start/stop evidence, station, branch, terminal,
installation, original actor, pricing snapshot, duration, amount, unresolved
child count, local evidence revision, and cryptographic candidate hash. The
backend accepts the report only when it matches the canonical Code30.1 cleanup
receipt and replay fences. Tenant, branch, terminal, installation, station and
session scope are enforced.

The owner Web ERP displays the exact station name/code, duration and amount for
the candidate. Approval records the approving owner, reason, idempotency key,
candidate hash and audit receipt. Approval does not directly alter the tablet.
The tablet must be online or reconnect on build `38`; it re-reports the same
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
   Confirm the tablet, branch, terminal, station name/code, session, duration,
   amount, actor and candidate hash.
4. Approve only that exact candidate with a reason.
5. Keep the tablet connected until it applies and acknowledges the directive.
6. Refresh Web and tablet Gaming views. Confirm that the station is available,
   no active or payment-due overlay remains, and no order, charge, payment,
   receipt or customer record was created or removed by the cleanup.

There is deliberately no **clear all**, direct SQL, remote Room edit, or
unscoped force-available action. If no candidate appears, reconnect the exact
tablet first and inspect its installation diagnostics. A different candidate
hash or revision requires a fresh review.

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
It does not delete live business data or the historical Code30.2 evidence.

## Database changes

Alembic `0079` adds the tenant-scoped
`client_gaming_cleanup_reconciliation` ledger with unique current-candidate
constraints, revision checks, immutable evidence guards, valid-transition
guards and a downgrade refusal once evidence exists. It chains from `0078`.

Room `52` adds cleanup evidence, directive and acknowledgement fields and the
`51 -> 52` migration. Upgrade acceptance must install build `38` over the
same-signed build `37` without uninstalling or clearing app data.

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

Independent functional verification passed. Independent security review passed.
The latest security rerun recorded 39 passing focused backend tests against
disposable PostgreSQL, three passing dedicated real-PostgreSQL trigger tests,
the Android shared-root-fixture Room bridge at 1/1, passing focused Android JVM
tests, Web type checking plus 13 focused tests, Ruff, and `git diff --check`.
The writer's fresh-PostgreSQL audit/cleanup/migration suite recorded 40 passes
with one existing Argon2 deprecation warning. The final Android repair gate,
after which Android source did not change, recorded 82/82 disposable-emulator
Gaming DAO and migration checks plus successful androidTest compilation. The
Web repair gate recorded 13 focused tests, type checking, targeted ESLint,
production build and asset verification. The focused PostgreSQL results include
the shared signed-backend flow and PII audit scan. These are overlapping
focused results and must not be summed into a repository-wide total.

The final local release-freeze phase also passed the coordinated identity
validator, the layered verifier preserving all 491 Code25 baseline test files,
369 layered freeze tests, 190 historical installer/freeze guards, two
freeze-path safety tests, 64 root Android release-contract tests with 124
subtests, and 53 physical-audit-lane contract tests. These results also overlap;
they are contract/source evidence rather than CI, package, deployment or device
proof.

The following remain separate pending gates at this candidate phase:

- repository-wide full suites and protected GitHub CI on the final clean commit;
- protected `v3.1.30` tag workflow;
- signed build-38 APK and manifest verification;
- same-signer build-37-to-38 in-place upgrade without data clearing;
- five production image scans and runtime identity checks;
- guarded backend/Web deployment through Alembic `0079`;
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
`3.1.30` and Alembic `0079`.

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
