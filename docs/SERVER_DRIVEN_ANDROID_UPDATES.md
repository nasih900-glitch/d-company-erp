# Server-driven Android updates

D Company ERP already separates release delivery from compatibility policy:

- the signed APK/AAB is produced by the release workflow;
- an HTTPS release location hosts the signed APK;
- the ERP server advertises the latest and minimum-supported version codes;
- Android shows an optional update banner or a required-update screen; and
- Android's package installer verifies that the new APK has the same package
  name and signing lineage before it can replace the installed app.

The ERP backend must never build, re-sign, or modify an APK. Keep the signing
key outside the VPS and use the existing release workflow.

The release workflow also requires the out-of-band repository Actions variable
`ANDROID_EXPECTED_SIGNER_SHA256`. Set it from a previously trusted installed
direct APK or the preserved release certificate, not from the candidate built
in that same run. Release APKs and the Play AAB must all match this fingerprint
before they can be packaged. Emulator automation and the Gradle build run
without signing secrets. Their unsigned artifacts are handed to a fresh signing
runner, where no Gradle process or third-party action is allowed to run after
the release keystore is decoded. The signer uses the explicitly pinned Android
35.0.0 `zipalign` and `apksigner` binaries instead of whichever SDK happens to
be newest on the hosted runner.

## One-time bootstrap boundary

Version `3.1.0` (code `11`) first introduced the verified in-app direct updater.
An already-installed `3.0.9` or older app cannot acquire new installer code
from the server. The already signed `3.1.1` (code `12`) partner APK is now the
preserved manual-install, update-capable baseline. Never overwrite either
signed identity with changed bytes, and do not advertise code `12` as a server
update. Version `3.1.2` (code `13`) is the first later candidate intended to be
advertised to that baseline through the server. A locally signed partner-review
APK now exists, but it has not been published, deployed, or advertised.

The repository and Compose defaults deliberately keep
`ANDROID_LATEST_VERSION_CODE=8`. Building a newer APK is not authority to
advertise it. The operator raises `ANDROID_LATEST_VERSION_CODE` only after the
exact APK is published and the complete manifest metadata has been checked.

## Release manifest to backend configuration

The workflow-generated `release-manifest.json` is the verified handoff record,
not a backend configuration file to import wholesale. Copy only these fields
into the production compatibility settings after publishing the exact APK:

| Release manifest | Backend environment variable |
| --- | --- |
| `version_code` | `ANDROID_LATEST_VERSION_CODE` |
| `version_name` | `ANDROID_LATEST_VERSION_NAME` |
| `apk_download_url` | `ANDROID_UPDATE_URL` |
| `apk_sha256` | `ANDROID_UPDATE_APK_SHA256` |
| `apk_size_bytes` | `ANDROID_UPDATE_APK_SIZE_BYTES` |
| `signing_certificate_sha256` | `ANDROID_UPDATE_SIGNING_CERT_SHA256` |

`ANDROID_MIN_SUPPORTED_VERSION_CODE` is a rollout decision and is deliberately
not generated from the artifact. `ANDROID_UPDATE_RELEASE_NOTES` is maintained
separately. Keep the minimum at the oldest compatible deployed client until
every active tablet has drained its offline queue and upgraded successfully.

The manifest generator rejects APKs above 512 MiB, matching the Android
downloader's hard limit. This prevents the server from advertising metadata for
an artifact the client is required to reject.

For a direct `.apk` URL, the backend fails startup if version name, SHA-256,
byte size, or signer fingerprint is absent. A partial direct-update contract is
an operational lockout risk, so it is not downgraded to an unverified link.
Play/managed-store links may omit APK integrity fields because the app does not
download those bytes through the direct updater.

Production and staging also refuse to advertise or require a build newer than
the code-`8` compatibility floor without an HTTPS update URL. This prevents an
operator typo from persisting a required-update screen that gives staff no
installation path.

## Safe rollout

Assume the partner tablet has the exact signed `3.1.1` baseline at code `12`,
installed manually through Android's package installer. Older supported clients
may remain at code `8`, code `9`, or the signed `3.1.0` code `11`; none is proof
that the code-`12` partner baseline was installed successfully. The candidate
server-delivered release has version code `13`.

1. Confirm the installed code-`12` baseline has the expected signer, no pending
   offline work, and a working update check.
2. Produce and verify the signed version-code-13 APK without changing the
   server's update metadata.
3. Publish the immutable APK on the controlled HTTPS release channel and
   confirm its signing-certificate fingerprint matches code `12`.
4. Only after the artifact and complete manifest are verified, configure the
   backend initially as:

   ```dotenv
   ANDROID_MIN_SUPPORTED_VERSION_CODE=8
   ANDROID_LATEST_VERSION_CODE=13
   ANDROID_LATEST_VERSION_NAME=3.1.2
   ANDROID_UPDATE_URL=https://controlled.example/d-company-erp-v3.1.2.apk
   ANDROID_UPDATE_APK_SHA256=<64-hex APK digest from release-manifest.json>
   ANDROID_UPDATE_APK_SIZE_BYTES=<exact byte size from release-manifest.json>
   ANDROID_UPDATE_SIGNING_CERT_SHA256=<64-hex signer digest from release-manifest.json>
   ANDROID_UPDATE_RELEASE_NOTES=Gaming Centre reliability update
   ```

   Code `12` sees an optional update while the minimum-compatible floor remains
   code `8`. Code `13` is current only after this configuration is deployed.

5. Let every tablet sync its offline queue, install the update, sign in and
   complete the smoke test.
6. Only when every active tablet is on version 13 may the minimum be raised:

   ```dotenv
   ANDROID_MIN_SUPPORTED_VERSION_CODE=13
   ANDROID_LATEST_VERSION_CODE=13
   ```

## What the employee experiences

For an optional update, the employee can keep working and install later. For a
required update, financial writes are blocked, but the local database and
signed-in account are preserved. In the controlled direct-distribution build,
the employee taps **Download verified update**. The app verifies the exact
advertised byte size, SHA-256, package, version, expected signer, and signing
lineage before enabling **Install update**. Android then requires the employee
to approve installation.

If any integrity field is missing or invalid, the app refuses the direct path
and offers only the legacy HTTPS link. The ordinary Play build never requests
Android's package-install permission and therefore always uses its store/link
channel.

Once this installed build receives an authoritative required-update decision,
that block is stored locally and survives process death, an offline restart,
and later supported/optional responses for the same version code. A successful
in-place update changes the installed version code and clears the stale block
at the next startup. Do not tell staff to clear app data to escape the screen;
that would also endanger locally queued work.

Normal Android installations cannot be updated silently. Silent installation
requires either Google Play managed updates or a tablet enrolled as an Android
Enterprise device owner. For the current partner pilot, Play Internal Testing
is the lowest-maintenance choice; direct signed APK delivery remains supported.

GitHub release assets and Caddy URLs are immutable. The workflow refuses to
replace an existing release tag, and the operator must never overwrite a file
already served under a versioned URL. A corrected binary requires a higher
version code, a new tag, and a new filename.

## Do not mix Play and direct delivery on one active fleet

The current compatibility contract has one Android `update_url`. It cannot
simultaneously advertise a Play listing to Play-installed clients and the
controlled APK to direct-installed clients. Choose one rollout channel for the
active tablet fleet until a channel-specific compatibility contract is added.

Google Play App Signing can also make the certificate on the APK delivered by
Play different from the certificate on the locally signed direct APK. The CI
signer fingerprint describes the direct APK/upload artifact; it does not prove
the certificate on a Play-delivered split APK. Android will safely reject a
cross-channel update whose signing lineage does not match, but raising the
server minimum first would leave that tablet blocked. Verify an in-place update
on an installation obtained through the exact chosen channel before changing
`ANDROID_MIN_SUPPORTED_VERSION_CODE`.

## Rollback

Android does not allow an older version code to replace a newer one in the
normal production flow. If a release is defective:

1. remove it from the advertised release channel;
2. fix the defect;
3. increment the version code again;
4. publish a newly signed replacement; and
5. update the server's latest/minimum policy only after the replacement exists.

Never lower server compatibility merely to make an unsafe binary operational,
and never ask staff to uninstall while the local offline queue contains work.
