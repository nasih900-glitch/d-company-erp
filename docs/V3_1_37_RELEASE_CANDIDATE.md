# v3.1.37 / build 45 release-candidate overlay

## Status and authority

This document records the prepared source identity and release gates for the
quality and daily-collection candidate. It does not declare a release,
deployment, signed package, tablet offer, or production acceptance.

The immutable predecessor is commit
`d915a66c9dee0d22c1a7018e2e79bc8ca9e0d7ab`, tagged `v3.1.36`, with Android
build 44, Room 53, and Alembic 0085. The current working tree coordinates
`v3.1.37`, Android build 45, Room 53, and Alembic 0086. The build-45 release SHA
is intentionally absent: it can be recorded only after a clean candidate
commit exists and the immutable tag points to that reviewed commit.

Update-policy defaults remain minimum build 8, latest offered build 8, and
policy revision 1. Preparing build 45 does not advertise or activate it.

## Candidate scope

The candidate keeps the existing money, stock, payment, idempotency, audit,
tenant, and Google Sheets generation-boundary contracts. It adds bounded
tenant-lock corrections, Web Gaming and shift freshness presentation,
technical-data retention, and one optional daily Cash/UPI collection summary.
Daily summary mode binds one branch, business timezone, start date, and
configuration generation; PostgreSQL remains authoritative. It does not send
individual daily transactions, receipt evidence, customer identity, private
notes, workbook URLs, or signing secrets to Google Sheets.

Alembic 0086 is an additive child of 0085. Android production code and Room
schema remain unchanged from build 44: `ErpDatabase` stays at version 53 with
the existing 52-to-53 migration and schema export.

The candidate also closes the dependency findings raised by the first hosted
CI attempt. It uses the official compatible Capacitor Android 8.5.1, Capacitor
iOS 8.4.3, Axios 1.20.0, Vitest 4.1.11, source-map-js 1.2.2, PyJWT 2.15.1 and
urllib3 2.8.0 updates. The npm and pnpm locks retain their separate frozen-
install contracts. Python production, CI and backup locks change only the
reviewed PyJWT or urllib3 closures applicable to each lock.

The published `braces` 3 line has no fixed release for
`GHSA-vfj7-8cjw-p6xm`. Instead of masking that advisory or forcing an
incompatible major toolchain migration, the candidate carries a private MIT-
licensed derivative of the independently attested
`@dieub/braces-depth-guard@3.0.3-pn.3`. D Company release engineering owns the
local package. Its manifest records the upstream package, commit, registry
integrity, archive digest and every source/result digest. All runtime files
match that source except one reviewed ancestor-depth correction in
`lib/expand.js`; the installed package includes that exact patch and a private
one-line `braces` compatibility adapter. Installation does not depend on
postinstall or other lifecycle scripts. The package must be reviewed against
the upstream advisory on each release and replaced when an official compatible
fix is available.

## Evidence available before the release commit

### Completed e492 CI and subsequent correction

Run `38148940575` used a GitHub merge tree identical to committed candidate
`e492524474397b0e5244a0f78bba243f335d1bf2`. It passed 1,647 repository
contracts, 1,801 backend tests with 87% coverage, 638 Web tests, 1,170 Android
app JVM tests and 7 driver JVM tests. Its full native release assembly/bundle
and lint completed. Production, CI and backup Python lock audits passed.
Inherited advisory checks still report 1,590 Ruff findings, 488 mypy errors
across 54 files and 28 Android lint warnings; they are not clean quality gates.

Both actual Docker stores failed at the same Caddy package check. Its pinned
curl/OpenSSL installation transitively installed official zlib `1.3.2-r1`,
overwriting the earlier reviewed library copy while the check expected r0.
The subsequent bounded correction pins Caddy's r1 package explicitly and
installs/checks the packages before copying and proving the unchanged reviewed
four-patch runtime. The existing image-scoped r0 VEX remains unchanged and is
used only for the other four services. Caddy has no VEX generated, mounted,
supplied to either Grype boundary or retained. Caddy requires a retained Syft
1.42.3 image-ID-bound catalogue proving exactly one consistent r1 APK and a
Grype 0.118.0 report with zero ignored findings,
no residual target CVE, and the unchanged high/critical gate. Both scanner
boundaries consume that SBOM; the other four services retain their exact r0
one-reviewed-ignore contract. A genuine older Syft catalogue advanced to r1
only as a fixture proves parser compatibility, not an actual r1 image. New
hosted builds, scans, migration and rollback gates must pass the final source.
The revised seven-file correction passed an independent source review and
76-test rerun. The current installer contract uses the build-45 action digest
and exact four-service VEX assertions; historical hashes remain unchanged.
Two additional current-action tests preserve all five scanner/image/connection
gates while requiring four VEX inputs and the Caddy SBOM boundary. Their
36-case focused suite and independent review passed. The corrected complete
repository run and exact-source hosted gates remain required.

Android instrumentation failed before discovery with a real startup ANR in a
one-vCPU emulator under severe CPU starvation. Twelve system-app ANRs preceded
the ERP launch. Native source and runner match the earlier passing 407-case
run, but that does not erase the failed gate. A separate first-ever launch of
the exact archived build-45 debug APK completed in 1,963 ms with no ANR on a
fresh, network-disabled API-35 ARM emulator. It used four guest CPUs and about
4 GB actual guest RAM (the emulator increased the requested 2 GB). Different
CPU architecture, emulator version, memory and workload limit the comparison;
it is not a hosted instrumentation, signed upgrade or physical-device pass.
The launch skipped 40 frames and captured only five startup frames, four
janky; it is not a representative steady-state smoothness result.
No speculative Android initialization or test-discovery workaround was added.

Production remains at build 44/0085. The 15:01 UTC preflight found a real open
shift and an unresolved separate build-41/one-pending installation last seen
on 28 September. The owner's uncertainty is not a retirement confirmation;
existing exact pending guards do not cover that installation. Preserve its
work and the current business activity. Production cutover, operations-schedule
installation, owner-receiver activation and tablet offer remain blocked until
their actual gates are satisfied.
A second read-only preflight at 15:56 UTC found the same shift and historical
pending records. Current build 44 had synced at 15:49 UTC with zero pending.

### Earlier local and isolated component evidence

The following is local or isolated candidate evidence. It is useful source and
runtime evidence, but it is not exact-commit branch CI, signed-artifact,
production-deployment, or physical-tablet acceptance.

- Independent review covered the intended backend, Web, cron, Apps Script,
  documentation, and tests before the identity overlay was generated.
- The Web candidate passed 638 tests. Before the HTTPS health-response
  correction, the backend candidate passed a 1,793-test composite after the
  historical 0079 fixture was corrected to run against its intended migration
  state. The final narrow dispatcher and durable-state tests then passed 39
  focused tests with none skipped. The full backend composite must be repeated
  on the exact final candidate; hosted exact-commit CI must repeat every
  repository blocking job.
- A disposable PostgreSQL 16 database migrated from empty to the single 0086
  head. A separate restored backup rehearsal completed 0085 to 0086, the
  guarded 0086 to 0085 downgrade, and the 0085 to 0086 re-upgrade while
  retaining the recorded baseline and isolation checks.
- A separate synthetic Apps Script workbook passed native setup, signature and
  scope checks, duplicate acknowledgement, higher blocked-revision precedence,
  delayed-ready suppression, corrected-revision recovery, owner-only log
  protection, existing history preservation, and the existing D2 formula
  contract. An adversarial native run also proved that blank or invalid
  `ReceivedAt` rows are excluded, cannot become accepted on retry, and that an
  editable protection hole blocks delivery until owner setup repairs it.
  These runs did not modify the business workbook or its production receiver.
- Isolated real HTTPS delivery observed the Apps Script redirect edge directly:
  the original signed daily event returned `accepted`; its first identical
  replay reached the receiver but the final redirected response was the exact
  `doGet` health document; one further bounded identical replay returned
  `duplicate`. The native log retained one accepted row. The dispatcher now
  classifies only that exact health contract as retryable and still requires a
  matching accepted/duplicate event ID and payload hash before delivery. This
  observation used a separate synthetic deployment and workbook; production
  transport was untouched.
- Independent adversarial review found no new health-response blocker. The
  final source passed 35 focused mirror unit tests, an independent inline
  parser probe covering 87 additional adversarial cases, rollback-only
  PostgreSQL proof of the default eight-attempt backoff/duplicate/
  re-finalization contracts, and 23 structural freeze tests.
- The durable HTTPS run also retained a separate unresolved transport event:
  an uninstrumented first connection attempt ended with HTTP 404 and was
  quarantined. Its redirect hop, MIME type, and response body were not captured,
  so its cause is unknown. A bounded direct invocation of the current-generation
  owner retry handler then received the exact signed connection acknowledgement
  and delivered the preserved event. This proves recovery of that isolated test
  row; it does not change 404 classification or establish transport reliability.
- An API 35 Pixel Tablet emulator exercised four actual network loss and
  recovery cycles twice against an isolated 0086 backend. The cold sample was
  185 frames with 7.03% missed and p95 26 ms; the warmed sample was 188 frames
  with 5.32% missed, p95 19 ms, and p99 46 ms. The trace found one-time JIT and
  emulator presentation costs, with no crash, ANR, frozen frame, or main-thread
  Room defect. This did not justify a speculative Android source change.
- On the isolated build-44 physical-audit package, the same installation let an
  owner open a zero-value shift and an authorized manager close it. The durable
  record had distinct opener/closer identities, exactly one close, zero
  variance, and no added orders; the 14-step native flow completed without a
  fatal crash or ANR. A committed server close followed by a deliberately
  dropped client response was not reproduced on-device. Existing Android
  instrumentation and focused backend replay tests cover retained count/retry
  state; this historical build-44 run did not include that fault injection.
- Separately, an exact archive of candidate commit
  `c5c6c498de5d204bd054e6878fa382236dbfd70f` produced a build-45 debug APK that
  exercised the committed-close/dropped-response path on an emulator. The
  first close received upstream HTTP 200 and a verified closed database row,
  then the proxy returned zero response bytes. The app retained the locked ₹0
  count as pending and retried the same body and key; the already-closed server
  state returned successfully, leaving no open shift, exactly one create and
  one close update, and no order or payment for that shift. The app remained
  alive with no crash or ANR. This closes the bounded debug functional case for
  the unchanged candidate components; it is not signed-build, in-place-upgrade,
  physical-tablet, performance or production acceptance.
- Security dependency verification used separate clean npm and pnpm 10.20.0
  installs with frozen locks and lifecycle scripts disabled. Both resolved
  Chokidar and Micromatch to the same guarded implementation and verified the
  installed provenance, runtime and patch digests. The upstream fork suite
  passed 799 cases; independent differential review matched 1,539 bounded
  ordinary/adversarial outputs and confirmed the local ancestor correction
  rejects the reviewed 10,000-parent case. Docker source contracts verify that
  both local packages are copied before `npm ci`; Docker was unavailable on the
  authoring host, so an actual image build remains a hosted gate.
- The dependency-adjusted Web tree passed lint, strict type checking, 103 test
  files with 638 tests, and its production build. Its generated CSS was byte-
  identical to the committed candidate baseline: 53,652 bytes with SHA-256
  `677c5433375e72c10302b766cee24b22fe86b290db303075f5afc688c89d8ba2`.
  Private rendered checks found Gaming, POS, Shift and Customers appearance
  unchanged. Shift showed one expected extra closed record from the separate
  zero-value ambiguity trial while the ₹140 total, zero refund and no-open-
  shift state remained unchanged.
- Fresh npm and pnpm audits report zero high and zero critical findings. The
  remaining report is the same moderate `postcss-selector-parser` advisory
  through Tailwind 3 and postcss-nested 6; its fixed major is outside their
  compatible ranges and is not overridden or waived. Production, CI and backup
  Python lock audits each reported no known vulnerability and exit status zero.
  Focused access, refresh, cookie, OTP, replay, locking, retention, realtime and
  branch tests with PyJWT 2.15.1 passed 32 cases against a fresh PostgreSQL
  database migrated to 0086.
- A final private Web runtime trial used PyJWT 2.15.1 through authentication,
  one 30-minute Station 1 session at ₹80, a 30-minute ₹60 extension, pause and
  resume, transfer to Station 2, fixed ₹140 stop, POS handoff, one ₹140 bill,
  ₹40 Cash plus ₹100 UPI settlement, receipt 00002, and shift close with ₹40
  counted, ₹40 expected and zero variance. This is isolated runtime evidence;
  it is not production or physical-tablet acceptance.
- A final combined acceptance used real HTTPS for one exact signed `accepted`
  acknowledgement and the immutable replay's matching `duplicate`
  acknowledgement, with one accepted native row and one projected ₹42.45
  total. After the real first acceptance, the external harness substituted a
  separately captured real health document for the client response. The
  unchanged dispatcher retained the event as pending with a persisted 30-
  second backoff, and a synthetic clock advanced to its due time for replay;
  this does not prove elapsed worker scheduling. A separate local transaction
  proved the default eight-attempt backoff/quarantine policy with the captured
  health response and then rolled back. The HTTP harness buffered completed
  responses, so this is not separate streaming-boundary proof. Component
  digests matched the current mirror, model and receiver bytes, and the
  synthetic endpoint was archived after readback. The earlier unexplained 404
  remains unresolved. The business receiver, workbook and production
  configuration were not changed.

## Immutable regression freeze

The build-44 map remains byte-for-byte unchanged and is read only from the
immutable `d915a66c9dee0d22c1a7018e2e79bc8ca9e0d7ab` Git object. The build-43 to
build-44 inventory remains exactly the 21 historical mapped files plus the
three canonical freeze-control files.

The separate build-45 map covers every path changed from build 44 except the
three self-referential freeze controls. Current-byte lookup gives build 45
precedence, while all historical maps and compatibility proofs remain bound to
their original Git objects. The skip, xfail, ignore-count, deleted-production-
path, canonical-file, and baseline-order guards remain fail-closed.

The historical pending-compatibility test now reconstructs its candidate from
the immutable build-44 `d915a66c9dee0d22c1a7018e2e79bc8ca9e0d7ab` Git object
instead of the live build-45 working tree. It also proves that live build-45
bytes cannot satisfy the narrow build-44 exception. The verifier, installer,
historical hashes, and accepted source pairs are unchanged.

The historical physical-audit test likewise reads build 44's Gradle identity
from that Git object and proves the build-44 analyzer rejects the live build-45
identity. The physical-audit runner, analyzer, and recorded build-44 result
identities are unchanged; this does not relabel build-44 evidence as build 45.

The build-44 unresolved-pending compatibility exception remains exact to
3.1.36/build 44. Build 45 may use the normal installer path when pending tablet
queues are zero or the existing reviewed historical cleanup-receipt path
applies. Any other nonzero queue is a deployment blocker and requires safe
drainage or a separately reviewed compatibility proof; it must not widen the
build-44 exception.

## Remaining release gates

1. Review this complete working-tree inventory and immutable map, then create a
   clean candidate commit without generated files, secrets, private evidence,
   or business URLs.
2. Run every blocking branch-CI job on that exact commit: repository contracts,
   full backend with PostgreSQL and Redis, Web install/lint/typecheck/tests/build,
   Android lint/build/JVM and API 35 instrumentation, and both production-image
   stores with migrations, health, rollback, SBOM, and scans. Record every skip
   and its reviewed reason. Confirm the local derivative's identity in the
   hosted dependency graph/SBOM and repeat both frozen frontend installers on
   the workflow's Node 22 runtime.
3. Create `v3.1.37` only at the reviewed clean commit. The protected tag
   workflow must independently repeat coordinated identity, source, image,
   migration, instrumentation, reproducibility, signing, and manifest checks.
4. Before deployment, verify capacity, backup and restore evidence, business
   quiescence, tablet pending queues, and the normal build-45 installer path.
   Keep the candidate inactive after deployment until authenticated operations
   and migration health are accepted.
5. The bounded combined transport gate is complete for the unchanged mirror,
   model and receiver component digests. Preserve its manifest during release
   review and repeat it only if those bytes change. Separately verify the owner
   workbook and deployed receiver without exposing its URL or secret; synthetic
   acceptance does not activate or establish production reliability.
6. Verify the signed build-45 in-place upgrade, offline queue replay, reconnect,
   shift and Gaming workflows, printer behavior, and required permissions on
   the intended physical tablet. Emulator profiling does not satisfy this gate.
7. Offer or activate build 45 only after the owner reviews the exact signed
   manifest and every preceding gate. Production deployment, update offer, and
   physical acceptance remain separate decisions.

Printer, SMTP, the owner workbook, production deployment, signed build-45
artifacts, and physical-tablet behavior are not verified by the evidence above.
