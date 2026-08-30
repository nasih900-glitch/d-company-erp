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
It is the manually sent partner baseline after the coordinated production smoke
and must not be hosted, registered, or advertised through the server.

Code `15` (`3.1.4`) is the first identity eligible for this server-hosted
registry, but it is a held audit build. Preserve any signed APK and manifest
exactly; do not rebuild, overwrite, or activate them as a shortcut. The current
server-delivery candidate is the distinct, newly signed `3.1.5` (code `16`)
APK. Before activation, verify the immutable HTTPS bytes, SHA-256, byte size,
package, version, expected signer and an in-place upgrade from the manually
installed code-`14` baseline. Android still requires the employee to approve
installation.

Do not direct a tablet to the obsolete `3.1.0` APK as a current bootstrap. Use
the verified, same-signer code-`14` direct baseline, or install code `16`
manually in place when the installed build cannot prove update capability.
Never uninstall while offline work is pending. After a verified direct build is
installed, future server offers can be downloaded, verified, and handed to
Android's installer by the app itself.
