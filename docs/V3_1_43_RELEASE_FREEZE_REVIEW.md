# Build 51 release-freeze review

Build 51 (`3.1.43`) is a separate reviewed overlay on published Build 50,
`29e32b440ab8dc162b6c8f5d40f8283fad53ce04`. It does not replace the Build 25–50
release records. Their literal dictionaries, immutable source objects, historical
scope checks, business assertions and negative mutation tests remain required.

The initial independent product inventory was reviewed at
`99ec526b4427fe815ecee9b8c0a096582f6d0c3d`: 226 paths (105 Android, 69 backend,
44 frontend, four infrastructure, two root tests and two environment/Compose
files). Its independent JSON inventory SHA-256 was
`4a47871d280625c80c48573b472cf380c46211957027f50cb35bb1164fb74e38`.
The final literal `REVIEWED_CODE30_4_BUILD51_SHA256` is the checked-in exact path
and SHA-256 inventory; it is never calculated from the running candidate.
Separately reviewed corrections after that inventory must be recorded before
updating those literal pins. A source hash is review evidence, not deployment,
physical-tablet acceptance, signing approval or a substitute for runtime tests.

## Initial post-inventory native correction

The initial follow-up included exactly three native paths for the real full-shell
inline Shift clipping defect found after the initial inventory:

- `android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/gaming/GamingScreen.kt`
  — SHA-256 `c42c693e69050c76f714081564770fdb0bab289955d067712bedb358c9d9b31f`.
- `android-native/app/src/main/java/cloud/dcompany/erp/ui/screens/shift/ShiftScreen.kt`
  — SHA-256 `8a294ef2f39abb2df30798b366ba7c59547df902d7802ad3902294d85eb60d62`.
- `android-native/app/src/androidTest/java/cloud/dcompany/erp/ui/screens/gaming/GamingInlineShiftWorkspaceUiTest.kt`
  — SHA-256 `d2d9cde08d1440a2e61324c6a873bf1ba0c32ff90ed55d401cfd70961e2d93f5`.

The three-path native repair is committed at
`ea2970f942aa80a2026ec4375937fb89ad340315`; these are its only changes from the
initial inventory source. The existing editor and its booking state remain mounted. The inline Shift form
uses the existing bounded options scroll pane instead of being appended below
all booking options. In panes narrower than440dp the same Open Shift submit
action is placed below the amount entry, avoiding an unreadable header squeezed
by its inline action; permission, busy, duplicate-capture and authoritative
confirmation guards remain unchanged. The permanent ring and pinned action host remain in place;
pricing, selected customer, tariff, player count, command identities and Shift
confirmation rules are not changed. The new real-shell instrumentation case
covers reachable amount/Open controls, keyboard action, Return, exact
Dual/four-player/60-minute draft and unchanged ring/footer bounds. Its fake
command callback is a component proof, distinct from the real shop-free API and
real-keyboard/Return acceptance evidence required before release. The separate
`NATIVE51_INLINE_READABLE_ACTUAL_KEYBOARD_ACCEPTANCE.json` records numeric Gboard
input25, soft Done, one explicit Open submit, server acknowledgement and Return
to the retained station2/Dual/four-player/60-minute₹210 draft with unchanged ring
bounds. This synthetic test is not physical-tablet acceptance or proof that all
font-scale/configuration behavior is complete.

## Final separately reviewed product source

The final product source is
`6863c5be741cacd30f64756081a19974a4fc5d0e`. Its explicit Build 50→51 inventory
contains 232 ordinary hash-pinned paths plus the same three freeze controls.
The earlier 229-path inventory is extended by exactly three new test sources;
no existing path is removed and no control exception is added. This review
record is itself an ordinary pinned path. The checked-in literal map, rather
than a digest of the running checkout, remains the authority for every file.

The reviewed product changes after `ea2970f` are limited to these follow-ups:

- `MainActivity.kt`, `GamingScreen.kt`, `GamingViewModel.kt` and the new
  `GamingDraftAuthorityTest.kt`/`GamingDraftRecreationUiTest.kt` preserve only
  nonsecret booking selections through Activity recreation. A process-bound,
  full user/cache-generation/validated-terminal authority controls the actual
  keyed composition; a missing or blank terminal fails closed. Initial empty
  Room projection is distinguished from a loaded catalogue that removed a
  station. Customer identity remains in memory. The header uses the existing
  readable “Payment due” label. The final recreation fixture mounts its initial
  and recreated content at the same real Activity content call site, retaining
  all assertions without explicit saveable keys or copied state.
- `ShiftScreen.kt`, `ShiftViewModel.kt` and the new `ShiftResultContextTest.kt`
  identify a previous unacknowledged close receipt by its original Shift ID,
  time and count, and explicitly distinguish a different currently open shift.
  Unknown or mismatched terminal state defers presentation. Dismissal captures
  the rendered receipt ID and rechecks its exact cache lease and pending/closed
  row before acknowledgement; it cannot acknowledge a newer receipt instead.
  No receipt is automatically acknowledged or erased.
- `TenderWorkspace.kt` adds the existing attestation text as the checkbox's
  accessibility description. Its value, enablement and mutation callback are
  unchanged.
- `AuditLogPresentation.kt`, `SemanticAuditPresentationTest.kt`, Web
  `audit-presentation.ts` and `audit-presentation.test.ts` give five known
  field/value pairs readable evidence labels and use “Cash”/“UPI” headings.
  Unknown values and free-text notes pass through unchanged; technical evidence,
  backend history, amounts, filtering and outcomes are not rewritten or inferred.

The booking/Shift/accessibility follow-ups were committed at `3e388118`; the
final display-only Audit vocabulary and recreation-fixture correction are
committed at `6863c5be`. All prior inline Shift body, readable title, permanent
dial/footer geometry, tariff and command guards remain in the final source.

The final-source native host record has 1,350 tests in 224 JUnit suites with zero
failures, errors or skips; app/test APK compilation and lint also passed (zero
lint errors, 35 warnings). These are local development artifacts, not release
signatures. The byte-identical final Web source passed all 760 tests in 132
files, type checking, lint, production build and asset verification. Four
compiled Chrome Audit display-fixture cases cover both modes at 1280px and
768px, readable facts, unchanged technical values and Return focus. Fixtures
are not production authentication or business-write acceptance. Final native
component/Activity-recreation and normal-flow execution are recorded separately;
host results do not substitute for those checks or physical-tablet acceptance.

## Historical and current checks

- Build 50's 38 reviewed files are read from its immutable source commit, and
  the historical Build 49→50 scope is compared between the two immutable commits.
- Build 51 has its own Build 50→candidate scope, including untracked files.
  The three existing freeze-control paths remain the only self-hash exceptions.
  The physical-audit identity test and this document are ordinary hash-pinned
  files, not additional control exceptions.
- Every other reviewed current file must be canonical, regular, present and
  byte-identical. Added unreviewed files, removed files, symlinks (even to
  identical bytes) and one-byte changes are rejected. New tests exercise these
  refusals through the actual exact-delta verifier in disposable Git clones.
- All historical `REVIEWED_*` literal dictionaries are compared with Build 50's
  script. The Code 25 baseline still contains exactly 491 test/support sources;
  ordered assertions and the existing skip/xfail/ignore checks remain.
- The existing release-identity-only normalization gains `51`/`3.1.43`.
  It does not normalize pricing, refunds, amounts or other business tests.
- The physical-audit lane remains frozen to Build 44/`3.1.36`; the current
  Build 51 identity is explicitly rejected by its analyzer. This change does
  not claim that the old physical-audit runner accepts or tested Build 51.

## Product inventory review boundaries

The additive inventory covers the approved semantic Audit catalogue and retained
paging, protected immutable tender correction/reconciliation and effective
readers, historical deployment proof, receipt search/investigation, the retained
five-nullable-column Room 55→56 change, durable owner-recovery journal,
truthful device/incident evidence, layout/palette/labels and existing workflow
refinements. Original receipts, physical counts and transaction history are not
rewritten. No old compatibility helper or accepted historical digest is repinned.

The installer additions use separate Build 51 history-proof files; old Build 50
helpers and all golden hashes retain their old contracts. New direct-SQL,
concurrency, snapshot, rollback and upgrade tests are additive. Existing tests
that changed are reviewed below rather than implicitly excused by a new hash.

## Existing test changes and retained assertions

| Existing test source or bounded group | Reason for the reviewed change; assertions retained |
| --- | --- |
| `android-native/.../AndroidReleaseIdentityTest.kt` | Current coordinated identity is 51/3.1.43; historical identities are not normalized in business tests. |
| `android-native/.../core/db/MigrationTest.kt`, `CanonicalReceiptMappingTest.kt` | Five nullable Shift metadata columns and typed receipt evidence survive migration/cache serialization; immutable original receipt values remain unchanged. |
| `android-native/.../core/sync/OutboxWorkStatusTest.kt` | Protected recovery presence/unknown is distinct from automatically uploaded outbox work; corrupt storage is not treated as clear. |
| `android-native/.../ui/PremiumFoundationContractTest.kt`, `ThemeContrastTest.kt` | Exact established Web neutral/brand/status roles and API-qualified launch resources replace the obsolete blue launch value; readability and API floor remain checked. |
| `android-native/.../ui/WorkspaceLeaseFeedbackContractTest.kt` | Newly captured customer-draft acknowledgement adds an exact lease guard; assertions bind the named guarded operations rather than allowing a raw guard-count change. |
| `android-native/.../screens/CanonicalReceiptPresentationTest.kt`, `CanonicalReceiptHistoryDialogUiTest.kt` | Effective versus originally recorded tender and exact configured fraction versus actual charged tax are distinct; receipt amounts and tax math remain retained. |
| `android-native/.../audit/AuditLogPresentationTest.kt` | Semantic actions and retained identity/time facts are readable; missing/observation snapshots never imply a name/role change. Raw evidence remains accessible. |
| `android-native/.../gaming/GamingParticipantAmendmentPolicyTest.kt`, `GamingStationPresentationTest.kt`, `GamingCentreNavigationUiTest.kt`, `GamingBoardUiTest.kt` | Per-station booking state, meaningful active-player labels, inline next actions and corresponding palette/geometry are covered without changing tariff/capacity/payment calculations. The hover fixture has an explicit actual themed background; the rectangular-highlight rejection remains. |
| `android-native/.../gaming/GamingDialogUiTest.kt` | Lazy row selection is checked with fresh semantics instead of a disposed node; selection/quantity alone still dispatch nothing and explicit confirmation dispatches the saved identity once. |
| `android-native/.../reports/ReportsPresentationPolicyTest.kt` | Recorded and reconstructed tender/unallocated evidence are separately labeled, including unknown provenance; report amounts are not recomputed in presentation. |
| `android-native/.../shift/ShiftClosePresentationTest.kt`, `ShiftOverviewUiTest.kt` | Unentered physical count differs from confirmed zero; saved ambiguous count/identity remains frozen. Wide/compact layout and intentional count confirmation remain required. |
| `backend/tests/integration/test_auth_otp.py` | Both semantic success and automatic User update report OTP-approved requester provenance truthfully; OTP/authentication assertions remain. |
| `backend/tests/integration/test_gaming_phase2_contracts.py` | New corroborated semantic Start/Stop companions coexist with canonical proof. Exact original key/request/actor/terminal/snapshot, replay with no extra rows and mismatched/duplicate refusal remain checked. |
| `backend/tests/integration/test_gaming_session_participants.py` | Full 160-character operation identity is retained through stop/replay and Audit links; unchanged cash, bill and order linkage remain asserted. |
| `backend/tests/integration/test_gaming_cleanup_migration_0079.py`, `test_membership_migration_downgrade.py`, `test_pos_refund_migration_downgrade.py` | Explicit reflected historical Core seeds avoid today's semantic recorder columns when testing old schemas. Unknown supplied columns are rejected, not filtered. Original migration targets, invalid writes, guards, downgrade refusal and timing assertions remain. |
| `backend/tests/integration/test_checkout_claim_contention.py`, `test_membership_subscribe_concurrency.py`, `test_pos_refund_settlement.py`, `test_pos_tenant_lock_scope.py`, `test_shift_close_count_validation.py` | Cleanup preserves newly immutable Audit rows and parent collection-fence ordering. Functional contention, refund, tenant and physical-count assertions are retained. |
| `backend/tests/integration/test_gaming_package_amendments.py` | Direct PostgreSQL fixture retains the exact configured local host/port DSN, avoiding an unintended default server; package/refund assertions remain. |
| `backend/tests/integration/test_google_sheets_settings.py` | Observer clears its cached PostgreSQL statistics snapshot before polling a newly opened lock-waiter connection. The lock-wait assertion and bounded timeout remain. |
| `backend/tests/unit/test_accounting_provenance.py`, `test_depreciation.py`, `test_event_ticket_report_integrity.py`, `test_manual_collections.py`, `test_operational_route_integrity.py`, `test_report_roundoff_reconciliation.py`, `test_tip_payouts.py`, `test_tip_refund_ledger.py` | Strict fake result sequences support the additional read-only correction projection. Existing gross/refund/ledger/provenance/roundoff assertions remain; no money assertion is replaced with a blanket stub. |
| `backend/tests/unit/test_audit_request_context.py` | Invalid operation-key boundary becomes 161 after supporting full protocol length160; exact boundary and unsafe context refusals remain. |
| `backend/tests/unit/test_permissions.py`, `test_staff_role_security.py` | Mutating permission partition includes `finance.tender.correct`; protected owner ceiling, tenant isolation and truthful actor provenance remain. |
| `backend/tests/unit/test_realtime_resource_map.py` | Applied/replayed payment versus daily corrections invalidate the exact effective readers/Audit resources; no mutation is inferred from a refresh. |
| `backend/tests/unit/test_shift_open_terminal_locking.py` | Explicit semantic Shift Audit is counted alongside the Shift row; terminal lock, one-capture and real Shift assertions remain. |
| `backend/tests/unit/test_release_contracts.py`, `test_remote_assistance_contract.py` | Only current version fixtures/expected current identity advance to3.1.43; historical upgrade versions and strict installer secret/configuration validation remain. |
| `frontend/src/modules/settings/tabs/SystemHealthTab.test.tsx` | No reporters/stale-only/partial coverage remains unknown, not an all-current or all-clear claim; evidence source and accessible status assertions remain. |
| `tests/test_build50_pending_compatibility.py` | Old Build50 helper bytes and explicit pending-work policy remain pinned; new history wrapper has separate scope instead of accepting new Build51 work through old compatibility. |

New test sources in the literal inventory add negative scope, permission,
expired/revoked confirmation, exact replay, unknown outcome, multiple-tab,
configuration/source race, immutable direct SQL, old backup/changed-OID restore,
malformed journal/file durability, elapsed token expiry, snapshot retention,
IME/layout/rotation, tax precision and device-evidence coverage. Their pins do
not excuse removal or weakening of an existing business assertion.

## Control repair and validation limits

The three blocking CI modules had 80 failures at the initial Build51 source:
46 freeze checks,33 installer-correction checks and one stale current identity.
They compared approved new source with Build50 current-tree expectations.
This repair preserves those historical expectations at their immutable commits
and adds a separately reviewed live inventory. It adds failure-first mutation
checks instead of relaxing byte/scope checks or accepting arbitrary Git diffs.

Local repository-contract validation uses the existing private Python3.14.7
(CI:3.14.8/Linux), exact hash-locked operations dependencies and Bash5.2.37.
A trusted user-owned home checkout is required for staging security fixtures:
`/private/tmp` has a world-writable parent and inherited macOS wheel GID,
correctly rejected by those fixtures. Unchanged tests pass in the trusted path.
The Linux-only `renameat2(RENAME_NOREPLACE)` case remains a platform skip locally
and requires Linux CI; no guard or test was changed to hide that difference.
Final CI, signed artifacts, retained upgrades, physical-tablet acceptance and
user-visible emulator review remain separate release gates.
