# v3.1.40 / Android build 48 release candidate

This is a prepared source candidate. It does not prove signing, deployment,
an update offer, or installation on a staff tablet.

## Identity and scope

The predecessor is published build 47 / version `3.1.39`, commit
`986584a4e24dc6ab777ab89afe4f6fec374229c6`, Room 55, and Alembic `0088`.
Its source, compatibility proof, APK, tag and manifest remain immutable.
The successor coordinates version `3.1.40` / tag `v3.1.40` / Android build 48.
Room remains 55 and Alembic remains `0088`; backend and Android business
contracts, serialized offline actions, billing and financial guards are unchanged.

The product changes repair Web Gaming spacing and customer disclosure while
preserving the existing Web sidebar and grid. The customer summary opens
Details; the existing inner Change action still clears the selection.

The runtime image fixes copied public assets that were unreadable by nginx's
unprivileged worker. Only the public document root receives directory mode
0755 and file mode 0644. Private release source, secrets, backup and evidence
permissions remain unchanged. A new installer gate reads the six reviewed
public assets through internal nginx HTTP and checks exact source bytes and
MIME type before public ingress opens. Redirects, unexpected framing, errors,
oversized responses and timeouts fail closed. The existing external HTTP
asset check remains part of the production-image smoke test.

The exact `/manifest.webmanifest` nginx route supplies
`application/manifest+json`, disables stale caching and returns 404 for a
missing file instead of the SPA. The shared HTTP checker rejects generic
JSON or octet-stream responses even when their bytes match the manifest.

## Exact compatibility and rollback boundary

The additional normal-installer branch is only published build47 / `3.1.39` /
commit `986584a4e24dc6ab777ab89afe4f6fec374229c6` / `0088` to build48 /
`3.1.40` / `0088`. It binds the immutable prior source/archive/backend
manifest, the exact reviewed candidate bytes, scanned and running image
identities, effective environment and current fleet projection. Historical
build45-to-build46 and build45-to-build47 branches are unchanged.

The preparation snapshot contains 11 installation records and two historic
pending counters. Both remain opaque and unresolved. The strict helper
rejects fleet drift, changed pending counts or installation-guard definitions;
it never clears counters, retires installations or claims queues drained.
Source contract checks do not establish an old tablet's queue contents or a
new behavioral replay result. Existing guest-client 426 and loyalty revision
rules are retained; preparing build48 does not widen client policy defaults.

The installer must capture fresh business quiescence and fleet evidence,
maintenance acknowledgement, verified final quiesced database backup and image
attestations. The public-asset gate executes before tariff application and
before public ingress under the existing pre-ingress rollback trap. A failed
gate must not be bypassed with direct Compose, copied artifacts or permission
changes on the running container. After new ingress, preserve the database
and recover forward under the existing post-ingress controls. Alembic `0088`
downgrade remains prohibited.

## Freeze and remaining promotion gates

Published build47's allow map is now checked against its immutable commit.
Build48 has a separate current-source overlay, including the Web tests,
public-asset checker and transport/parser tests. Existing historical maps
and compatibility helpers are not rewritten.

The corrected Web preview must be shown before declaring this visual fix
complete. Independent source/security review and final coordinated checks
must bind the final commit. A new protected release must produce build48
with production endpoints and the production signing identity; artifact,
manifest, certificate and in-place retained-data upgrade proof are separate
gates. Existing build47 artifacts must never be relabeled as build48.

Production installation uses only the normal installer after fresh live
safety gates. Any release staging starts inactive; an authorized owner offer
and authenticated staff-tablet update, Sync and workflow evidence remain
separate from server acceptance. Local mocks, CI, an emulator, or a visible
offer alone are not physical-tablet evidence.
