# v3.1.44 / Android build 52 release candidate

This source successor repairs the exact deployed Build 50 to approved Build 51
server transition. It does not prove signing, deployment, an Android offer,
installation, physical-tablet acceptance, or production behavior.

## Identity and unchanged product scope

The deployed predecessor is commit
`29e32b440ab8dc162b6c8f5d40f8283fad53ce04`, version `3.1.42`, Android build
50, and Alembic `0089`. The distinct tag commit with the same source tree is not
accepted as the deployed identity. Build 51 (`3.1.43`) is an immutable,
cancelled, unsigned and unoffered release record. This successor coordinates
version `3.1.44`, Android build 52, Room 56 and Alembic `0091` while preserving
the approved Build 51 Web, Android, Audit, finance and workflow behavior.

The Android rollout policy remains latest build 50, minimum build 8, forced
updates disabled, with the existing withdrawn-build-49 rule. Preparing or
signing build 52 is separate from offering it.

## Exact compatibility repair

The old installer path required a global one-row/one-counter historical scope
before migration and again after migration. The quiesced deployed fleet instead
has 13 installation rows and two unchanged nonzero counters. The new path is
limited to the exact deployed Build 50 identity, the exact reviewed 13-row fleet,
candidate version `3.1.44`, and the `0089` to `0091` transition. Older release
paths and their helpers, SQL, golden values and assertions remain unchanged.

Before migration, with writers stopped, the adapter independently verifies the
actual prior source archive and backend manifest, reviewed candidate protected
source and backend inventories, scanned prior and candidate image identities,
effective environment and rendered Compose configuration. It validates the
historical projection with the immutable strict parser and pins the stable raw
fleet document. Receipt and cache rows may expire normally, so their quiesced
document is validated semantically instead of being treated as a permanent
byte string.

A separate read-only, repeatable-read database snapshot hashes every existing
business table and records every sequence definition, owner, last value and
call state. Audit rows omit only the ten nullable columns added by migration
`0090`; all older Audit fields remain exact. The three objects introduced by
`0091` are separated from the old inventory: the collection fence has an exact
phase-specific schema, and both finance receipt tables must be absent at `0089`
and present but empty at `0091`. Database system, name and in-place OID, every
old row count and hash, all sequence state, the scoped history and the full
fleet must remain exact.

For this exact path, the attested backend image ID is written to a private
single-field Compose override and compared with the base rendered Compose
document. A one-off container uses that immutable image ID, pull disabled, no
dependencies and the literal Python Alembic command. The normal application
entrypoint, seeds, web process and workers do not run until the `0091` database,
history, fleet, schema and sequence proof succeeds.

Rollback recreates and restores the prior logical database from the quiesced
`pg_dump`, so it requires the same PostgreSQL system and database name while
permitting the expected new OID. It then requires head `0089`, exact old table
and sequence contents, absent new reserved objects, the same scoped history and
the same full fleet before any non-PostgreSQL writer or ingress can restart.

## Preserved unknown work and retry behavior

The two nonzero tablet counters remain `unknown_preserved_work`. The adapter
does not claim that either queue was inspected or drained, and it never rewrites
telemetry or retires an installation. Current build-45 zero-counter reporting is
freshness and staff-readiness evidence only and cannot discharge older work.

Migration `0091` adds collection fences and durable correction receipts. Old
writer requests keep their captured tenant, terminal, loyalty, guest, tender,
refund and idempotency evidence. An accepted durable replay may return the
stored outcome after cache expiry. Timeouts, transient server failures and an
in-progress conflict remain pending or ambiguous after transaction rollback.
A definitive conflict can park the original row for explicit staff recovery;
special membership value stages remain ambiguous. Neither outcome rewrites a
refund rail, creates a new key, proves a payout, or promises success for an
opaque payload. Lost replies, lock conflicts and rollback failures cannot open
a second admission route.

## Freeze and promotion boundary

The compatibility CLI remains fail closed until an independent freeze records
the final protected-source and backend inventories and enables the candidate
gate. The candidate commit and source archive are supplied and checked by the
release caller; they are not self-referential literals inside a file contained
by that commit. Protected CI and signing bind the final whole commit after this
review.

Promotion still requires independent source review, final CI, newly signed
artifacts, retained-data upgrade and rollback checks, authenticated runtime
checks, normal quiescence and the approved emulator and physical-tablet
acceptance. None of those gates is satisfied by this design record alone.
