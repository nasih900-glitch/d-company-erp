# D Company ERP Android design QA

> Evidence boundary: this document records the earlier `3.0.4` (`5`) Android
> snapshot. Material fixes were made before the source identity moved through
> the earlier schema-37 `3.0.8` (`9`) candidate to `3.0.9` (`10`), so none of
> the older results below count as current-candidate acceptance evidence. The
> code-`10` release gates and signed upgrades must be rerun. It still has no
> physical Redmi Pad 2 acceptance proof and has not been deployed to production.

## Visual target and test environment

- Approved visual reference: `/tmp/codex-remote-attachments/019f6149-4483-7750-9466-b04063d78502/8D369ABB-4F08-47B5-ACA4-2FF03554B80F/1-Pasted-Image-1.jpg`
- Combined reference/implementation comparison: `/tmp/d-company-erp-precision-2026-08-26/final-v304/reference-vs-gaming.png`
- Baseline captures: `/tmp/d-company-erp-precision-2026-08-26/baseline/`
- Final captures: `/tmp/d-company-erp-precision-2026-08-26/final-v304/`
- Platform: Android API 35 emulator, 320 dpi, host-GPU renderer (`Google (Apple)`, Apple M4)
- Viewports checked: 2560x1600 tablet landscape, 1920x1200 compact landscape, 1600x1000 smaller landscape, and 1600x2560 portrait
- States checked: authenticated recovery after emulator reboot, data-filled operational screens, zero-data reports, command dialog with IME, offline banner, and reconnect recovery

## Iterations completed

1. Rebuilt shared tokens and operational components around layered dark surfaces, restrained brand gold, semantic state colours, tabular financial values, and 48 dp touch targets.
2. Reworked the shell, sidebar, header, command search, action bars, cards, tabs, lists, empty states, and dialogs so the same hierarchy is used across modules.
3. Kept a single dominant action per context and moved ordinary money values back to neutral text; green and red now communicate actual positive/negative or success/error states.
4. Corrected dialog modifier ordering and width caps so form, confirmation, and command dialogs remain usable with the software keyboard and at smaller viewports.
5. Corrected Tables filtering so reserved, cleaning, merged, and unknown states no longer inflate Open bills; focused regression tests cover the policy.
6. Restored Reports tickets, profit margin, and cost ratio after the first visual refactor, including truthful zero states and regression tests.
7. Refined Finance colour hierarchy so routine inflows/outflows remain neutral and only operating result uses semantic profit/loss colour.
8. Verified all 18 accessible modules in landscape and portrait, then rechecked POS, Gaming, Shift, Reports, and Settings at 1600x1000.
9. Verified the persistent Offline state, explanatory offline banner, saved-data wording, and automatic return to Online without making a business-data write.
10. Rebooted the saved emulator under host GPU, confirmed authentication/session recovery, measured three cold launches at 252 ms, 224 ms, and 158 ms, and measured representative module navigation at 3.04% janky frames (21 of 690; 99th percentile 32 ms).

## Phase 1 completion gate — global UI

Status: **PASS** on the recorded Android `3.0.4` / code-5 source snapshot at the emulator proof layer.

- Re-inspected every accessible module in the authenticated `3.0.4` / code-5 release build at 2560x1600 px / 320 dpi (1280x800 dp). The final captures are in `/tmp/d-company-erp-final-completion-2026-08-26/phase1-after/`.
- Re-inspected Shift in portrait at 1600x2560 px / 320 dpi and confirmed all denomination controls and close-shift actions remain reachable.
- Re-inspected Login at 1920x1200 px / 320 dpi (960x600 dp) with the software keyboard closed and open. Email/password inputs remain focusable, the form scrolls, the visible action area is reachable, and the password field has an IME Done submission path.
- Corrected destructive-action contrast, warning/error connectivity semantics, neutral informational notices, normal-money colouring, tabular numerals, the undersized brand tagline, loading announcements, and busy-action feedback.
- Consolidated the duplicated Events, Finance, Inventory, and Memberships form/confirmation/picker primitives into the shared UI system. Shared dialogs are width-capped, keyboard-aware, scrollable, and keep validation errors visible.
- Code-5 snapshot validation: debug and Android-test Kotlin compilation, all JVM tests, signed release assembly, and release lint all passed together.
- Code-5 focused instrumentation at 960x600 dp: 10/10 tests passed for money entry, touch-only numeric entry, void reasons, compact dialogs, visible validation, loading/busy semantics, and Gaming start/void dialog input reachability.
- `git diff --check` is clean and no duplicate private `FormDialog`, `ConfirmDialog`, `PickerField`, or `DecimalField` definitions remain in screen files.

Accessibility boundary: automated Compose semantics and contrast checks passed, but TalkBack exploration with a blind/low-vision operator and physical-tablet OEM behaviour remain device acceptance work. They are not represented as emulator-certified.

## Phase 2 completion gate — Gaming

Status: **PASS** on the recorded `3.0.4` / code-5 source snapshot, the disposable PostgreSQL/Redis backend, and the API-35 emulator at 1920x1200 px / 320 dpi (960x600 dp).

- Payment-due cards use a neutral surface, compact amber state, white amount, restrained Void, and dominant gold Send to POS. Available cards use green state and a restrained start action. Active cards prioritise the timer and current estimate while keeping extension/transfer secondary and stop destructive.
- Proved a timed hourly session, free timer extension, authoritative stop billing, Send-to-POS failure retention, same-owner recovery, and successful retry without losing or duplicating the session.
- Proved a fixed-price package start, one paid package extension, station transfer with the original session/package snapshots intact, authoritative stop, and one held POS order for the final package total.
- Proved an open-ended session, stop billing, Send to POS, custom-reason Void, original-amount audit retention, rapid repeated Stop protection, and zero active or unresolved sessions after cleanup.
- Proved offline start and stop with a persistent pending state, explicit offline guidance, reconnect sync, one server session, one final bill, and no duplicate transaction.
- Proved permission parity with a disposable cashier: eight stations remain readable, the app shows a view-only explanation and disables Start, and a direct backend Start request returns HTTP 403 for missing `gaming.write`.
- Corrected already-stamped database compatibility with Alembic 0039. Complete 0038 databases remain a true no-op; legacy stamped databases missing `gaming_sessions.billing_mode` are repaired conservatively and idempotently.
- The compact custom Void flow was visually inspected with Gboard open. Keyboard, Keep session, and Void session remain fully visible and tappable above the IME; the enabled Void action was completed without first dismissing the keyboard. Capture: `/tmp/dcompany-phase2-compact-keyboard.png`.
- Focused Gaming instrumentation: 22/22 passed on `emulator-5556`. Focused Gaming presentation/reason JVM tests: 33/33 passed. Backend Phase 2 PostgreSQL contracts: 68/68 passed; related Gaming tests: 59/59 passed; 0038/0039 migration tests: 5/5 passed. Web tests/typecheck/lint/build also remained green after the shared Gaming changes.
- Production, `emulator-5554`, and the physical-tablet boundary were not touched. Alarm delivery under lock screen/reboot/OEM battery optimisation remains a later physical-device acceptance item.

## Severity review

- P0: none observed.
- P1: none observed.
- P2: Tables filter classification and Reports KPI regression were found, corrected, and retested.
- Remaining platform limit: physical-tablet-specific alarm behaviour under OEM battery optimisation, lock screen, reboot, and permission denial cannot be certified from an emulator. This is a device acceptance item, not an unresolved visual defect.

## Automated and rendered evidence

- JVM suite: 405 tests passed with no failures.
- Android instrumentation: 116 tests reported OK; 114 executed and passed, with two environment-assumption skips for notification posting and exact-alarm deep-idle delivery.
- Recorded code-5 Phase 1 focused instrumentation: 10 tests passed with no failures.
- Release lint: no errors; baseline warnings remain documented.
- Release build: successful for version 3.0.4 (versionCode 5).
- Reference comparison and responsive capture sets were opened and visually inspected after the final implementation, not treated as screenshot-only evidence.

passed
