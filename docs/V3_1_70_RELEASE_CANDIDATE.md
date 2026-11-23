# Build 77 / 3.1.70 candidate

Build 77 retains Build 76's product, tablet rendering and image-security
implementation. Room stays 58, Alembic stays 0105, and dependencies and image
recipes are unchanged. Its functional change is a narrowly verified alternative
to the existing fresh selected-tablet report requirement.

Build 76 / v3.1.69 at `2c9f3e779de4c24078aecb6d351b468e545aabcc`
and all prior signed/tagged source and assets remain immutable. This source
admits the coordinated successor identity only.

## Selected-tablet normal-close admission

The original fresh known-zero Build 71 report path remains. If its report is
older, a fresh readonly repeatable-read snapshot must bind that exact selected
installation and company/terminal to the newest server-created shift, exactly
one committed, completed normal Build 71 Android close, its real closer, matching
shift-open/shift-close UUIDv4 action suffix and recorded opening installation.
The last successful known-zero sync must fall during or after that shift.
Valid ordered timestamps and a fresh server observation remain mandatory; the
qualifying normal close itself has no age cap. The audit timestamp can precede
closed_at within the same transaction and is not incorrectly required to follow
it. Missing or ambiguous proof, unknown/nonzero queues, recovery closure, a
newer/open shift or changed device evidence refuses this alternate path.

Selected snapshots bracket the fleet and global quiescence reports, before
maintenance and again after writers stop. Global live-business guards, exact
runtime/images, Redis retention, unrelated legacy fleet queues, restore and
rollback checks stay intact. No verifier writes a shift, receipt or audit row.

The normal close proves its exact shift only; a historical zero report does not
prove that later local work is absent. Existing compatible offline replay and
signed in-place upgrade preservation remain required. Updates remain optional;
this change does not discard local work, force-install or force-close a shift.

The protected exact-source release workflow is authoritative before signing or
offering; its reviewed tag may start while branch CI is in progress. It must pass
the full repository/backend/frontend suites, both image-store security gates,
Android instrumentation, unit tests/lint and independent reproduction. Signed
upgrade preservation and production, idle-shop and tablet admission remain
required before an offer. Source tests are not a live deployment claim.

## Image security assessment and admission checks

The security-only follow-up retains the exact product versions and tablet
implementation. It adds separate, image-bound OpenSSL execution-path
assessments for the backend, static-Go Caddy, HTTP-only Nginx frontend and
PostgreSQL16/pgcrypto. They retain the original raw scans, unchanged High fail
gate and exact package versions. The backend's separately proven CPython
hashlib backport remains a `fixed` statement. Redis has no new assessment.

Each assessed image requires fresh offline runtime evidence and exact
source/startup/configuration/native-library closure. Final rescans must explain
only the reviewed eight OpenSSL matches per image and, for the backend, the
single proven Python backport finding. All other original records remain;
unrelated or changed findings stay blocking. See the
[backend assessment](../infra/security/vex/README.backend-openssl.md) and
[three separate service assessments](../infra/security/vex/README.service-openssl.md).

PostgreSQL's mutable data-volume configuration is checked separately. The
existing upgrade-runtime probe reads live TLS/preload settings, extensions,
native C-function libraries and role/database overrides before maintenance,
after shutdown and when validating the admitted migration head. Changed or
unavailable state refuses admission; it is not repaired by the verifier.
These source controls still require independent security review, final
exact-source CI and the existing signing/deployment/idle-shop gates.

## Tablet interaction changes

Dynamic glass scenes and Home insets invalidate their actual consumers instead
of unrelated route content. Gaming dial finger positions, loading pulses and
the fresh-login movement update drawing or layers without recomposing their
whole content on each intermediate frame.

Opaque glass derivatives cache the existing scene grade. Fully covered glass
consumers reuse those pixels with the same contrast-admitted tint. Transparent,
partially outside and grade-disabled consumers retain the original fallback.
The managed bitmap admission ceiling remains 64 MiB. Independent color and
projection checks cover the optimized and original rendering paths.

Touch and hover washes are removed from the shared foundation controls, Gaming
dial and POS quantity controls. A stable focus-only foundation indication avoids
native ripple work on the sidebar and header while retaining a visible keyboard
focus outline. Material buttons retain their existing focus indication and
Enter activation. The rejected input-mode-dependent ripple experiment is not
part of this candidate.

Isolated release-like TCL checks exercised actual gestures, ordinary POS,
Gaming, staff and shift controls, pixel comparisons and keyboard focus. Paired
native timing probes found substantially less missed-frame work on the tested
paths. Those probes used the pre-version-bump QA identity. They do not establish
zero stutter, identical Redmi timing, or acceptance of a newly signed Build 77.

Build 77 still requires exact-source CI, independent reproduction, protected
signing, signed in-place upgrade preservation and actual production admission
before an update can be offered. The selected shop tablet must satisfy the fresh report or exact normal-close
admission above, and the shop must be idle under the guarded procedure.

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
selected Build 71 tablet must satisfy the original fifteen-minute sync path or
the exact normal-close evidence contract above. The selected installation, its
newest terminal shift and append-only close audits share one readonly MVCC
snapshot in `verify-build77-tablet-close.sql`. Two unchanged selected snapshots
bracket the global quiescence and fleet reports in each runtime phase.

The Redis section records its container and image IDs, named data volume and
mount, entrypoint, startup command, working directory and ACL checksum. The
verifier checks the exact repair receipt and recreation hold bytes against the
baseline and confirms the prior Redis container differs from the live one. The
readiness verifier reports `production_admission: false` even when they pass.

## Guarded upgrade from Build 71

The installer defaults to refusing Build 77. Its explicit upgrade takes
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
revision. Build 77's Android staging tool reads that receipt and freshly
verifies the four candidate images, schema 0105 and the actual retained Redis.
The protected Owner activation and offered-update checks still apply.

[Build 71 candidate history](V3_1_64_RELEASE_CANDIDATE.md)
