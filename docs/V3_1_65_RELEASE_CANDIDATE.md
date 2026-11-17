# Build 72 / 3.1.65 candidate

Build 72 extends the released Build 71 source. It targets Android Room 58 and
backend Alembic 0105.

- Staff can pin POS favourites on a shared tablet and hand over the active till
  without closing the shift.
- Staff can report a mistake on a finalized receipt. Confirmed submissions and
  retries keep their original receipt identity across app restarts.
- The protected Owner reviews the exact receipt using the existing correction
  workflow. Reporting a mistake does not make a payment or change stock.
- Schema 0104 preserves UTC receipt snapshot comparisons for stock restoration
  checks. Schema 0105 adds an append-only receipt mistake report table.

The source is a release candidate. Protected signing, signed upgrade tests,
production upgrade admission, and live tablet readiness are required before
an update is offered.

## Read-only readiness evidence

The populated regression rehearsal restores a Build 71 PostgreSQL 16 backup,
checks the preserved data and catalogue through Alembic 0105, and verifies the
empty-candidate downgrade and re-upgrade. Its retained offline counters are
synthetic.

`infra/scripts/verify-build72-readiness.py` checks a root-owned live capture
against a separately reviewed root-owned runtime baseline, candidate Git commit,
selected tablet installation, and reviewed prior fleet. The verifier checks both baseline files against their independently retained checksums. The
runtime baseline is a schema-version-1 JSON object with the Build 71 source
revision and archive checksum, exact IDs for the five reviewed images, Redis ACL
checksum and checksums for its three repair receipts. The live capture is a
schema-version-1 JSON object with UTC capture time, source and candidate revisions,
source archive checksum, five running image IDs, business quiescence, fleet,
selected tablet and Redis state. The fleet objects use the existing
`verify-build45-pending-state.sql` format for the reviewed 0094 snapshot and
current 0095 capture. Matching legacy nonzero counters remain unresolved and
must be preserved. The live capture must be at most five minutes old and the
selected Build 71 tablet must have synced within fifteen minutes of that capture.

The Redis section records its container and image IDs, named data volume and
mount, entrypoint, startup command, working directory and ACL checksum. The
verifier checks the exact repair receipt and recreation hold bytes against the
baseline and confirms the prior Redis container differs from the live one. It reports
`production_admission: false` even when they pass. The installer remains closed
pending a reviewed production upgrade path that checks live state again and
preserves the running Redis service during upgrade and rollback.

[Build 71 candidate history](V3_1_64_RELEASE_CANDIDATE.md)
