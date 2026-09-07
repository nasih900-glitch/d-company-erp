# D Company ERP 3.1.14 (code 25) rejected candidate history

Code 25 was rejected after its physical-tablet audit exposed release-blocking
issues. Do not build, stage, activate, advertise, or install it. Its tag and any
artifact remain immutable evidence; the corrective candidate is
[Code 26 / 3.1.15](CODE26_RELEASE_CANDIDATE.md).

Its planned coordinated
release identity is tag `v3.1.14`, Android `versionName=3.1.14`, Android
`versionCode=25`, and database migration head `0071`. The compatibility floor
and fail-safe latest-code fallback remain `8`; production defaults do not
advertise Code 25. No authorised CI-signed Code 25 artifact exists yet.

This document is a release ledger, not an approval. A local emulator-only signed
APK is not release authority. Code 25 is not CI-signed, deployed, staged,
activated, installed on the partner tablet, or accepted on a physical Redmi Pad
2 until the corresponding evidence below exists. Signed Code 21 (`3.1.10`)
remains the immutable same-signing-lineage upgrade predecessor. The
failed-before-signing Code 24 tag remains immutable history and must never be
retagged or have artifacts replaced.

## Included scope

Code 25 carries forward the reviewed Code 24 operational work: authoritative
gaming tariffs and extensions, multiplayer/controller charges, session recovery,
shared-shift close with opener and closer history, held-order discounts,
finance/timezone corrections, diagnostics, offline recovery, and owner-controlled
Android release delivery.

The additional Code 25 work is broader than a dialog-only correction:

- Android Gaming recovery dialogs remain fully reachable with the software
  keyboard visible, use real coordinate taps in instrumentation, preserve
  exactly-once actions, and expose touch targets appropriate for a tablet.
- The public update check is bounded inside the immutable Code 21 timeout. A
  matching production backend continuously maintains a short-lived, success-only
  web/backend identity attestation. Startup warms it before serving traffic;
  activation seeds it only after strict revalidation; observed mismatch,
  timeout, unknown active-release state, or internal cancellation fails closed
  without cancelling the tablet request.
- Release preparation builds from immutable source, records backend/web runtime
  identity, verifies rollback image identity, uses safe root-owned APK staging,
  rejects unsafe SSH destinations, isolates signing secrets, independently
  rebuilds unsigned Android artifacts, and checks dependency locks. Operator
  and backend now share a cross-tested, newline-free canonical manifest
  fingerprint while shell transport framing remains independent.
- Production container bases are pinned by digest. The production Python closure
  is exact-version and artifact-hash locked, and CI exercises that same closure.
- Fresh-volume PostgreSQL readiness distinguishes the temporary initialization
  server from the final PID-1 postmaster before restore or migration work begins.
- The compatibility validator enforces the actual code-8 safe floor.
- Project-local Codex roles route bounded discovery, implementation,
  verification, security, and release-audit subtasks to appropriate models and
  effort levels. They do not change the primary chat model or bypass Codex
  permissions.

## Current evidence ledger

Evidence must be recorded against one exact frozen commit and artifact. The
results below are local source evidence and become release evidence only when
reproduced by protected CI on the final commit:

- Backend: all 71 migrations applied to a fresh database; 1,461 tests passed and
  18 explicitly isolated tests were then run against their allowlisted database
  and passed. The guarded disposable end-to-end workflow passed 182 checks with
  no failures and the test database, Redis databases, SMTP sink, and processes
  were cleaned afterwards. `pip-audit` reported no known vulnerabilities in the
  locked production dependency closure.
- Frontend: dependency install/audit, lint, typecheck, all 462 tests, production
  build, dependency-tree validation, and built-artifact reference verification
  passed. The build contained 105 files.
- Android: clean sequential Play and Direct lint, unit, and APK assembly passed;
  each release variant's unit suite passed 979 tests. The tablet instrumentation
  pass completed 281
  tests with no failures and two intentional alarm-permission skips; the two
  permission-granted/deep-idle alarm cases then passed separately. The formerly
  intermittent Gaming recovery interaction passed 10 consecutive runs at
  2,560 x 1,600 and 320 dpi.
- Release/security: the complete repository contract suite passed 165 tests and
  295 subtests, with the Bash-4 installer fault-injection and real Linux
  `renameat2(RENAME_NOREPLACE)` tests skipped on macOS. The settled focused
  installer/release/staging review passed 89 tests and 114 subtests with the
  same two platform-only skips. ShellCheck, `git diff
  --check`, coordinated version validation, dependency verification, and
  project-local Codex routing validation passed. Independent adversarial review
  found no remaining P0/P1 source-level vulnerability after the immutable
  deployment-snapshot and pinned-Redis corrections.

Backend Ruff and mypy currently remain advisory legacy-quality gates rather than
release gates; their existing backlog is not represented as clean. Docker is not
available on the development Mac, so the immutable container build, Compose/Caddy
validation, SBOM and image vulnerability scans still require protected Linux CI.
The actual Linux `renameat2(RENAME_NOREPLACE)` staging path and the Bash-4
installer fault-injection harness must also run there; macOS tests use a bounded
mock for that Linux-only syscall.

These pre-freeze checks are strong defect-finding evidence, but they are not a
signed upgrade, production deployment, target-Redmi result, or owner activation.
Protected CI must reproduce the required checks for the exact release commit and
the signed artifacts must be verified independently before any update is offered.

The candidate was rejected before these historical gates completed:

1. Fresh migrated database plus the full backend suite and guarded disposable
   end-to-end workflow, including clean-reset evidence.
2. Frontend lint, typecheck, complete test suite, production build, artifact
   verification, and rendered Gaming/POS/Finance/owner-update workflows.
3. Android direct-release unit/lint/build checks, full instrumentation, alarm,
   offline/restart, flicker, frame-time, and crash/ANR checks.
4. Independent security and release review of the complete diff, dependency
   audits, shell/workflow validation, and protected CI for the frozen commit.
5. Reproducible signed artifacts with package/version/hash/size/manifest/signer
   verification and a same-key Code 21 to Code 25 upgrade preserving local data.
6. Matching backend and web deployment after backup-and-restore proof, followed
   by a real Caddy cold/warm Code 21 update poll within its three-second budget.
7. Staging of only the verified CI APK, owner-controlled activation in Web ERP,
   and authenticated partner-Redmi acceptance with Gaming, POS, shift, finance,
   sync, offline recovery, alarm, performance, and pending-work reconciliation.

## Historical rejected delivery sequence — do not execute

1. Freeze and review one exact commit; verify all coordinated metadata with
   `python3 scripts/verify_android_release_version.py --tag v3.1.14`.
2. Reconcile the signed Code 21 tablet without uninstalling, clearing app data,
   or discarding pending offline work.
3. Produce a fresh quiesced backup and prove restoration before deploying the
   matching backend and frontend with migrations and rollback readiness.
4. Build and sign only through the protected `v3.1.14` workflow. Verify the
   immutable package, version, SHA-256, size, manifest, provenance, and expected
   signing certificate.
5. Prove the same-key Code 21 to Code 25 upgrade, then stage the exact CI direct
   APK and manifest using the documented server-update procedure.
6. The bound owner reviews the staged release in Web ERP and chooses when to
   offer it. Android still requires the employee to approve installation.
7. Complete target-tablet acceptance before wider operational use.

No project code may silently install an APK, change the active Codex chat model,
or grant itself filesystem/network permissions.
