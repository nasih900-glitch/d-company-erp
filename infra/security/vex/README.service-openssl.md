# Three separate service execution-path assessments

These contracts address the same four upstream OpenSSL advisories as the
backend assessment, with separate image, package, source and configuration
closures. They are not inherited from the backend and cannot be applied to
Redis or an arbitrary service. No severity threshold or package version is
changed. Original unfiltered evidence is retained.

- `caddy-static-go-openssl-reviewed.json`: the exact static Go/CGO-disabled
  Caddy executable uses Go TLS and quic-go. Installed OpenSSL libraries are
  outside that reviewed executable's path. Its executable, complete native
  inventory, startup, Go source/module locks, fixed Caddy/Compose sources and
  OpenSSL configuration/engine/provider file sets are bound.
- `frontend-http-only-openssl-reviewed.json`: the pinned Nginx binary includes
  SSL and HTTP3 support. The reviewed deployment serves HTTP on port 80 with
  HTTP upstreams. The contract binds every Nginx configuration/entrypoint
  file, absent template directory, image startup, native inventory and fixed
  deployment sources. It makes no assertion that HTTP3 was compiled out.
- `postgres16-pgcrypto-openssl-reviewed.json`: PostgreSQL 16.15, libpq and
  OpenLDAP use ordinary TLS. pgcrypto's reviewed cipher whitelist excludes
  AES-GCM-SIV; its PGP RSA/ElGamal interface is not CMS encryption. The
  complete native inventory includes installed extensions. Startup,
  OpenSSL configuration/engine/provider files and the empty init directory
  are bound independently.

Each contract covers precisely the four advisory IDs against `libcrypto3`
and `libssl3` 3.5.9-r0 in its actual Alpine distribution. A closed dispatcher
selects only these three names. The shared code handles the already pinned
Syft-to-Grype serialization, immutable image identity and exact raw-to-filtered
comparison. It does not provide a configurable CVE-ignore framework. Each
rescan must retain every nonexcluded original finding and contain exactly
eight `not_affected` records with the reviewed justification. Missing,
duplicate, changed or newly ignored findings are rejected.

A fresh offline readonly collector hashes the full Syft ELF path inventory
and recursively inventories each approved startup/configuration root. Resolved
ELF paths, symlink targets and directory presence are retained, including empty
or absent template/init directories. New engine/provider/configuration files,
new init scripts, changed entrypoints, crypto-environment overrides and native
library changes invalidate the contract. Diagnostic SPDX conversions are not
accepted as the raw scanner evidence: admission requires the direct pinned
Grype image/archive output with the unchanged High fail gate.

PostgreSQL's data volume is mutable and is not proven by image identity. The
existing production upgrade runtime checker also queries live `pg_settings`,
`pg_extension`, native C-function libraries and role/database library-setting
overrides in a readonly transaction. It requires the reviewed settings and
catalogue before maintenance/shutdown and again when verifying the admitted
migration head. Missing or unavailable evidence fails the gate. No production
settings or catalogue are changed by this assessment.

The source API rationale follows the [upstream advisory](https://openssl-library.org/news/secadv/20261110.txt)
and reviewed PostgreSQL [pgcrypto cipher implementation](https://github.com/postgres/postgres/blob/REL_16_15/contrib/pgcrypto/openssl.c).
These are execution-path assessments, not claims that vulnerable library code
has been patched or removed. New features/configuration and upstream package
updates require reassessment with real scanner/runtime evidence.
