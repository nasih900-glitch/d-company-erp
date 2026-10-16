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
immutable historical controls. A build48-to-build49 normal-installer exception
is not included in this source preparation because current production fleet,
pending-counter, running-image, effective-environment, source-archive and
backend-manifest evidence is unavailable. Reusing the prior snapshot would
misrepresent current fleet state.

If production still reports pending tablet work, the installer must fail
closed until a new exact helper is reviewed against fresh read-only evidence.
That helper must bind the immutable build-48 source and archive to the exact
build-49 source bytes and current fleet projection. It must not clear counters,
retire installations, inspect or rewrite Room data, widen compatibility policy,
or claim queue replay. If no pending work exists, the existing strict drained
path may proceed only after the installer captures its normal fresh safety
evidence.

## Remaining promotion gates

Source tests and freeze verification are lower-level evidence only. Promotion
still requires independent source and release review, final commit binding,
protected CI, a newly signed build-49 APK, artifact and certificate identity,
retained-data upgrade verification, authenticated Sync and heartbeat, and the
approved physical-tablet Gaming, POS and Shift workflow. Production deployment
and activation must use the normal installer after fresh quiescence, backup,
image and fleet gates pass.
