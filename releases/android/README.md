# Android release channel

This directory is mounted read-only into Caddy and exposed at
`/downloads/android/<filename>`. Signed APK files are deliberately ignored by
Git. Never copy a keystore, password, signing certificate private key, or an
unsigned/debug build here.

Use a versioned filename such as `d-company-erp-v<version>-direct.apk`; do not
overwrite a published filename. Before enabling the release in the backend
configuration:

1. build and sign the APK in the trusted release workflow;
2. verify the package name, version code, and signing-certificate lineage
   against the independently configured `ANDROID_EXPECTED_SIGNER_SHA256`;
3. calculate and record the APK SHA-256 and byte size;
4. copy the verified APK here using a temporary filename, then atomically rename
   it to the versioned filename;
5. confirm the public HTTPS URL returns exactly the recorded bytes; and
6. only then update the server compatibility metadata.

Removing a bad APK does not downgrade installed tablets. Publish a corrected
APK with a strictly higher Android version code.

## Current rollout boundary

Do not place the signed `3.1.2` (code `13`) partner-review APK in this directory.
It is the manually sent partner baseline after the production `0057` smoke and
must not be hosted or advertised through the server in this release. Keep the
production latest-version policy at code `8` with no direct-update URL or
integrity metadata.

The first artifact eligible for this server-hosted channel is a distinct,
newly signed `3.1.3` (code `14`) APK. Before advertising it, verify the immutable
HTTPS bytes, SHA-256, byte size, package, version, expected signer and an
in-place upgrade from the manually installed code-`13` baseline. Android still
requires the employee to approve installation.

The `3.1.0` APK is the one-time bootstrap for the verified direct updater.
Earlier installed builds still require Android's normal installer flow; future
versions may then be downloaded, verified, and handed to that installer by the
app itself.
