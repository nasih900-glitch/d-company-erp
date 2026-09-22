# Android release channel

This directory is mounted read-only into Caddy and exposed at
`/downloads/android/<filename>`. Signed APK files are deliberately ignored by
Git. Never copy a keystore, password, signing certificate private key, or an
unsigned/debug build here.

Use a versioned filename such as `d-company-erp-v<version>-direct.apk`; do not
overwrite a published filename. Use `ops/stage_android_release.py` rather than
copying a candidate manually. It:

1. accepts the signed APK and exact manifest from one trusted release workflow;
2. verifies the package name, version code, and signing-certificate lineage
   against the independently configured `ANDROID_EXPECTED_SIGNER_SHA256`;
3. verifies the APK SHA-256 and byte size against that manifest;
4. copies the verified APK here using a random temporary filename, then uses a
   no-replace atomic rename to the versioned filename;
5. confirms the public HTTPS URL returns exactly the recorded bytes; and
6. only then registers an immutable **staged** release record.

Staging does not advertise the APK. A protected owner activates or withdraws an
already staged record in the ERP; activation re-verifies the public bytes. Do
not create a second environment-variable activation path.

Removing a bad APK does not downgrade installed tablets. Publish a corrected
APK with a strictly higher Android version code.

## Current rollout boundary

Code30.2 `v3.1.29` / build `37` is the immutable direct-channel
predecessor. Preserve its exact APK, manifest, hashes, source and signer.

Code30.3 is the in-progress `v3.1.30` / build `38` source candidate at Room
schema `52` and Alembic head `0081`. There is no distributable signed Code30.3
APK yet. Do not place local/debug bytes in this directory. Before staging, the
exact protected-workflow APK and manifest must pass independent package,
version, hash, size, source and signer checks plus a same-signer in-place update
from exact build `37` without uninstalling or clearing data. The matching
backend/Web migration, split-payment and station-transfer acceptance,
stale-session recovery acceptance, inactive staging, owner activation and
physical Redmi Pad 2 acceptance are separate gates.

Staging build `38` does not offer it. Only the bound owner release-controller
may activate the exact staged record after the earlier gates pass, and Android
still asks each user to approve installation.

### Earlier immutable release history

Do not place the signed `3.1.3` (code `14`) partner-review APK in this directory.
It is historical manually sent partner-baseline evidence and must not be hosted,
registered, or advertised through the server. Signed Code `21` (`3.1.10`) was
the later same-channel predecessor for the release attempts recorded below; it
is no longer the current Code30.3 predecessor.

Code `15` (`3.1.4`) is the first identity eligible for this server-hosted
registry, but it remains a held audit build. Codes `16` and `17` are immutable
upgrade-proof predecessors. Tags `v3.1.7` (code `18`), `v3.1.8` (code `19`),
and `v3.1.9` (code `20`) failed before signing and must not be reused. Code
`21` (`3.1.10`) is immutable signed predecessor history. Code `22` (`3.1.11`)
was superseded before signing and must not be approved or activated. Code `23`
(`3.1.12`) was also superseded without an authorised signed artifact. Code
`24` (`3.1.13`) failed before signing and remains immutable history. Code `25`
(`3.1.14`) was rejected by its physical-tablet audit; its tag and any artifact
remain immutable history and must not be staged or activated. The `v3.1.15`
attempt failed before build/signing and produced no authorised or distributed
Code 26 artifact; its tag and evidence remain immutable. Code `26` (`3.1.16`)
was later signed under the documented never-issued-identity exception, but its
production installer stopped before image builds or cutover. Code `27` was
signed but its scanner gate failed. Code `28` was signed but failed the exact
production image-identity gate. The original signed Code `29` failed its
installer-lock gate, and the later Code29 attempts remained superseded history.
Their candidate ledgers preserve the exact facts; none may be relabelled as
Code30.3 evidence. Code30.2 build `37` is the current immutable predecessor.

Do not direct a tablet to the obsolete `3.1.0` APK as a current bootstrap. Use
the verified, same-signer Code30.1 `v3.1.28` / build `36` predecessor for the
Code30.3 continuity test. Build `38` may be installed only after the protected
workflow has produced and independently verified its signed artifact. Never use
an unsigned local candidate or uninstall while offline work is pending. After a
verified direct build is installed, future server offers can be downloaded,
verified, and handed to Android's installer by the app itself.
