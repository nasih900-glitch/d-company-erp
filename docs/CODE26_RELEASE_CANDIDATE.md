# D Company ERP 3.1.15 (code 26) release candidate

Code 26 is the corrective candidate after the Code 25 physical-tablet audit.
Its coordinated identity is tag `v3.1.15`, Android `versionName=3.1.15`, Android
`versionCode=26`, and database migration head `0071`. Code 25 remains immutable;
its APK must not be rebuilt or silently replaced.

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

## Required release gates

1. Apply all migrations to a fresh disposable PostgreSQL database; run the full
   backend suite and guarded Gaming/POS/Finance workflow without touching
   production data.
2. Run frontend typecheck, all tests, production build, and rendered Web ERP
   checks for synchronized Gaming, billing, shift, finance, and update state.
3. Run complete Android JVM/lint/build checks and instrumentation for input,
   cold-start recovery, Gaming, POS, shift, alarms, offline/retry, diagnostics,
   updates, and permissions.
4. On physical Android tablet hardware, run the authenticated synthetic flow:
   login, open shift, all nine base Gaming package codes, all eight extension
   package codes, every supported player mode, reasoned pause, stable paused
   state, resume, add-on, stop, send to POS, discount, cash/UPI,
   receipt/history, finance/report, close, restart, offline queue, reconnect,
   and duplicate protection. Record
   crash, ANR, frozen-frame, layout-flicker, and frame-time evidence separately.
   Run only from a clean committed Code 26 tree. The physical runner records and
   rechecks the exact commit/tree, APK SHA-256, verified signer certificate and
   isolated `cloud.dcompany.erp.physicalaudit` manifest identity
   (`26` / `3.1.15-physical-audit`); it rejects dirty or drifting source. Its
   410-step plan completes 16 isolated Gaming-to-payment sessions and records
   exact per-step screenshots/hierarchies, start/mid/end idle-window geometry
   across nine stability windows, strict non-zero frame thresholds for the four
   live-timer windows, exact raw paused-timer equality, device constraint
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
5. Independently review authentication, tenant/workspace isolation, money,
   idempotency, audit, signing, update, and rollback boundaries.
6. Freeze one commit, build and sign through protected CI, then verify package,
   version, signer, SHA-256, byte size, manifest, and provenance. Prove a
   same-signing-key in-place Code 21 to Code 26 upgrade without uninstalling or
   losing Room/outbox state.
7. Back up and restore-test production before deploying the matching backend
   and Web ERP. Verify live runtime identity and compatibility endpoints.
8. Stage only the verified signed APK in Web ERP. The owner decides when to
   offer it; Android still requires the employee to approve installation.
9. Confirm the intended tablet reports Code 26, has no unsent work, and passes a
   final smoke shift before calling the rollout complete.

Cloud physical hardware is valid defect-finding evidence but is not a claim
that the partner's Redmi Pad 2 or HyperOS was tested. SMTP delivery, real UPI or
bank settlement, and receipt-printer hardware remain separate operational
checks when those integrations are enabled. The target Redmi must separately
prove reboot alarm delivery, actual lock-screen alarm delivery, staff recovery
after notification-permission denial, HyperOS battery-policy behavior and the
same-key signed in-place upgrade; Firebase toggling those states only proves
that the app preserves its registered alarm and recovers after relaunch.
