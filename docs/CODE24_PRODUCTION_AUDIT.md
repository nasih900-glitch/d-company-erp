# Code 24 production audit — evidence and remaining gates

Audit window: 4–5 September 2026. Candidate: D Company ERP 3.1.13, Android
version code 24. This candidate supersedes the unsigned Code 23 candidate; the
immutable Code 23 tag was not rewritten and its waiting release run was cancelled.

## Decision

**Not yet approved for production activation.** The coordinated local business
workflow, backend reconciliation, offline recovery and web checks passed. Two
physical cloud tablets passed the first functional matrix, but the slower
Samsung tablet exposed a performance concern in aggressive component stress.
A targeted timer optimisation and separated normal-cadence tests passed their
functional assertions and yielded the measurements below. The improvement is
modest, not a no-jank guarantee. Signing, production migration, in-place fleet upgrade and the actual
Redmi Pad 2 acceptance remain separate gates.

No production sale, session, shift, tariff, account, password or payment was
created, changed or deleted for this audit. All test trading used a synthetic
tenant and one Hybrid workspace in a separate local database.

## Reproduced root causes corrected

| Area | Defect | Correction |
| --- | --- | --- |
| Android writes | A timeout, response-decoding or local-confirmation failure could be treated as a rejected operation even when the server might have committed it. | Preserve the original durable operation ID, payment values, stopped time and cash count; replay the same request and explain the uncertainty. HTTP 408 is not a definitive rejection. |
| Finance sync | A successful write followed by a failed refresh could look like a failed write. | Save the authoritative response and synced state atomically before the optional read refresh. |
| Finance allocation | A valid partner-share reconciliation error cancelled the primary P&L refresh and blanked Finance. | Isolate only the known allocation business-rule rejection; commit an explicit unavailable allocation alongside current reports and show its guidance. Authentication, transport and unexpected failures still propagate. |
| Gaming recovery | Invalid timestamps could be replaced with invented current time or lose the configured deadline. | Reject malformed authoritative data safely without changing recorded timing. |
| Diagnostics | Optional monitoring/database/scheduling errors could themselves disrupt the app. | Isolate optional failures; preserve cancellation and acknowledge retained crash evidence only after durable capture. |
| Web realtime | Late callbacks from an old socket could retire the replacement connection. | Bind callbacks to their connection; refresh authoritative data after authentication acknowledgement. |
| Finance UI | An older delayed response could overwrite newer figures. | Shared latest-request fencing and visible stale-data/retry states across Finance and Analytics. |
| Business date | A London browser could show the previous date and zero revenue while Android/Finance showed the shop day. | Use the server's shop day by default; render receipt and Finance dates explicitly in IST. |
| Profit confidence | Missing costs could be presented as a confident 100% margin. | Keep incomplete costing visibly provisional; do not invent a verified profit. |
| Database validation | A valid tip payout failed because a shared trigger referenced a field absent from that table. | Add migration 0067 with table-specific validation; retain tenant, actor and immutable-source checks. |
| Concurrent tip payouts | Two requests could spend the same outstanding tip balance. | Serialize payout and relevant refund checks with the same company lock; reject future or timezone-free payout dates. |
| Financial reports | Several queries could observe different committed states within one report. | Read-only repeatable-read snapshots for financial report GETs; writes retain their existing isolation and locks. |
| Setup and feedback | Empty categories disappeared; payment success could retain an earlier busy error; session stop copy omitted the distinction between session and snack totals. | Keep empty management categories, clear obsolete payment warnings and provide accurate combined-bill guidance. |
| Gaming rendering | A one-second timer invalidated the full station action card. | Keep the clock read in the timer body; outer actions observe only actual status changes, including overtime. |

Detailed backend reproductions and commands are in
[the backend audit](../backend/docs/CODE24_BACKEND_FINANCE_AUDIT.md).

## Completed shared-shift simulation

The rendered web application and native Android API-35 emulator used different
staff accounts against the same synthetic company and Hybrid workspace.

1. Employee 1 opened a shift on web with ₹500 float.
2. Web: Standard Single PS5 30 minutes ₹80, a ₹60 extension and ₹60 drink;
   combined ₹200 cash bill. ₹300 tendered, ₹100 change. A rapid second tap
   produced no second payment. The prepared bill survived reopening.
3. Web: ₹30 crisps, ₹3 discount, ₹27 UPI confirmation; exactly one payment.
4. Android Employee 2 saw Employee 1's open shift and the explicit shared-close
   guidance. Started Standard three-player PS5 30 minutes for ₹130 (dual ₹100
   plus extra controller ₹30); added ₹30 crisps.
5. Disconnected Android transport, stopped the session and killed/restarted
   the app while offline. The same request, fixed price, snack and captured
   stop timestamp survived. The timer remained stopped rather than charging
   until reconnection.
6. Restored connectivity. The original stop synchronised without manual replay
   and without a duplicate session. Sent the combined ₹160 bill to POS, applied
   ₹10 discount and confirmed ₹150 UPI.
7. Employee 2 closed Employee 1's shift at counted ₹700. Web updated without
   manual refresh and showed the original opener and actual closer.
8. Independent PostgreSQL/API reconciliation found exactly three paid orders,
   zero running/paused sessions, zero ended sessions awaiting POS and zero
   unfinished orders. Historical ended sessions and paid bills were retained.

| Reconciliation | Amount |
| --- | ---: |
| Gaming charges | ₹270 |
| Drinks and crisps | ₹120 |
| Discounts | −₹13 |
| Net collections | **₹377** |
| Cash collected | ₹200 |
| UPI collected | ₹177 |
| Opening float | ₹500 |
| Expected / counted drawer | ₹700 / ₹700 |
| Cash variance | **₹0** |

UPI checks were synthetic manual confirmations, not movement of real funds or
payment-provider settlement proof. Profit remains provisional when fixture
cost coverage is incomplete.

Local observed event-to-web-render delays were 228 ms for the Android snack,
301 ms for Send to POS and 428 ms for shift close. These are observations of this
local environment, not production WAN guarantees. The exact offline-stop
transition fell within a web observer restart gap, so no timing claim is made
for that transition.

All four synthetic users logged in and had shared operational/Finance access;
only the designated protected user saw Audit Log. Actual production staff
credential and role acceptance has not been substituted with these fixtures.

## Device diagnostics requested for the new release

The app can passively collect privacy-filtered crash/ANR, API-failure and
sync-stall evidence and send it to web System Health. It is not an automatic
whole-app test runner. It does not create financial transactions, secretly
record screens, or prove that every visible flicker or incorrect price was
detected. Staff can use Help to add context and written feedback.

Two real Android transport errors were captured while offline, survived process
termination/restart, and uploaded on reconnection. The original incident UUIDs
were retained; each had one acknowledged delivery and one server record. A
separate authenticated HTTP retry was deduplicated. Pre-login evidence without
provable account ownership remained quarantined rather than being attributed
to a later employee.

OS crash/ANR history may need the next healthy app launch. Delivery requires an
authenticated connection. The live database was still at migration 0058 and
did not contain the new diagnostic table when inspected; the matching server
release is therefore mandatory before advertising this as live fleet coverage.

## Verification completed

- Backend: **1,314 tests passed, no failures or skips**, fresh isolated
  PostgreSQL migrated to 0067; fatal lint clean.
  A later test-only CI guard correction ran **31 cases, no skips**: thirteen
  database/workflow guard checks plus all eighteen approved-tariff HTTP cases.
  The exact ephemeral `erp_test` CI database is now allowed; an unexpected
  GitHub Actions database fails visibly instead of silently skipping the gate.
- Web: **407 tests in 70 files passed**, lint/typecheck/production build clean.
  Rendered Finance stale-response/503 recovery, repeated payment taps, category
  setup, shared shift and four-user access checks passed. Gaming/POS/Shift/
  Finance at 390×844 had no horizontal overflow or unhandled page error.
- Android source at that audit checkpoint: **934 JVM tests passed in each of Debug and DirectRelease**,
  no failures/errors/skips; debug lint and APK build passed, including the new
  date, timer and Finance allocation recovery tests. Direct-release lint also
  passed on that isolated build.
  The then-current debug APK installed in-place on the existing synthetic
  emulator. The unsigned direct-install release APK built at that checkpoint
  also succeeded: package
  `cloud.dcompany.erp`, code 24, version 3.1.13, 16,433,996 bytes,
  SHA-256 `e98cea17f0ec270be2b3e3d4c47cd4c3e547b3c0145df2315911e3e23dd66deb`.
  The compiled artifact contains the production HTTPS API URL and no local
  `127.0.0.1:8001` debug API URL.
  These are historical unsigned local bytes, not hashes for the later source
  containing migrations 0068–0071 and the current recovery changes, and not a
  partner-installable release.
- Release identity/contracts: **57 tests passed**, tag/version/code agreement
  verified as v3.1.13 / 3.1.13 / 24.
- Physical cloud baseline: **169 tests passed on each device** (Lenovo Tab P12,
  API 35, and Samsung Galaxy Tab A8, API 34). This is real hardware executing
  component/input/Room/auth/sync tests, not an authenticated full business day
  on the partner's Redmi.
- Gaming-performance physical matrix at that checkpoint: **171 tests passed on
  each of the same two device models**, including added timer-only and
  connectivity-only checks. This preceded migrations 0068–0071 and the later
  Finance, catalogue, refund and captured-shift recovery changes. Later
  JVM/build/render checks must not be represented as the same cloud-tested bytes.
- Dependency audits: web `pnpm audit` and backend `pip-audit` reported no known
  vulnerabilities. This is the databases' current result, not proof that no
  undisclosed vulnerability exists. Backend audit ignored incompatible local
  cache entries and completed successfully using refreshed evidence.

## Physical performance evidence

The baseline workload forced 200 combined timer, connectivity and outbox
presentation changes, bypassing normal connectivity stabilisation. It verified
stable board/card geometry and no captured frame of 700 ms or more on either device.
It did **not** establish a completely smooth product.

| Device, baseline combined stress | p50 | p95 | p99 | Frames over rounded 60-Hz budget proxy |
| --- | ---: | ---: | ---: | ---: |
| Lenovo Tab P12 | 13 ms | 22 ms | 35 ms | 32 / 200 |
| Samsung Galaxy Tab A8 | 41 ms | 67 ms | 74 ms | 200 / 200 |

These use `FrameMetrics.TOTAL_DURATION`, rounded to milliseconds, not platform
FrameTimeline missed-deadline attribution. Instrumentation and video collection
affect measurements. The Samsung result is a real warning requiring investigation,
not a passing smoothness claim because a test's frozen-frame assertion passed.

The final matrix retained that same forced workload for comparison and separately
measured sixty nominal one-second timer changes and thirty connectivity changes
at nominal three-second intervals. Actual measured durations were about 63 and
92 seconds because test idleness checks add overhead. Timer text and the
Active → Overtime transition passed; no feedback was hidden.

| Final scenario / device | p50 | p95 | p99 | Over-budget proxy | Frozen frames |
| --- | ---: | ---: | ---: | ---: | ---: |
| Combined stress / Lenovo | 14 ms | 20 ms | 28 ms | 29 / 200 | 0 |
| Combined stress / Samsung | 40 ms | 60 ms | 68 ms | 200 / 200 | 0 |
| Timer only / Lenovo | 22 ms | 35 ms | 52 ms | 34 / 60 | 0 |
| Timer only / Samsung | 20 ms | 35 ms | 51 ms | 44 / 60 | 0 |
| Connectivity only / Lenovo | 28 ms | 37 ms | 39 ms | 26 / 30 | 0 |
| Connectivity only / Samsung | 22 ms | 29 ms | 38 ms | 27 / 30 | 0 |

All settled geometry checks passed. This is a modest reduction in combined
stress tail duration, not proof of consistently meeting a 60-Hz deadline.
There is no pre-optimisation isolated-cadence result, so no before/after claim
is made for those two scenarios. These tests use component fixtures with no-op
actions, not network-driven business workflows or scrolling/startup benchmarks.

Independent baseline video inspection saw no repeated whole-page flash in the
sampled Gaming interval. An animated Samsung system onboarding popup obscured
part of that recording; it is not ERP UI and is a measurement/visual confound.
Blank transitions between separate instrumentation test activities are not
counted as in-flow app flicker. Settled geometry cannot exclude transient flashes.
The user's original WhatsApp clip could not be opened because macOS denied
Downloads access; that permission was not bypassed and a direct visual comparison
is not claimed.

The final Samsung video was also inspected. An all-encoded-frame scene-change
scan found no large whole-frame transition within the continuous Gaming stress,
timer and connectivity intervals; the status change to Overtime was visibly
confirmed. The same system popup remained present. This broad scene-change
heuristic does not establish absence of subtle blinking or dropped display
frames. The full video contains separate test-activity transitions and must
not be presented as a continuous authenticated working shift.

Historical final physical matrix for that checkpoint:
[Firebase Test Lab results](https://console.firebase.google.com/project/erp-15f1617a/testlab/histories/bh.f12d1fd93fce5f96/matrices/6370848612567856080).
The historical baseline and then-final APKs and raw per-device frame files are
retained separately.
The historical cloud-tested app package was `cloud.dcompany.erp.physicalaudit`, version
`3.1.13-physical-audit`, code 24, APK SHA-256
`752761e16b12c3926183332b3aed339ef9d1031e2ce0254103118c88a0d5e4f8`;
its test APK SHA-256 was
`38fcda7a94e9de7dcaf173f4ef710c0bc9870d480ec69ad0dedfa304f9952721`.
These hashes identify only that earlier physical-audit checkpoint. The isolated
test packages cannot update the production fleet and do not contain all current
0068–0071 or captured-shift recovery changes.

## Historical reinstall follow-up before the later 0068–0071 changes

The then-current debug APK preserved the emulator's signed-in account, three receipts,
closed shift, opener/closer and ₹700 zero-variance drawer. The formatted receipt
now displays the verified `05 Sep 2026 · 3:11 AM IST` payment time.

That check also reproduced an Android Finance orchestration
defect: an unconfigured 0% partner-share total correctly rejects the allocation
endpoint, but Android awaited it before committing valid primary P&L and metrics.
The resulting allocation-only error blanked the whole Finance screen. Backend
probes confirmed P&L/metrics/trial balance succeed independently. The native fix
now catches only that known business-rule rejection inside the asynchronous
allocation read. It saves an explicit unavailable allocation in the same Room
transaction as the current reports, replacing any old allocation. Authentication,
scope, network, decoding and unexpected errors are not treated as valid reports.

The corrected debug APK was built in isolated local output, installed in-place
and retested without adding partners or changing business data. Finance displayed
₹377 net revenue, three paid orders and ₹125.67 average bill, with the exact
ownership reconciliation warning confined to allocations and the separate
incomplete-costing caveat still visible. Online process restart, offline process
restart and reconnection retained the same report and warnings in real Room
storage. The Partners tab correctly showed no partners, not invented allocations.

Historical debug APK SHA-256 for that checkpoint, not the current candidate bytes:
`265ccac5970862a1377abbf770cfc449cb67040bd0fd6d321af727bea1ab9ee9`.
It targets the local synthetic API and is not a partner release. A prior build
attempt was stopped after Gradle blocked reading dataless iCloud-conflict generated
Dex files; the successful rebuild used fresh `/private/tmp` output and cache,
leaving source and the existing generated artifacts untouched.

## Production recovery evidence and rollout blockers

Two read-only production snapshots were captured on 5 September 2026: SQL at
00:59:57 UTC and the deployed operational-ledger calculation at 01:01:49 UTC.
Production remained at migration 0058. The server had one active workspace,
zero active/paused gaming sessions, zero ended-unsent sessions and zero open
shifts. Every explicitly queried payment, invoice, orphan-link, tenant/branch
and shift-variance anomaly count was zero. Twelve ended and ten cancelled
sessions, three paid orders and nine void orders remained as historical records.

The deployed derived operational ledger had 127 lines and balanced debit/credit
totals for the one active company. This is not a completeness or P&L classification
certificate. Persisted manual-journal checks were vacuous because there were no
nonvoid manual journals. The snapshots were separate and cannot inspect unsent
actions stored only on the partner tablet. Exact SQL, saved aggregate results
and an executed validation notebook are retained in the local audit output,
outside the source repository; no business row dump or credential was included.

The VPS's latest completed nightly backup at inspection was 3 September
22:00:07 UTC. Its full PostgreSQL archive restored successfully into a newly
created disposable database: migration 0058, 109 public tables. Only that
disposable clone was removed afterwards. The live database was untouched.

The corresponding existing offsite object was downloaded read-only and matched
the restored archive byte-for-byte: 899,912 bytes, SHA-256
`f25016ad98e3b503d4a9fd9048ea876a3f97d078dcf1360a4a97d9142ba5695c`.
This proves that backup's restoration, not a new pre-deployment backup.

The same exact backup was then restored into a separately named disposable
local database and upgraded using candidate migrations from 0058 to 0067.
The clone moved from 109 to 115 public tables. Across 108 pre-existing data
tables, baseline-column row fingerprints and 110 money-column aggregate digests
were unchanged. The explicitly queried anomaly counts were also unchanged.
A clone-only downgrade to 0058 and re-upgrade to 0067 passed with the same
invariants. This older backup contained one open shift and one ended-unsent
session; both were preserved. It must not be confused with the later live
snapshot, which had zero of each. A fresh quiesced deployment backup remains
mandatory because later live changes are outside this rehearsal.
The private local dump and disposable database were removed after checking their
absence. Six pre-existing forward-write check constraints intentionally remain
`NOT VALID`; that catalog flag does not establish violating rows or certify all
historical data. Successful clone downgrade before new-version business writes
does not authorize a live downgrade after such writes.

Remaining gates:

- Complete signed-artifact and real workflow performance acceptance; the local
  unsigned build and component comparison above alone are not that acceptance.
- During the 5 September 2026 audit, the last observed Code 21 production
  heartbeat reported **one pending outbox item**. This is dated historical
  telemetry, not a verified current tablet count. Refresh the same installation
  and reconcile the exact item before rollout. Never uninstall or clear storage
  to force an empty queue. The app's in-place update warning is not the server
  installer's separate zero-outbox maintenance gate.
- Create a new immutable Code 24 release and obtain normal protected GitHub
  signing approval. No signed Code 24 partner APK is currently established by
  this report. Code 23 must not be activated.
- Perform coordinated server maintenance with a fresh quiesced backup,
  restoration check, migrations, rollback and authenticated smoke tests.
  The production-shaped 0058 → 0067 rehearsal above passed; recheck the schema
  and any subsequent production drift before running the live upgrade.
- Verify signed Code 21 → Code 24 in-place upgrade, exact package/signer/hash,
  pending data retention and hosted APK bytes before owner activation.
- Prove real staff logins/roles, authenticated Redmi workflows, normal navigation
  performance, lock-screen/reboot/battery alarm behavior and online receipt of
  diagnostics. Cloud hardware is not the same as the actual Redmi Pad 2.
- SMTP remains unavailable; no new mail-provider credentials or integration was
  introduced. Live Sameer role parity requires the normal authorised Staff
  workflow; historical inactive/review accounts require owner decisions.
- Code 24 product visibility uses migration 0070's stored Gaming Centre
  classification, backfilled from approved legacy category names. After that
  migration, editing a category's display name no longer silently removes its
  drinks/snacks from new-sale surfaces. Verify the expected categories after the
  coordinated upgrade; production at migration 0058 retains the older behavior
  until migration 0070 is deployed.

There is no honest zero-bug, zero-latency or all-device guarantee. Passing tests,
signed bytes, successful deployment and real operator acceptance are distinct
pieces of evidence; none should be substituted for another.

### Safe first recovery check for the currently installed Code 21

Read-only inspection of the actual running backend image found revision
`e134c1a685d925d6cb1ed10d463988bc7f71a381`, not merely the canonical checkout.
It already has a compatibility bridge that includes recently cancelled session
rows in the Code 21 Android unbilled board response, plus the exact-ID endpoint.

The first action should therefore be for the partner to open Code 21 online on
the same account/workspace, open Gaming and Refresh, then inspect the Sync Centre.
If the pending item is the already cancelled session, Code 21's existing local
reconciliation may retire it without a server upgrade or reinstall. Verify a
fresh acknowledged heartbeat and zero pending count before proceeding.

The observed count of one does not identify the pending operation. If it is a
different request or does not reconcile, retain it and inspect its details;
do not discard it, rewrite history or bypass maintenance checks. The cancellation
bridge has a bounded list and is not a general guarantee for every stale queue.

## Addendum — 5 September 2026 final local re-audit and release identity

This addendum records later evidence without rewriting the historical snapshots
above. The current source identity is D Company ERP `3.1.13`, Android code `24`,
and migration head `0071`. Code 24 remains an **unsigned candidate**: it is not a
signed release, deployed backend/web build, staged or active server update,
approved rollout, or partner-installable APK. Code 21 (`3.1.10`) remains the
immutable signed direct-channel predecessor for the required same-lineage
in-place upgrade. Codes 22 and 23 are unsigned superseded history and must not
be activated.

Later isolated checks recorded:

- backend: 1,364 tests passed with zero failures, errors or skips; three
  dependency deprecation warnings remained;
- web source: 434 tests in 73 files plus lint, typecheck and production build;
- Android source: 965 JVM tests in 170 suites, lint with 22 warnings and zero
  errors, and debug/test/unsigned-direct APK assembly;
- Android emulator: a corrected 55-step captured-opening workflow, a complete
  repeated 68/68 working-shift workflow, and a separate 29-step four-network-
  cycle/restart probe;
- Android instrumentation: 278 distinct cases across the default 276-pass run
  with two alarm-assumption skips and a separate granted-permission run in which
  those two cases passed; and
- disposable backup migration: 0058 → 0069 → 0058 → 0069 with original table
  fingerprints and money aggregates unchanged.

At that checkpoint, the disposable production-backup restore evidence stopped
at `0069`; merely adding source migrations `0070` and `0071` did not extend it.
The later fresh-schema rehearsal below proves the migration chain, but a fresh
production-backup rehearsal through `0071` remains a deployment gate. A downgrade rehearsal to
the production baseline is valid only before any post-migration business write;
after a revision-51 cash-expense receipt or other protected new-protocol evidence
exists, the deliberate downgrade guards must refuse destructive rollback.

### Final sealed-source evidence

After the final source freeze, the complete local gates were rerun from one
isolated source copy. These results supersede the earlier addendum counts, but do
not make the candidate signed, deployed, staged, or approved for rollout:

- backend CI-style run: **1,400 passed, 0 skipped, 3 warnings**, **71%** coverage
  in **726.38 seconds**;
- web: lint, TypeScript typecheck, production build, and **455 tests in 77
  files** passed;
- dependency audits: `pnpm audit` and `pip-audit` reported no known
  vulnerabilities;
- Android JVM tests: **978/978** passed for `release` and **978/978** for
  `directRelease`; release lint reported **0 errors and 22 warnings**, while
  direct-release lint reported **0 errors and 20 warnings**;
- unsigned Android artifacts: release APK, 16,466,236 bytes, SHA-256
  `0bb3800d870885f7b7456b2b5cb038beb0fd0c95ba7affddc14cfeefbff1b1a7`;
  release AAB, 16,087,535 bytes, SHA-256
  `68cc4f4c7f0078397135d3231ae64896130ac99213f722254fcf878ee3398f3a`;
  direct-release APK, 16,466,764 bytes, SHA-256
  `596d78af73d224073c1f2784d3d5f1d216fde1d54035c4bc99c1a3cc5a97f621`;
- Android instrumentation: the **280-test** run completed with **278 passes and
  2 expected alarm-permission assumption skips**; the same two alarm cases then
  passed **2/2** in the separate permission-granted run;
- database migrations: a fresh `0001 -> 0071` database contained **116 public
  tables**; the isolated schema round trip completed `0058` (**109 tables**)
  `-> 0071` (**116**) `-> 0058` (**109**) `-> 0071` (**116**);
- focused security, tenant-scope, refund, pause and shift recovery suite:
  **68/68 passed**; and
- root release/CI contract suite: **99/99 passed**.

The rendered-browser retest of the two corrected final-bundle presentation
states also passed against an isolated `0071` database: POS now gives precise
shift-required guidance when no shift is open, and the incoming Gaming-only
bill reports **1 item** rather than zero. The retest then completed the VR bill
by UPI, rendered the receipt in Operations, and closed the synthetic shift at
zero cash variance. Cleanup proved zero open shifts, zero unfinished orders,
zero active sessions, and zero ended-unsent sessions.

A fresh rendered-browser workflow then passed login, ₹500 shift open, fixed
₹80 PS5 session, product add/quantity/required-reason void, reasoned
pause/resume, stop, Send to POS, ₹10 discount, ₹70 UPI payment, receipt,
Finance/Reports reconciliation, zero-variance close, and a second shift closed
by a different non-audit employee. The non-audit employee had no Audit Log
navigation and direct `/audit` access redirected to POS. Final isolated records
had zero open shifts, zero active/paused or ended-unsent sessions, and ₹230 of
payments matching ₹230 of paid orders.

That rendered run also found two presentation defects: a Gaming-only POS bill
label counted zero counter products despite containing the Gaming charge, and a
no-open-shift POS state used a misleading backend-unreachable heading. Both
source defects were corrected and their focused policy tests now pass: incoming
Gaming-only server lines contribute to the bill count, and a valid no-shift
response shows shift-required guidance. Both corrected states were then
verified in the rendered final bundle. One immediate post-receipt transition
briefly showed retryable connection guidance even though the backend recorded
HTTP 200; Retry, a fresh load, and two subsequent route-transition repetitions
all succeeded, with no browser-console error. This non-reproducible observation
is retained in the external browser report rather than called a proven fix.

The later tests do not change the decision boundary. Release still requires a
signed Code 24 artifact from the protected workflow, exact signer/hash/manifest
verification, preserved Code 21 outbox and same-key in-place upgrade proof, a
fresh pre-deployment production backup plus live migration/smoke, and physical
target-tablet acceptance. Emulator and cloud-device evidence do not prove Redmi
Pad 2 smoothness, alarm behavior or absence of flicker.
