# v3.1.42 / Android build 50 release candidate

This is a prepared source candidate. It does not prove signing, deployment,
an update offer, installation, or production behavior.

## Identity and scope

The immutable predecessor is `v3.1.41` / Android build 49 / commit
`a834b63d32c45513ab5c8fd9d4de417acc0a96c6`. The successor coordinates
version `3.1.42` / tag `v3.1.42` / Android build 50. Room remains 55 and
Alembic advances from `0088` to `0089`.

The candidate contains the reviewed resumable Android updater, compact Gaming
layout, realtime liveness, password-recovery guidance, and bounded client
telemetry capacity corrections. Migration `0089` raises only the per-user
installation admission ceiling from 8 to 16; the company ceiling remains 32,
and all existing installation and event evidence is retained. It does not
change other backend business behavior, database columns, serialized offline
action contracts, money rules, tenant boundaries, permissions, audit behavior,
or the client compatibility policy defaults.

## Immutable freeze boundary

Every release through build 49 retains its existing allow map. Build 49 is
verified from its immutable Git object. Build 50 uses a separate current-source
overlay whose exact paths and bytes are reviewed independently. The three
self-referential freeze controls remain excluded from current-tree hashing and
are instead protected by their existing structural invariants.

An earlier APK, manifest, image or release record must never be relabeled as
build 50. Candidate identity in Gradle, backend package metadata, frontend
metadata and production examples must remain coordinated at `3.1.42` / 50.

## Pending-work compatibility boundary

The existing compatibility helpers and installer branches through build 49
remain immutable historical controls. Build 50 adds one separate exact
build49-to-build50 path. Fresh read-only production evidence on 2026-10-17
established the exact build-49 predecessor: source revision
`a834b63d32c45513ab5c8fd9d4de417acc0a96c6`, five running images reporting
`3.1.41` with runtime parity, and Alembic `0088`. The fleet projection contained
12 installations and two unchanged nonzero historic counters. Its all-row
identity is `d741c0d407f2b29c687f7b41ec7276a93f19853e99fc279fb65d0f5aec03d030`;
its nonzero identity is
`5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128`.

The new installer path binds that fleet, the immutable build-49 archive and
backend manifest, the reviewed protected-source delta, distinct scanned image
IDs, and equal effective environments. Both counters remain opaque unresolved
work classified as `unknown_preserved_work`. The path cannot clear counters,
retire installations, inspect or rewrite Room data, widen compatibility policy,
or claim queue replay.

## Remaining promotion gates

Source tests and freeze verification are lower-level evidence only. Promotion
still requires independent source and release review, final commit binding,
protected CI, a newly signed build-50 APK, artifact and certificate identity,
retained-data upgrade verification, authenticated Sync and heartbeat, and the
approved physical-tablet Gaming, POS and Shift workflow. The physical-tablet
acceptance is still required and is not established by this document.
Production deployment and offer activation must use the normal guarded paths
after fresh quiescence, backup, image and fleet gates pass. The latest
preparation snapshot contained zero active games and one open shift, so normal
quiescence still must be established before backup or deployment. Existing
restore-parity checks remain mandatory.
