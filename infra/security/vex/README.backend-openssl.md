# Backend OpenSSL execution-path assessment

This is a bounded `not_affected` assessment, not a patched-library claim or a
severity override. The original scanner reports remain retained. Grype's High
threshold, pinned scanner identities, isolation and unrelated findings remain
unchanged. The separate CPython hashlib backport retains its original `fixed`
statement and runtime/source controls.

The reviewed backend contains system OpenSSL 3.5.9, psycopg-binary's private
OpenSSL libraries and cryptography 50.0.0's bundled OpenSSL 4.0.1. The assessment
covers all three copies. The four advisory paths are reviewed separately:

| Advisory | Vulnerable path | Bound application use |
| --- | --- | --- |
| CVE-2026-95692 | AES-GCM-SIV initialization with an invalid nonce length | ERP uses AESGCM with a 12-byte nonce, with no AES-GCM-SIV or caller-selected cipher path. |
| CVE-2026-88823 | AES-GCM-SIV decryption | ERP uses AESGCM, not AES-GCM-SIV. |
| CVE-2026-95691 | OpenSSL QUIC client | CPython `_ssl` and libpq 18 use ordinary TLS methods; the ERP does not expose a QUIC client or arbitrary transport selection. |
| CVE-2026-107496 | CMS encryption with an untrusted EC recipient certificate | Device keys are canonical P-256 SPKI used for ECDSA verification; there is no CMS encryption/recipient interface. The cryptography copy's disabled explicit-curve option is additional evidence for that copy only. |

The upstream [OpenSSL advisory](https://openssl-library.org/news/secadv/20261110.txt)
defines these preconditions. The reviewed library clients are the pinned
[CPython `_ssl` implementation](https://github.com/python/cpython/blob/v3.14.10/Modules/_ssl.c)
and [PostgreSQL 18 libpq TLS implementation](https://github.com/postgres/postgres/blob/REL_18_0/src/interfaces/libpq/fe-secure-openssl.c).
Passing the ordinary-crypto probes alone does not establish this assessment:
the complete reviewed source and immutable binary/configuration closure are
also required.

`backend-openssl-reviewed.json` fixes the complete 686-file application tree,
entrypoint, dependency lock, configuration-file hashes, provider state, crypto
environment, all 181 Syft-catalogued ELF paths and dependencies, all native
library hashes, and startup/deployment source hashes. The collector hashes
actual candidate-image bytes in a bounded, offline, readonly container. Syft
supplies the complete ELF inventory and imported-library data; the collector
does not implement another ELF parser. The immutable image config must have
the reviewed entrypoint, working directory and environment. Source or recipe
changes require a new security assessment rather than automatic renewal.

Only the exact eight reviewed CVE/package matches can produce the OpenSSL VEX.
The first rescan must retain the separately assessed Python High finding. Only
then can its original strict backport validator issue the Python `fixed`
statement. The combined final VEX is generated from both validated statements.
Final verification requires exactly eight `not_affected` records and one
Python `fixed` record, with every original raw match otherwise retained byte
for byte. DB identity/checksum/provider data must agree across scans; local
cache paths and archive-versus-daemon source labels are not vulnerability
identity. New CVEs, package/library drift, missing/duplicate records, extra
ignore rules, altered proofs or unexplained filtering remain blocking.

This assessment applies only to this backend closure. It does not assess the
other four production images or future findings. Replace these library copies
with reviewed upstream fixes when available, rerun the original unfiltered
scans and remove the assessment only when the genuine findings disappear.
