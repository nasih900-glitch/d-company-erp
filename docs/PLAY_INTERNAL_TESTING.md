# Google Play internal testing — D Company Android app

Google Play internal testing is the controlled pre-release channel for the
native Android app. It can distribute a build to up to 100 selected testers,
but it is not a production-readiness verdict and it does not replace acceptance
testing on the café's real tablet.

Current Android identity:

| Field | Value |
| --- | --- |
| App name | `D Company` |
| Package name | `cloud.dcompany.erp` |
| Version name | `3.1.2` |
| Version code | `13` |
| Minimum compatible client code | `8` |
| Production API | `https://dcompany.duckdns.org/api/v1/` |

This is the identity reserved for the next unreleased candidate. The already
signed `3.1.1` code-`12` partner APK remains the immutable manual,
update-capable baseline. A future signed `3.1.2` APK/AAB must pass its complete
release gates and a signed in-place upgrade from code `12` to code `13`. No
code-13 APK/AAB has been approved or uploaded, no production rollout or server
advertisement has occurred, and physical Redmi Pad 2 acceptance remains
unverified. The candidate includes the refined Gaming command workspace,
canonical receipt history, reliable real-time refresh, and Room schema 40. Its
coordinated backend deployment must reach Alembic revision `0057`.

Google's current internal-testing instructions are at
[Play Console Help](https://support.google.com/googleplay/android-developer/answer/9845334).

## Release gate before Play Console

Do not upload an arbitrary local build. Use the signed artifact from a green
repository release workflow and verify all of the following:

- backend tests and migrations pass;
- Android JVM tests, compilation, lint, assembly, and emulator instrumentation
  pass;
- the release manifest says package `cloud.dcompany.erp`, version `3.1.2`, code
  `13`, and the production HTTPS API above;
- APK/AAB signatures and the published SHA-256 checksums verify;
- no test active session, unpaid held order, pending cancellation, or open test
  shift remains in the acceptance environment;
- the physical Redmi Pad 2 has no unsynced offline work before an install,
  uninstall, account change, or signing-key change; and
- the complete workflow in
  [`ANDROID_STAFF_GUIDE.md`](ANDROID_STAFF_GUIDE.md) passes on that physical
  tablet.

API-35 emulator installation and a signed code-12-to-code-13 in-place upgrade
are required, but they are not physical Redmi Pad proof.
Uploading to an internal track also does not deploy the backend or web ERP to
production. Keep client code `8` as the compatibility floor and advertise code
`13` only after its signed artifact is actually available at the configured
HTTPS update URL. The code-`12` partner baseline remains a manual install and
must not be advertised as a server update. GST validation is outside the
current Android acceptance scope.

## 1. Create or use the correct developer account

Use a Google account controlled by the long-term owner of the D Company app,
enable two-step verification, and keep recovery access documented privately.
Choose the account type that matches the legal owner:

- **Organization** is appropriate when a registered business owns the account;
  Google requires organization verification, including a D-U-N-S number.
- **Personal** is appropriate only when an individual legally owns the account.

Google currently charges a one-time USD 25 registration fee for a full
distribution developer account and requires identity verification. See
[Google's account setup guide](https://support.google.com/android-developer-console/answer/16604405),
and follow the live Console prompts rather than treating this document as policy
authority.

## 2. Create the Play app

In Play Console, choose **All apps → Create app** and use:

| Field | Value |
| --- | --- |
| App name | `D Company` |
| Default language | English (India) or English (UK) |
| App or game | App |
| Free or paid | Free |

Confirm every declaration truthfully. Once the first artifact is uploaded, the
package name is fixed for that Play app, so confirm `cloud.dcompany.erp` before
continuing.

## 3. Preserve the signing lineage

Android identifies an installed app by its package name and signing lineage.
This package was also used by earlier D Company Android builds. A build signed
with an unrelated key cannot update an existing installation and may force an
uninstall, which deletes that tablet's Room database and any unsynced work.

The production-safe choice is to enrol the existing D Company app-signing key
when configuring Play App Signing if any earlier APK has been installed or
distributed. Play Console may ask for an encrypted key export using its PEPK
tool. Run the console-generated command locally against the real keystore; do
not commit or paste keystore passwords, encryption material, or private keys.

Local files are expected at:

```text
keystore : android-native/dcompany-release.keystore
alias    : dcompany
settings : android-native/keystore.properties
```

Only let Play generate a new app-signing key when no deployed installation must
be updated and the owners deliberately accept a new signing lineage. Google's
key choices and import procedure are documented in
[Use Play App Signing](https://support.google.com/googleplay/android-developer/answer/9842756).

Before choosing either path, compare the certificate fingerprint in
`release-manifest.json` with the installed build and the key registered in Play
Console.

## 4. Complete the required app information

The exact setup items shown depend on account and track. Complete every item the
Console requests before wider release. Use these known facts:

| Section | D Company answer |
| --- | --- |
| Privacy policy | `https://dcompany.duckdns.org/privacy.html` |
| App access | Sign-in is required; put a dedicated review account only in Play Console's protected review fields |
| Ads | No ads |
| Category | Business |
| Content rating | Complete the live business/utility questionnaire truthfully |
| Target audience | Staff / adults; not designed for children |
| Data handling | Staff identity, role/activity, customer/order/payment and operational records are processed on D Company's server; no advertising or sale of data |
| Government app | No |

Never commit review credentials or real customer data. Internal-test artifacts
may receive lighter listing treatment, but privacy and access answers must still
be accurate before any broader rollout.

## 5. Upload version 3.1.2 (13)

1. Open **Test and release → Testing → Internal testing**.
2. Choose **Create new release**.
3. Upload the signed AAB from the green repository release, normally named
   `d-company-erp-v3.1.2-play.aab`. Do not upload `app-debug.apk` or substitute the
   locally signed APK for Play's required bundle.
4. Confirm Play reads package `cloud.dcompany.erp`, version `3.1.2`, and version
   code `13` from the bundle.
5. Use release name `3.1.2 (13)`.
6. Add concise notes such as: `Gaming Centre command workspace, canonical
   receipt history, reliable real-time refresh, and offline recovery.`
7. Review all warnings, then start the rollout to **Internal testing only**.

If Play reports that version code `13` was already used, increment `versionCode`
and rebuild through the release workflow. Never alter or rename an existing
bundle to work around a version error.

## 6. Add staff testers

1. Open the internal track's **Testers** tab.
2. Create an email list such as `D Company staff`.
3. Add the Google Account used on each authorised tablet.
4. Save the list and copy the opt-in link.
5. Share the link only with authorised staff.

Testers need a Google or Google Workspace account. A first release or update can
take time to become available; do not schedule the café shift around an assumed
instant rollout.

## 7. Install and accept on the Redmi Pad 2

Before installation, open the existing app and make sure it shows no pending,
rejected, or waiting-to-sync actions. Do not uninstall an existing build merely
to bypass a signing error.

On the tablet:

1. Sign in to Google Play with the authorised tester account.
2. Open the opt-in link and accept the invitation.
3. Install or update **D Company** from Google Play.
4. Confirm Android reports version `3.1.2` (code `13`).
5. Grant notification permission and, when prompted, allow the exact-alarm
   access needed for operational session/held-order reminders.
6. Run the complete start-to-close workflow in
   [`ANDROID_STAFF_GUIDE.md`](ANDROID_STAFF_GUIDE.md), including airplane-mode
   recovery and an app restart.
7. Record the device model, Android version, app version/code, timestamp, result,
   and any defect. Do not call the build approved until this run passes.

Play updates are delivered through the Store, but installation timing depends
on rollout availability, device state, and the tablet's auto-update settings.
Do not promise silent or immediate updates; confirm the installed version before
staff begin a shift.

## Shipping a later update

1. Fix and verify the change.
2. Increment `versionCode`; Play requires a value greater than every previously
   uploaded release for this app.
3. Choose a new `versionName` and matching Git tag.
4. Run the signed repository release workflow.
5. Verify the manifest, checksums, signing certificate, and physical-tablet
   acceptance evidence.
6. Upload the new AAB to the internal track and roll it out.
7. Confirm the target tablet actually installed the new version before the next
   live shift.

See [`DISTRIBUTION.md`](DISTRIBUTION.md) for version/tag rules, artifact names,
workflow gates, direct-APK handling, and rollback procedure.

## Protect the key and recover safely

Keep encrypted backups of `dcompany-release.keystore` and the credentials needed
to use it. Restrict access and test that the backup can be restored. The upload
key can be reset through Play App Signing, but an unmanaged app-signing key that
is lost cannot simply be recreated.

If a release is faulty, stop or supersede the internal rollout, preserve the
previous signed APK, fix the defect, increment the version code, and publish a
new tested build. The installed app has no automatic data rollback. Never solve
a release problem by clearing app data while offline work is unresolved.
