# Android release channel

This directory is mounted read-only into Caddy and exposed at
`/downloads/android/<filename>`. Signed APK files are deliberately ignored by
Git. Never copy a keystore, password, signing certificate private key, or an
unsigned/debug build here.

Use a versioned filename such as `d-company-erp-v3.1.1.apk`; do not overwrite a
published filename. Before enabling the release in the backend configuration:

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

The `3.1.0` APK is the one-time bootstrap for the verified direct updater.
Earlier installed builds still require Android's normal installer flow; future
versions may then be downloaded, verified, and handed to that installer by the
app itself.
