# v3.1.38 / Android build 46 release candidate

## Status and authority

This document describes a prepared source candidate. It does not establish a
commit, tag, CI result, signed package, deployment, update offer, tablet
installation, or production acceptance.

The immutable predecessor is commit
`ee025b5b78dff7397e16612cad80aebd1a4904d3`, tagged `v3.1.37`, with Android
build 45, Room 53, and Alembic 0086. The candidate coordinates version
`3.1.38`, Android build 46, Room 54, and Alembic 0087. Its release commit is
intentionally absent until the reviewed working tree is committed by the
release owner.

Production update-policy defaults remain minimum build 8 and latest offered
build 8. Preparing build 46 does not advertise or activate it.

## Candidate scope

The patch preserves the released Web navigation and desktop layout and the
approved Android tablet layout. It adds the reviewed monochrome day/night
palette and original gold app mark, durable Gaming participant and drink
review flows, complete retained receipt search and exact refund targeting,
shift collection rails, and a versioned owner-configurable loyalty program.

Loyalty program revisions apply prospectively. Existing point counts remain
unchanged; accepted reservations, settlements, refunds, and receipts preserve
their captured economics. Publication uses company-scoped authorization and
revision compare-and-set guards. Android persists new redemption intent bodies,
program revision, and idempotency key without reconstructing legacy requests.
Rank awards are immutable, awarded once after qualifying paid Gaming activity,
and never demoted by later threshold changes. Hour-based rewards and WhatsApp
automation remain disabled.

Alembic 0087 is an additive child of 0086. It seeds the validated legacy
program as system revision 0 without fabricating a publisher, adds immutable
program, award, reservation, and settlement provenance, and keeps established
missing or corrupt configurations fail closed. Room 54 is an additive child of
Room 53 and preserves existing local orders, payments, caches, and unresolved
actions while adding scoped loyalty program and action state.

## Immutable regression boundary

The regression freeze reads every earlier release map from its immutable Git
object. In particular, the complete build-45 map is verified against
`ee025b5b78dff7397e16612cad80aebd1a4904d3`; it is no longer a mutable
current-tree allowance. A separate build-46 overlay binds every changed and
added candidate path by SHA-256. The freeze control files remain outside their
own map only to avoid a self-referential digest cycle.

The release metadata change is coordinated across Android, backend, Web,
Compose, and environment examples. Production still requires native version
headers. The default update channel remains on build 8 until a signed build 46
is separately staged and deliberately offered.

## Verification evidence

Before the version-only overlay, the application candidate passed the complete
accepted local suites: 1,816 backend tests, 669 Web tests, and 1,191 Android JVM
tests. Android assembly passed, lint reported zero errors and 27 warnings, and
the focused rendered operational tests passed on the isolated API-35 emulator.
Those results are local source and emulator evidence; they are not exact-commit
hosted CI, signing, physical-tablet, deployment, or production proof.

The build-46 preparation must additionally pass the complete repository
contract suite, version/tag coherence check, routing check, regression freeze,
and focused Android release-identity test. Any failure must be investigated;
tests and guards must not be skipped or weakened.

## Pending-work compatibility boundary

The installer contains an exact build-44-to-build-45 compatibility exception
for two preserved historical tablet counters. It does not authorize build 46.
Do not widen that branch, infer those queues are empty, retire installations,
or clear local work. Build-46 deployment remains blocked until a separate
reviewed compatibility proof covers the exact build-45-to-build-46 source,
schema, fleet, image, and pending-state transition, or every reported queue is
authoritatively drained.

The build-46 proof is a server-contract carry-forward only. Production must
remain on loyalty program revision 0 while those historical counters are
carried: revisionless legacy requests are accepted only at revision 0, exact
saved idempotent replays are resolved before current-revision validation, and
accepted historical settlements and refunds retain their captured values.
After customization, fresh revisionless points and named-reward requests are
deliberately refused and require a current client review. This release changes
Android, Room, and points contracts; it does not claim byte-identical tablet
behavior with build 45 or knowledge of either preserved queue.

## Recovery boundary

A pre-upgrade image or backup restore is an installer recovery option only
before any Alembic-0087-era business ingress. The 0087 downgrade refuses every
new non-baseline program revision, rank award, redemption, or settlement,
including new revision-0 facts. Once any such write exists, recovery must move
forward on 0087 or a later reviewed schema. Do not start the build-45 backend
or restore a pre-upgrade database as a routine rollback after ingress.

## Release gates

1. Review and commit the exact candidate without unrelated or generated files.
2. Pass every protected branch job on that exact commit, including repository,
   backend, Web, Android JVM/instrumentation, image, migration, and rollback
   gates.
3. Tag only that reviewed commit as `v3.1.38`; the protected release workflow
   must build and sign Android build 46 without changing source.
4. Verify the manifest, package identity, version, signer continuity, hashes,
   Room 53-to-54 upgrade, retained local actions, and exact replay on an
   isolated same-signer upgrade trial.
5. Require a fresh production quiescence check, pre-ingress backup and restore proof,
   reviewed build-46 pending-work compatibility proof, guarded migration to
   0087, authenticated smoke tests, and owner review before staging.
6. Offer the signed update only after staging remains inactive and every prior
   gate passes. Tablet users still approve installation.

The current production activity and unresolved historical counters prohibit a
cutover. No source preparation step in this document changes that boundary.
