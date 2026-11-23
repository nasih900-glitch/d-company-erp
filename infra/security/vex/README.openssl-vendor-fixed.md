# Exact Alpine3.24 OpenSSL vendor-fixed evidence

This is a package-content `fixed` assessment for the authenticated x86_64
Alpine3.24 `libcrypto3` and `libssl3` 3.5.9-r1 packages. It is separate from
Caddy's unchanged Alpine3.23 r0 `not_affected` execution-path assessment and
from the historical backend/frontend/PostgreSQL r0 records.

Alpine's [v3.24 security database](https://secdb.alpinelinux.org/v3.24/main.json)
records vendor fixes in [source commit
0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2](https://gitlab.alpinelinux.org/alpine/aports/-/commit/0f4c7e8d0f5f7cebbdc2156eca4391080b732bf2).
The reviewed stale NVD CPE matches are CVE-2026-88823, CVE-2026-95691,
CVE-2026-95692 and CVE-2026-107496, once per exact package. The vendor also
records CVE-2026-88822; it is not added to this assessment because it is absent
from the reviewed eight-match scanner evidence.

The collector runs inside the exact candidate image as a non-root process,
without network access, capabilities or writable root filesystem. Two exact
archives and the verifier are mounted individually readonly. It checks the
trusted signing key's full SHA256, offline APK signature, authenticated package
name/version/origin/source commit/architecture, signed payload manifest, and
both extracted and installed library SHA256 values. The exact archives remain
in retained security evidence. Changed CDN bytes refuse admission.

The producer binds that runtime result to the exact image/config identity,
pinned Syft and Grype identities, exact package metadata, library digests and
eight reviewed matcher signatures. It creates only `fixed` statements for those
records. All original records are retained; filtered scans must preserve the
same database identity and exact active/ignored multisets. Missing, duplicate,
unknown or changed findings, extra ignores and `not_affected` substitution fail.
A genuine raw zero-High scan continues through the original admission path.

The backend's independent CPython hashlib proof is still mandatory when its
reviewed High is present. The eight vendor fixes cannot excuse that finding.
Its combined statement is admitted only after the exact source/module/runtime
proof and one-CVE Python statement validate; the final scan must explain exactly
eight vendor records plus that one Python record. Other High findings block.

Neither this source nor a successful isolated proof is an update offer. Full
exact-source release, independent reproduction, signing, preserved in-place
upgrade and production/tablet admission gates remain required.
