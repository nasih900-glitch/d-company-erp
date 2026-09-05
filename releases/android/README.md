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

Do not place the signed `3.1.3` (code `14`) partner-review APK in this directory.
It is historical manually sent partner-baseline evidence and must not be hosted,
registered, or advertised through the server. It is not the current upgrade
predecessor; signed Code `21` (`3.1.10`) is.

Code `15` (`3.1.4`) is the first identity eligible for this server-hosted
registry, but it remains a held audit build. Codes `16` and `17` are immutable
upgrade-proof predecessors. Tags `v3.1.7` (code `18`), `v3.1.8` (code `19`),
and `v3.1.9` (code `20`) failed before signing and must not be reused. Code
`21` (`3.1.10`) is immutable signed predecessor history. Code `22` (`3.1.11`)
was superseded before signing and must not be approved or activated. Code `23`
(`3.1.12`) was also superseded without an authorised signed artifact. The
current server-delivery candidate is the **unsigned** `3.1.13` (code `24`)
source at migration `0071`; it is not yet a signed, tagged release artifact.
Before any staging or activation, obtain the exact artifact from the protected
green `v3.1.13` workflow and verify the
immutable HTTPS bytes, SHA-256, byte size, package, version, expected signer and
an in-place upgrade from the signed code-`21` predecessor. Android
still requires the employee to approve installation. Until those gates and
production deployment pass, Code 24 is not approved, advertised, active or
partner-installable.

Do not direct a tablet to the obsolete `3.1.0` APK as a current bootstrap. Use
the verified, same-signer code-`21` direct predecessor. Code 24 may be installed
manually in place only after the protected workflow has produced and verified
its signed artifact; never use an unsigned local candidate. Never uninstall
while offline work is pending. After a verified direct build is installed,
future server offers can be downloaded, verified, and handed to Android's
installer by the app itself.
