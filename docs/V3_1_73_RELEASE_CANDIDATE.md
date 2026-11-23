# Build 80 / 3.1.73 candidate

Build 80 retains the product and Android interactions of Build 79. Room remains
58 and Alembic remains 0105. Its dependency change upgrades the exact PostgreSQL
16 production and hosted integration image from 16.15 to 16.16, using the
reviewed official Alpine3.24 manifest digest. No new database migration, role,
payment or tablet interaction change is added.

Build 79 / v3.1.72 at `9cecce5b035d0341ad390d9818a9f8162edce2d6`
and all earlier source/tags/assets and literal maps remain immutable. A newly
refreshed scanner database removed the already vendor-patched OpenSSL CPE rows
but reported PostgreSQL 16.15 security findings on both image-store lanes.
They are addressed by the official dependency update, not a PostgreSQL exception
or reduced scanner threshold. All exact candidate images still require fresh
raw scans and the complete release gates.

## Same-major PostgreSQL security transition

The [official 16.16 release notes](https://www.postgresql.org/docs/release/16.16/)
describe security fixes and composite-type protections. The candidate uses built-in
extensions rebuilt with that base. Its expected setting
`composite_type_mismatch=error` is checked without disabling protection. Both
hosted integration databases use the exact 16.16 image, and hosted PG16 client
selection now requires minor 16.16 or newer within major 16, including protected
restore tools. Path checks still reject cached wrappers and shell functions.

The disposable compatibility proof keeps the exact immutable 16.15 predecessor.
It clean-stops each same-volume transition through 16.15 → candidate 16.16 →
16.15, and tests a candidate dump/fresh-volume restore. It preserves pgcrypto1.3,
btree_gist1.7, a composite trigger, a GiST exclusion fixture and pre/post-upgrade
rows. The candidate image build and this isolated roundtrip passed; they do not
establish a production database change or a complete release pass.

The data directory remains `/var/lib/postgresql/data`. Existing live predecessor
identity, selected data volume, reviewed runtime, migration/restore comparison,
rollback and maintenance guards stay intact. No blanket REINDEX or rewrite of
existing data is added; relevant index/extension conditions were reviewed
separately before this scoped transition.

## Retained image and tablet controls

The candidate PostgreSQL base reports Alpine3.24.4. Only its official zlib and
OpenSSL-r1 runtime profile moves to that exact distro value; vendor source,
key/archive/payload hashes and other service profiles remain unchanged. Existing
r1 fallback proofs retain strict fixed-only record equivalence and independent
hashlib controls. No PostgreSQL vulnerability is added to an ignore list.
Caddy's r0 assessment and historical backend/frontend/PostgreSQL records remain
separate. A naturally clean raw scan keeps the original path.

The existing Build 77 selected-tablet fresh-sync/normal-close SQL and validation
are unchanged. A normal close proves its exact shift only, not the absence of
later local work. Compatible replay, retained legacy queues, optional updates,
signed in-place upgrade preservation, exact runtime/Redis retention, repeated
running/stopped checks and idle-shop admission remain mandatory.

The protected exact-source workflow remains authoritative before signing or
offering; its reviewed tag may start while branch CI is in progress. Full root,
backend/frontend, Android instrumentation/unit/lint, both image-store security
checks and independent reproduction must pass. Actual signed upgrade and
production/tablet admission remain separate requirements. Prior TCL evidence
does not establish newly signed Build 80 or Redmi acceptance; no zero-stutter
claim is made.

[Retained vendor proof](../infra/security/vex/README.openssl-vendor-fixed.md)
