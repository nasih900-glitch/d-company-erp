# Build 73 / 3.1.66 candidate

Build 73 carries the Build 72 features and corrects the frontend build toolchain.
The v3.1.65 tag records the failed candidate.

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
baseline and confirms the prior Redis container differs from the live one. The
readiness verifier reports `production_admission: false` even when they pass.

## Guarded upgrade from Build 71

The installer defaults to refusing Build 73. Its explicit upgrade takes
`--maintenance-confirmed --build72-upgrade-evidence /absolute/private/manifest.json`.
This path requires existing Build 71 source, PostgreSQL schema 0095, and all
five reviewed running images. The manifest must be a canonical root-owned
0600 regular file in a root-protected directory. It has these fields.

| Field | Value |
| --- | --- |
| `schema_version` | `1` |
| `candidate_revision` | Exact candidate Git commit |
| `candidate_source_archive_sha256` | Independently reviewed SHA-256 of `git archive --format=tar` for that commit |
| `tablet_installation_id` | Selected current Build 71 Android installation UUID |
| `owner_credential_file` | Absolute protected input for the existing short-lived Owner probe |
| `reviewed_runtime`, `reviewed_runtime_sha256` | Absolute path and exact checksum of the reviewed Build 71 source, image and Redis baseline |
| `reviewed_prior_fleet`, `reviewed_prior_fleet_sha256` | Absolute path and exact checksum of the accepted predecessor fleet snapshot |

The installer reads Docker and PostgreSQL directly before maintenance and
again after stopping Caddy, backend and frontend. It seals the retained Redis
process, container, local named data volume, network and ACL evidence. It also
seals the existing PostgreSQL volume and database identity and verifies Caddy's
certificate, configuration and Android file mounts before ingress opens. The
quiesced dump must restore into a disposable database with exact data and fleet
identity before the candidate can attach to the live data volume. The 0095 to
0105 migration verifies the reviewed catalogue delta, empty new ledgers and
preservation of historical rows, sequences, and fleet evidence.

The existing Redis container remains running across candidate and rollback.
Compose starts only the explicit application services with `--no-deps`. The
Redis repair hold continues to apply to future Docker operations. The installer
verifies the signed-in Owner, internal health, and exact candidate runtime and
Caddy image before starting public ingress. It can restore the verified
quiesced backup before candidate writers start. A later failure keeps the
current database and closes ingress for recovery.

The successful guarded installer writes a protected receipt keyed by Git
revision. Build 73's Android staging tool reads that receipt and freshly
verifies the four candidate images, schema 0105 and the actual retained Redis.
The protected Owner activation and offered-update checks still apply.

[Build 71 candidate history](V3_1_64_RELEASE_CANDIDATE.md)
