# D Company ERP 3.1.16 (code 26) release candidate

Code 26 is the corrective candidate after the Code 25 physical-tablet audit.
Its coordinated identity is tag `v3.1.16`, Android `versionName=3.1.16`, Android
`versionCode=26`, and database migration head `0071`. Code 25 remains immutable;
its APK must not be rebuilt or silently replaced.

The immutable `v3.1.15` attempt failed before a release build or signing and
produced no authorised or distributed APK. It was never registered, staged,
activated, offered, or installed as a production direct-signed Code 26
artifact; isolated debug test installations are not distribution evidence.
Retaining version code `26` for `3.1.16` is a one-time narrow correction to that
never-issued identity, not authority to reuse any version code that has
produced a signed or distributed artifact. The `v3.1.15` tag and failed
evidence must not be moved or rewritten.

This ledger separates source checks from release approval. Code 26 is not ready
for staff installation until one exact commit and signed APK pass every gate
below and the same artifact is staged in the owner-controlled Web ERP update
centre.

## Narrow corrective scope

- Remove main-thread persisted-store loading from Android cold start while
  keeping authentication, offline ownership, alarms, diagnostics, bug reports,
  and sync fail-closed until restoration completes.
- Accept the decimal comma emitted by supported tablet keyboards without ever
  converting an ambiguous value such as `1,250` into a different amount.
- Keep Gaming and Finance status/refresh regions geometrically stable so normal
  connectivity changes do not move the operational workspace.
- Lock Finance form values while a write is in progress.
- Replace silent Gaming start/stop and POS payment rejection with explicit,
  actionable workspace-recovery feedback.
- Give every POS quantity control item-specific accessibility semantics.
- Share one session-scoped refresh coordinator across ordinary ERP and remote
  support clients. Preserve separate network clients, remote proof/price
  boundaries, account-lineage guards and backend token-reuse revocation.
- Preserve all Code 25 tariffs, billing, shift, offline, update, permission,
  audit, and finance contracts unchanged.

## Regression rule

Every Code 25 check that passed is a Code 26 floor, not optional historical
evidence. A newly failing test, changed tariff, changed financial total, lost
idempotency guarantee, new crash/ANR, or weakened authorization rejects Code 26.
The corrective files above receive focused tests in addition to the complete
backend, frontend, Android JVM, instrumentation, and physical-device suites.

Before any release build or physical run, execute:

```bash
python3 scripts/verify_code26_regression_freeze.py
python3 -m pytest -q tests/test_code26_regression_freeze.py tests/test_code26_physical_audit_lane.py
```

The first command must report all 491 Code 25 test/support files preserved and
no production path outside the reviewed Code 26 allow-list. A missing,
rewritten, reordered, skipped, ignored or xfailed baseline check rejects the
candidate.

The audit-isolation test's plan-reader locator follows the raw UTF-8 reader.
This one exact locator migration is reviewed and normalized by the freeze
gate; all original credential-cleanup assertions and their ordering remain
mandatory. No application behavior is exempted from regression verification.

## Required release phases and final owner decision

The four preparation phases are ordered gates. Evidence from an earlier source
identity does not substitute for the exact `v3.1.16` candidate commit. Within
Phase 2, the full automated and bounded physical lanes may run in parallel
against the same frozen source; both must pass.

1. **Fixes and regression protection.** Complete the bounded corrective scope,
   its focused regressions, independent code/security review, and the 491-file
   Code 25 regression freeze without weakening business, auth, permission,
   money, audit, or update contracts.
2. **Full automated and bounded physical validation.** Apply all migrations to
   a fresh disposable PostgreSQL database; run the full backend suite and
   guarded Gaming/POS/Finance workflow without touching production data. Run
   frontend typecheck, all tests, production build, and rendered Web ERP checks
   for synchronized Gaming, billing, shift, finance, and update state. Run
   complete Android JVM/lint/build checks and instrumentation for input,
   cold-start recovery, Gaming, POS, shift, alarms, offline/retry, diagnostics,
   updates, and permissions. In the parallel bounded physical lane, run the
   authenticated synthetic flow on physical Android tablet hardware:
   login, open shift, all nine base Gaming package codes, all eight extension
   package codes, every supported player mode, reasoned pause, stable paused
   state, resume, add-on, stop, send to POS, discount, cash/UPI,
   receipt/history, finance/report, close, restart, offline queue, reconnect,
   and duplicate protection. Record
   crash, ANR, frozen-frame, layout-flicker, and frame-time evidence separately.
   Run only from a clean committed Code 26 tree. The physical runner records and
   rechecks the exact commit/tree, APK SHA-256, verified signer certificate and
   isolated `cloud.dcompany.erp.physicalaudit` manifest identity
   (`26` / `3.1.16-physical-audit`); it rejects dirty or drifting source. Its
   413-step plan completes 16 isolated Gaming-to-payment sessions and records
   exact per-step screenshots/hierarchies, start/mid/end idle-window geometry
   across nine stability windows, strict non-zero frame thresholds for the four
   live-timer windows (after separate layout observations and a two-second
   settle, with no screenshot/accessibility work during frame measurement),
   exact raw paused-timer equality after the success snackbar finishes, device constraint
   evidence, and an authenticated Finance/Reports-to-fixture reconciliation. Shared Gaming
   pause is enabled only for that disposable loopback-backed audit process; the
   post-run fixture requires exact pause/resume actor, terminal, reason, version,
   response clock consistency, stable pause duration, unchanged package
   snapshots, all 17 configured tariff codes, 16 unique paid orders and both
   payment rails. Named Finance
   hierarchies must show the reconciled revenue, COGS, gross profit and
   operating profit; named Reports hierarchies must show the reconciled
   revenue, order count and net profit. Cash, UPI and Reports COGS remain exact
   authenticated API evidence because those cards are below the initial tablet
   viewport.
3. **Signing, upgrade, final audit, and deployment.** Perform the final exact
   source audit, freeze one commit, build and sign through protected CI, then
   verify package, version, signer, SHA-256, byte size, manifest, and provenance.
   Prove a same-signing-key in-place Code 21 to Code 26 upgrade without
   uninstalling or losing Room/outbox state. With fresh maintenance confirmation
   and current tablet/outbox quiescence, back up and restore-test production,
   deploy the matching backend and Web ERP, and verify live runtime identity and
   compatibility endpoints. The old 5 September client heartbeat is not
   evidence of current quiescence.
4. **Owner-visible inactive staging.** Stage only the exact verified signed APK
   in Web ERP as an inactive owner-visible record. Staging must not itself
   activate or advertise an update offer.

The user cancelled the separate eight-hour endurance requirement. The
interrupted isolated shop-day remains preserved failed evidence of the
cross-client refresh race; it is not reclassified or completed by adding its
remaining hours. See `CODE26_AUTH_REFRESH_INCIDENT.md` for the bounded incident
and corrective proof.

Only after all four phases pass on the same source and artifact may the owner
activate the exact staged candidate; no automated process may offer it.
Activation advertises the update to every eligible direct-channel client, not
only one selected tablet: this release has no per-device allowlist. Keep it
staged until the owner explicitly accepts that channel-wide exposure and has
coordinated the intended pilot installation. Android still requires employee
installation approval. After installation, run the supervised
real-live operational acceptance trial with authenticated Android health,
natural token expiry, financial reconciliation, and no automatic relogin.
Confirm the intended tablet reports Code 26, has no unsent work, and passes a
final smoke shift. Passing that trial is required before the owner asks other
staff to install; that operational rollout restriction is not enforced by the
update endpoint.

Cloud physical hardware is valid defect-finding evidence but is not a claim
that the partner's Redmi Pad 2 or HyperOS was tested. SMTP delivery, real UPI or
bank settlement, and receipt-printer hardware remain separate operational
checks when those integrations are enabled. The target Redmi must separately
prove reboot alarm delivery, actual lock-screen alarm delivery, staff recovery
after notification-permission denial, HyperOS battery-policy behavior and the
same-key signed in-place upgrade; Firebase toggling those states only proves
that the app preserves its registered alarm and recovers after relaunch.
