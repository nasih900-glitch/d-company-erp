# D Company ERP — current project state

Updated 2026-09-14. This file records the verified state of the existing ERP and the targeted Code30.1 patch. It supersedes older “current candidate” statements, but it does not rewrite the immutable history of earlier tags, APKs, deployments or trial evidence.

## Architecture and supported applications

- **Backend:** FastAPI/Pydantic 2, async SQLAlchemy, PostgreSQL 16, Alembic and Redis. Tenant, company, branch, terminal and actor identity are carried through the API. Financial writes use idempotency and integer minor units.
- **Web:** React 18, TypeScript and Vite using the shared backend API. The Web client requires a live connection; it does not have the Android durable offline queue.
- **Android:** native Kotlin/Jetpack Compose app in `android-native`, with Room caches, durable queued writes, explicit sync/reconciliation and an optional direct-install updater. This is the only supported Android source.
- **Operations:** Docker Compose builds PostgreSQL, Redis, backend, frontend and Caddy. The guarded VM installer performs provenance checks, quiesced backup/restore verification, migrations, health checks and rollback preparation.
- **Outside this release:** the archived Capacitor Android/iOS shells and Tauri desktop wrapper are not supported release clients. There is no supported native iOS release in Code30.1.

## Repository and candidate identity

| Item | Current value |
|---|---|
| Authoritative checkout | `/Users/mohammednasih/.codex/worktrees/d-company-erp-code30-trial-patches-20260914` |
| Branch | `codex/code30-trial-patches-20260914` |
| Candidate base | `e27ab8e5eaffc83fe639b9dd0f4b61f07dbc2c60` (`v3.1.27`, cancelled before signing) |
| Intended patch tag | `v3.1.28` |
| Public label | Code30.1 |
| Product / Android identity | `3.1.28` / build `36` / package `cloud.dcompany.erp` |
| Android database | Room schema `48` |
| Backend database | Alembic head `0073` |
| Current production predecessor | Code30 `3.1.25`, Android build `33`, backend migration `0072` |

The source containing this file is the reviewed cumulative Code30.1 patch and its tests. All local release gates listed below have completed. No reset, revert, unrelated refactor or production deployment is part of this work. Tags `v3.1.26` and `v3.1.27` are immutable failed/cancelled attempts and must never be moved or reused.

## Completed and known-good functionality

The existing ERP includes authentication and role permissions; POS orders, payments, receipts and refunds; table operations; menu and recipes; FIFO stock and stock movement; shift opening, closing and cash reconciliation; gaming stations, sessions, extensions, pause/resume, POS handoff and billing; finance, accounting, reports, staff, audit, diagnostics and the Android updater.

The cumulative customer and pricing behavior remains in Code30.1:

- Staff can search saved customers by name or phone and reuse a stable customer record when starting later gaming sessions. Anonymous starts remain valid.
- Customer playtime totals and the leaderboard are available. Reward estimates and WhatsApp messaging remain inactive drafts; no free play is granted and no message provider or company number is configured.
- Standard/Premium choices remain removed. The verified prices are Single ₹80/30 min and ₹120/hour; Dual ₹100/30 min and ₹150/hour; Racing Sim ₹70/15 min, ₹100/30 min and ₹180/hour; VR Games ₹80/15 min, ₹120/30 min and ₹200/hour; VR Racing Sim ₹100/15 min, ₹140/30 min and ₹250/hour. Existing extension and additional-controller rules remain unchanged. VR Racing Sim is a mode on Racing Simulator 1.
- Historical orders and sessions retain their captured price and identity snapshots.

The Code30.1 patch adds only corrections found during the trial:

- A Stop rejected because the tablet clock was in the future can be retried after the clock is corrected without recreating the session. Ambiguous network failures retain the original captured timestamp.
- Gaming attention includes rejected Start and Stop actions and opens the affected station without silently replaying a mutation.
- Customer deletion advances a tenant-scoped directory revision. Stale offline name/phone creation, linking or edit actions cannot recreate deleted personal information after reconnect; fresh intentional creation remains possible after observing the new revision.
- Routine operations resolve the exact open shift for the company, branch and terminal. A user with the appropriate routine permission can continue another authorized user’s work: create and bill POS orders, extend/stop/send/pay gaming sessions, and close the exact shift. Sensitive refund, void and privileged discount operations retain their separate permissions.

## Verified business workflows

The complete isolated gaming trial produced nine settled receipts totalling **₹599**: ₹196 cash and ₹403 UPI. It covered all five gaming modes, a real 15-minute expiry, a paid offline extension, paused-time exclusion, an offline Stop across restart, a lost payment acknowledgement followed by idempotent recovery, hourly billing, an additional controller and the future-clock recovery. The shift closed with expected and counted cash of ₹696 and zero variance. Reports, database rows, receipts, payments and stock agreed; every station became available and there were no duplicate payments, receipts, extensions or stock deductions.

A separate two-user acceptance flow verified the user’s shared-operation rule. The owner opened the shift and started a session. A manager extended and stopped that session, sent its ₹80 + ₹60 bill to POS and paid the single ₹140 order, then closed the owner’s shift at expected/count cash ₹640 with zero variance. The manager opened a second zero-value shift and the owner closed it. Both shifts and the session retained the actual opening, closing, stopping, handoff and payment actors. Final state was zero open shifts, active sessions and blocked stations.

Restart, force-stop, temporary disconnection and reconnect were exercised during active sessions and payment recovery. Session identity, captured time, billing and idempotency remained consistent between Android, Web and the isolated backend. Runtime checks found no application crash or ANR in the accepted flows.

## Test and audit status

| Gate | Verified result |
|---|---|
| Backend | 1,514 passed; 2 warnings; fresh disposable database migrated through `0073` |
| Web | 507 tests in 85 files; lint, typecheck and production build passed |
| Android JVM | 1,072 tests in each of debug, release, direct-release and physical-audit variants, plus 7 audit-driver tests; 4,295 total, zero failures |
| Repository release contracts | 674 passed, 2 environment skips; regression freeze preserves 491 baseline files |
| Android release lint/build | Release and direct-release lint passed; release APK/AAB and direct-release APK assembled successfully. These local packages are intentionally unsigned and are not distributable. |
| Android API-35 instrumentation | 322 cases completed: 320 passed, 2 permission-gated cases skipped, zero failed; both skipped notification/exact-alarm cases then passed with the required permissions granted |
| Dependency audit | Python lock files have no known vulnerabilities; Web production dependencies have none. Two moderate findings remain only in the Vitest development toolchain; the available fix is a major test-runner upgrade and is deferred from this business patch. |
| Production Compose | Could not be rendered locally because this Mac has no Docker CLI. The tagged release workflow contains this gate and two independent production-image lanes. |

The active isolated cleanup target is migrated through `0073` and contains zero customers, orders, lines, payments, refunds, expenses, shifts, gaming sessions/extensions/bookings, journal entries and stock movements. It has zero active sessions, open shifts and blocked stations while preserving one configured user and role, 17 gaming packages, 9 stations, 6 menu items and 2 opening stock batches. Android then passed a clean offline restart and reconnect without deleted customer records reappearing. Production was not used for this trial or cleanup.

## Unfinished, disabled and untested areas

- The signed `v3.1.28` direct APK, its independent checksum/signature verification and the exact signed predecessor-upgrade test are still release gates. A locally built unsigned APK is not distributable.
- The matching backend migration has not been deployed, the APK has not been staged in the ERP update registry, and no tablet update has been offered.
- Redmi Pad 2 acceptance, real printer output, physical keyboard behavior, background alarms under the vendor battery manager and a full elapsed shop-day trial remain untested because the tablet is unavailable.
- Actual payment-provider settlement was not exercised; cash and UPI were recorded as synthetic ERP test payments. No real transfer was sent.
- WhatsApp automation, company-number/provider setup and reward redemption are deliberately disabled pending partner agreement.
- The documented future `declining_balance` depreciation method is not implemented. Current supported depreciation behavior is unchanged.
- Native iOS distribution and archived client shells are outside this release.

## Regression protection and decisions that must be preserved

- Never select an arbitrary first open shift. Resolve company, branch and terminal exactly, retain installation binding, and preserve actor attribution.
- Keep routine cross-user operations available to users who hold their required permissions. Do not widen sensitive refund, void or privileged discount permissions as a shortcut.
- Keep all money as integer minor units and preserve GST, pricing, rounding, cash-drawer, double-entry and immutable snapshot contracts.
- Preserve idempotency, checkout claims, offline captured timestamps, queue request identity and one-time stock/payment effects.
- Preserve company/branch/terminal isolation, RBAC, audit rows, soft-delete/tombstone history and monotonic customer deletion revisions.
- Do not activate rewards, WhatsApp, pause, minimum-version or update-offer policy implicitly.
- Do not rebuild or overwrite an immutable tag or signed APK. Every Android successor requires a higher integer build and must prove same-signer in-place upgrade continuity.
- Do not restore archived trial databases or stale emulator images into an active environment without applying and verifying cleanup again.

## Release readiness and next steps

Code30.1 is a tested source candidate, not yet a distributable or production release. Complete these gates in order:

1. Commit the exact reviewed source, create immutable tag `v3.1.28`, and run the normal GitHub release workflow including Compose, image, reproducibility and protected signing gates.
2. Download the exact signed direct APK, verify package/build/version, SHA-256, size and established signer, then prove an in-place signed Code21-to-build-36 upgrade on the isolated API-35 emulator without clearing data.
3. Give that exact verified APK to the user and leave it installed on the emulator for trial.
4. Separately deploy the matching backend/migration with the guarded installer, verify preservation, stage the same APK inactive, and offer it only after the user requests that action.
5. Record physical-tablet installation and the partner’s real shop-day acceptance before calling the rollout fully accepted.

Detailed trial and cleanup evidence is under `/Users/mohammednasih/Documents/Codex/2026-09-12/this-is-a-continuation-of-my/outputs/code30-full-trial-20260914`. `docs/CODE30_1_PATCH_CANDIDATE.md` is the concise immutable-candidate ledger.
