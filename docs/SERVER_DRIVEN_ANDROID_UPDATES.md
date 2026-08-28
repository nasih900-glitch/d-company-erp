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

## Safe rollout

Assume the currently installed production build has version code `8`; a test
tablet may instead hold the earlier schema-37 code-`9` candidate. The new signed
release has version code `10`.

1. Produce and verify the signed version-code-10 APK before changing the server.
2. Publish the APK on the controlled HTTPS release channel.
3. Confirm its signing-certificate fingerprint matches the installed app.
4. Configure the backend initially as:

   ```dotenv
   ANDROID_MIN_SUPPORTED_VERSION_CODE=8
   ANDROID_LATEST_VERSION_CODE=10
   ANDROID_UPDATE_URL=https://controlled.example/d-company-erp-v3.0.9.apk
   ```

   Version 8 remains operational and sees an optional update. Version 10 is
   current.

5. Let every tablet sync its offline queue, install the update, sign in and
   complete the smoke test.
6. Only when every active tablet is on version 10 may the minimum be raised:

   ```dotenv
   ANDROID_MIN_SUPPORTED_VERSION_CODE=10
   ANDROID_LATEST_VERSION_CODE=10
   ```

## What the employee experiences

For an optional update, the employee can keep working and install later. For a
required update, financial writes are blocked, but the local database and
signed-in account are preserved. The employee taps **Update securely**, obtains
the signed APK from the configured HTTPS channel and approves installation in
Android.

Normal Android installations cannot be updated silently. Silent installation
requires either Google Play managed updates or a tablet enrolled as an Android
Enterprise device owner. For the current partner pilot, Play Internal Testing
is the lowest-maintenance choice; direct signed APK delivery remains supported.

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
