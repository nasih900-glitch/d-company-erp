# v3.1.41 / Android build 49 release candidate

This is a prepared source candidate. It does not prove signing, deployment,
an update offer, installation, or production behavior.

## Identity and scope

The immutable predecessor is `v3.1.40` / Android build 48 / commit
`4058fb29e70789a9bb3fa62b2d580330b25db8a3`. The successor coordinates
version `3.1.41` / tag `v3.1.41` / Android build 49. Room remains 55 and
Alembic remains `0088`.

The candidate contains the reviewed Web and Android staff-workflow corrections
for Gaming, POS, Shift, Customers, Inventory, Finance, Reports and Settings.
It does not change backend business behavior, database schemas, serialized
offline action contracts, money rules, tenant boundaries, permissions, audit
behavior, or the client compatibility policy defaults.

## Immutable freeze boundary

Every release through build 48 retains its existing allow map. Build 48 is
verified from its immutable Git object. Build 49 uses a separate current-source
overlay whose exact paths and bytes are reviewed independently. The three
self-referential freeze controls remain excluded from current-tree hashing and
are instead protected by their existing structural invariants.

An earlier APK, manifest, image or release record must never be relabeled as
build 49. Candidate identity in Gradle, backend package metadata, frontend
metadata and production examples must remain coordinated at `3.1.41` / 49.

## Pending-work compatibility boundary

The build47-to-build48 compatibility helper and installer branch remain
immutable historical controls. Fresh read-only production evidence on
2026-10-16 established the exact build-48 predecessor: source revision
`4058fb29e70789a9bb3fa62b2d580330b25db8a3`, five running images reporting
`3.1.40` with runtime parity, and Alembic `0088`. The fleet projection contained
12 installations and two unchanged nonzero historic counters. Its all-row
identity is `d741c0d407f2b29c687f7b41ec7276a93f19853e99fc279fb65d0f5aec03d030`;
its nonzero identity is
`5a1100a5695e997be523eb7a948d06db0fd79fed1a4f3a5f48c3286c86ea5128`.

The normal installer therefore has one exact build48-to-build49 server-only
compatibility path. It binds that fleet, the immutable build-48 archive and
backend manifest, the reviewed protected-source delta, distinct scanned image
IDs, and equal effective environments. Both counters remain opaque unresolved
work classified as `unknown_preserved_work`. The path cannot clear counters,
retire installations, inspect or rewrite Room data, widen compatibility policy,
or claim queue replay.

## Remaining promotion gates

Source tests and freeze verification are lower-level evidence only. Promotion
still requires independent source and release review, final commit binding,
protected CI, a newly signed build-49 APK, artifact and certificate identity,
retained-data upgrade verification, authenticated Sync and heartbeat, and the
approved physical-tablet Gaming, POS and Shift workflow. Production deployment
and activation must use the normal installer after fresh quiescence, backup,
image and fleet gates pass. The same production read established three active
games and one open shift. Those rows do not qualify for an exception: normal
quiescence must be re-established before backup or deployment, and the existing
restore-parity checks remain mandatory.
