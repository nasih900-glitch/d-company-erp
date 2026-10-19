# v3.1.45 / Android build 53 release candidate

This candidate adds the reviewed Audit evidence, search and optional-update
interaction corrections to the Build 52 source. It does not prove signing,
deployment, an Android offer, installation, physical-tablet acceptance or
production behavior.

## Identity and unchanged schema

The deployed predecessor remains commit
`29e32b440ab8dc162b6c8f5d40f8283fad53ce04`, version `3.1.42`, Android build
50 and Alembic `0089`. Build 51 is cancelled and unsigned. Build 52 was
withheld after fresh QA. This successor coordinates version `3.1.45`, Android
build 53, Room 56 and Alembic `0091`; it adds no database or Room migration.

The Android rollout policy remains latest build 50, minimum build 8 and forced
updates disabled, including the existing withdrawn-build-49 rule. Preparing or
signing Build 53 is separate from offering it.

## Exact compatibility profile

Build 53 has a separate fail-closed profile for the exact deployed Build 50
identity, reviewed 13-row fleet, two unchanged nonzero counters, candidate
version `3.1.45` and `0089` to `0091` transition. The immutable Build 52
profile and every older helper, hash, proof and negative test remain unchanged.

The Build 53 profile loads the Build 52 proof implementation by exact SHA-256
and replaces only successor-specific version, backend-manifest and protected
source pins in a private module instance. The database transition format,
predecessor archive and image identity, raw fleet pin, historical projection,
table and sequence inventories, reserved-schema checks, finance-table checks,
environment comparison and rollback proof are unchanged.

The two nonzero tablet counters remain `unknown_preserved_work`. The profile
does not inspect or drain either queue, normalize its payload, rewrite
telemetry, retire an installation or treat fresh build-45 zero counters as
proof about older work. No version range or generic compatibility exception is
introduced.

The reviewed product delta keeps Build 52 write payload contracts except for
the single-payment invoice Audit flush. Its other backend changes are Audit
read/search and presentation support. This makes the existing server-side
preservation proof applicable, but it does not establish that either opaque
tablet payload will succeed when replayed.

## Promotion boundary

The compatibility CLI remains fail closed until the final protected-source
inventory, backend manifest and release-control overlay are independently
frozen. The candidate commit and source archive are supplied by the release
caller and checked at runtime rather than embedded self-referentially.

Promotion still requires independent release audit, full CI, a newly signed
artifact, retained-data upgrade and rollback checks, authenticated runtime
checks, quiescence, emulator review and physical-tablet acceptance. Production
remains Build 50 until those separate gates pass and an optional offer is
explicitly activated.
