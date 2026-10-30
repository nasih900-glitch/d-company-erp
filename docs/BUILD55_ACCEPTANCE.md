# Build55 requirement and verification map

This maps the agreed scope to inspectable regression sources. A source file's
presence is not a passing result. Final checks must identify the exact source,
APK/signing certificate and runtime; emulator, production deployment and the
shop tablet are separate acceptance states.

| Requirement | Expected behavior | Focused regression sources |
|---|---|---|
| G01 — clock dial |30 at bottom;60 appears after clockwise movement past30; selected tariff/price/Start agree; bounds survive running, paused and stopped states |`GamingDurationDialHitTest`, `GamingBoardUiTest`, `GamingDialogUiTest`, `GamingInlineShiftWorkspaceUiTest`|
| C01 — cancellation and Void |Eligible active/paused/stopped Gaming Void before POS; below 30 minutes reason optional, at or above 30 required; POS-linked void Owner-only; served add-ons retained payable; immutable participant/void/add-on evidence, no queued work erased |`test_gaming_participant_void.py`, `test_gaming_void_policy.py`, `test_gaming_void_retention.py`, `test_gaming_void_retention_migration.py`, `GamingParticipantAmendmentPolicyTest`, `GamingViewModelRecoveryTest`, `GamingSessionAddonDaoTest`|
| C02 — exact session and concurrency |Reason/review revisions retained; immutable durable receipt; lost-response replay and stale Join/Stop cannot resurrect billability; POS handoff has one winner; old workspace callbacks cannot write |`test_gaming_participant_void.py`, `GamingCancellation.test.ts`, `GamingInlineShift.test.tsx`|
| U01/U02 — Gaming workspace |Compact relevant attention; stopped Played/Booked labels; critical warnings and Stop remain visible; no changed dial geometry |`GamingBoardUiTest`, `GamingDialogUiTest`, `GamingRejectedSessionAttentionUiTest`|
| U03 — players/controllers |Primary plus up to3 joining players; deliberate guest identity, Leave/Rejoin and one corresponding charge; capacity/busy/paused/rejected safeguards |`GamingBoardUiTest`, `GamingDraftRecreationUiTest`, `test_gaming_session_participants.py`|
| U04 — inline drinks |Frozen selected item/quantity/price review; explicit one Confirm; catalogue refresh requires renewed review; drafts never move to another session at the same station |`GamingBoardUiTest`, `GamingSessionAddonDaoTest`|
| U05 — POS |Recognizable compact catalogue, retained query/category/cart/scroll, reachable checkout, one payment entry and authoritative receipt; zero-payment completion copy is truthful |`PosEmptyCatalogueUiTest`, `PosReceiptPrintTest`, existing checkout/payment suites|
| U06 — Shift |Expected/Counted/Difference are separate; blank is not counted cash; known saved blockers shown; final server review still required; normal close and permission/origin guards retained |`ShiftOverviewUiTest`, `ShiftCloseSafetyDaoTest`, `ShiftCloseReviewPresentationTest`, existing backend Shift tests|
| U07 — navigation |Real WorkspaceScaffold tab roundtrips retain correct station, cart and Shift count; exact-session drink/player drafts do not leak to replacements |`GamingBoardUiTest`, `PosEmptyCatalogueUiTest`, `ShiftOverviewUiTest`|
| U08 — offline/recovery/update progress |Queued Start permits valid inline guest Join through the existing ordered protocol; unknown work stays; exact running optional Download/Verify remains visible; Pause/Resume stays explicit |`GamingBoardUiTest`, `OptionalUpdateBannerUiTest`, existing downloader/outbox/recovery suites|
| U09/U10 — appearance and feedback |Approved transparent liquid-glass across sidebar/cards/controls/dialogs and additional screens; shared selectable 3840 × 2160 workspace background and original Web logo; Login/Splash 1672 × 941 art preserved; hide/reveal and held-touch sidebar gestures, swipe-up Login handoff; readable colors,48dp controls and duplicate prevention |`WorkspaceGlassUiTest`, `WorkspaceBackgroundUiTest`, `WorkspaceBackgroundPersistenceUiTest`, `LiquidSharedComponentsUiTest`, `ThemeContrastTest`, Gaming/POS/Shift instrumentation|
| P01 — Optional/Required |Exact protected deployment controller; global direct-channel policy with safety floor, CAS and audit; consistent body/426 headers; no arbitrary artifact; monotonic retained policy;1s foreground best-effort polling separate from telemetry |`test_android_update_policy.py` unit/integration, `test_android_update_policy_migration.py`, `AndroidUpdatePolicyPanel.test.tsx`, `ClientPolicyMonotonicTest`, `ClientPolicyPersistenceTest`, `ForegroundCompatibilityPollerTest`, `AndroidUpdatePolicyViewModelTest`|
| P02 — preservation |Exact54/0091 predecessor; full legacy rows/sequence/fleet preserved; three new empty tables (0092 policy plus 0093 void receipts/add-on lines), exact legacy Gaming projection with zero retained links, exact schema/trigger delta; actual five-image/runtime/env proof; stopped backup/restore; forward-only after first candidate writer |`test_build55_pending_compatibility.py`, `test_build55_authenticated_acceptance.py`, `test_build55_transition_state_query.py`; guarded deployment runbook|
| P03 — immutable release |Separate55 allow-map over immutable54; no rewritten historical maps; unexplained changes, missing source, symlinks or extra paths refuse; identity consistent across platforms |`test_code26_regression_freeze.py`, `test_code29_installer_correction.py`, coordinated identity and release-contract checks|

The full regression also covers unchanged pricing, financial arithmetic, tax,
payments/refunds, inventory, authentication, tenant isolation, reports and saved
work. Focused UI checks supplement that suite; they do not replace it.

The final release must additionally prove retained **45→55,49→55,50→55 and54→55**
signed upgrades without uninstalling or clearing storage; slow/interrupted
download, reconnect and byte resume; artifact identity/certificate verification;
and the normal native own-shift→Gaming/players/drinks→POS→cash review→close flow.
Never close or settle unknown review work to manufacture a passing demonstration.

The owner's corrected-emulator approval covers the reviewed core design. The
protected update-control feature, final signed artifact, guarded production
installation, optional banner delivery and physical-tablet outcome still need
their own recorded evidence. No release readiness is implied by this map.
