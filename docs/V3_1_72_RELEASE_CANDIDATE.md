# Build 79 / 3.1.72 candidate

Build 79 retains Build 78's features, Android interactions, dependency locks,
image recipes and signed Alpine3.24 OpenSSL 3.5.9-r1 pins. Room remains 58 and
Alembic remains 0105. It adds a narrowly bound vendor-fixed proof for stale NVD
CPE matches against those already patched packages; it does not lower the High
fail gate or change the reported upstream OpenSSL version.

Build 78 / v3.1.71 at `91fd778cb56c1867eaca2eb6a4713607c92e36c8`
and every earlier tagged source, asset and literal freeze map remain immutable.
Its ordinary source/backend/web and Android instrumentation gates passed; the
two image stores still failed on the stale OpenSSL matches. That is not recorded
as a passing complete release. Current staging admits the successor only.

## Vendor fix proof

Alpine's authenticated x86_64 libcrypto3/libssl3 3.5.9-r1 archives record source
commit `0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2`. The vendor security database
lists the backports while the observed Grype database predates the package.
The [scoped proof contract](../infra/security/vex/README.openssl-vendor-fixed.md)
explains the exact four CVEs and eight package records that may be proven
`fixed`. The absent fifth vendor CVE is not an additional exclusion.

The offline verifier runs inside the immutable candidate image with dropped
capabilities, readonly root and individually readonly-mounted archives and
script. It requires the exact public-key SHA256, valid APK signature, matching
package/version/origin/source/architecture, signed manifest, extracted payload
and installed library hashes. Exact archives and unmodified original scanner
reports remain retained. Rotated CDN bytes, changed runtime content, unknown
High findings, duplicate records, extra ignores or altered fixed status refuse
admission. A naturally clean raw scan uses the original path.

The backend's separately verified hashlib source/module/runtime controls and
one-CVE fixed statement remain independent. When that High is present, the
combined final scan must account for exactly eight vendor records plus one
Python record, preserving every other record and the same database identity.
Caddy's reviewed Alpine3.23 r0 `not_affected` path remains separate. Historical
backend/frontend/PostgreSQL r0 proofs are not relabeled or admitted as r1.

Isolated exact Build 78 image collectors and per-image vendor proofs passed.
The integrated scanner, both image stores and all exact-source release gates
remain required before signing or offering this successor. Isolated proof is
not deployment or newly signed tablet acceptance.

## Preserved operations and release gates

The existing Build 77 selected-tablet SQL and close validator are unchanged.
Both fresh known-zero Build 71 sync and its exact normal-close alternative keep
the same installation/company/terminal, newest-shift, actor/action, valid-time,
no-recovery and global-quiescence guards. A normal close proves that shift only;
historical zero-report evidence does not prove later local work absent.
Compatible replay, unrelated retained legacy queues, optional updates, signed
in-place upgrade preservation, exact runtime/Redis retention, restore/rollback
and repeated running/stopped checks remain mandatory. No shop state is changed
by these verifiers.

The protected exact-source workflow is authoritative before signing or offering;
its reviewed tag may start while branch CI is in progress. Full root, backend,
frontend, Android instrumentation/unit/lint, both image-store security gates and
independent reproduction must pass. Production, idle-shop and actual tablet
admission remain separate requirements. Prior TCL motion/focus evidence does
not establish newly signed Build 79 or Redmi acceptance; no zero-stutter claim
is made.

[Retained product and admission details](V3_1_70_RELEASE_CANDIDATE.md)
