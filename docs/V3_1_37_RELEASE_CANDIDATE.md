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

## Evidence available before the release commit

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
  the first signed POST reached the receiver but the final redirected response
  was the exact `doGet` health document; replaying the same immutable event
  returned `duplicate`, and the native log retained one accepted row. The
  dispatcher now classifies only that exact health contract as retryable and
  still requires a matching accepted/duplicate event ID and payload hash before
  delivery. This observation used a separate synthetic deployment and workbook;
  production transport was untouched.
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
  state, but that does not replace the missing device fault-injection proof.

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
   and its reviewed reason.
3. Create `v3.1.37` only at the reviewed clean commit. The protected tag
   workflow must independently repeat coordinated identity, source, image,
   migration, instrumentation, reproducibility, signing, and manifest checks.
4. Before deployment, verify capacity, backup and restore evidence, business
   quiescence, tablet pending queues, and the normal build-45 installer path.
   Keep the candidate inactive after deployment until authenticated operations
   and migration health are accepted.
5. Repeat the durable backend-dispatch HTTPS path from the exact candidate so
   the health-response retry, matching duplicate acknowledgement, backoff, and
   attempt-budget state are recorded together. Then separately verify the owner
   workbook and deployed receiver without exposing its URL or secret.
6. Verify the signed build-45 in-place upgrade, offline queue replay, reconnect,
   shift and Gaming workflows, printer behavior, and required permissions on
   the intended physical tablet. Emulator profiling does not satisfy this gate.
7. Offer or activate build 45 only after the owner reviews the exact signed
   manifest and every preceding gate. Production deployment, update offer, and
   physical acceptance remain separate decisions.

Printer, SMTP, the owner workbook, production deployment, signed build-45
artifacts, and physical-tablet behavior are not verified by the evidence above.
