# Archived Capacitor shells — do not release

The `frontend/android` and `frontend/ios` directories are retained only as
historical prototypes. They are **not supported D Company ERP clients** and are
not part of CI, signing, partner distribution, production deployment, or the
server-driven Android update channel.

In particular, do not run `frontend/android/gradlew assembleRelease` and do not
send its APK to staff or partners. That archived wrapper has a different
application ID (`cloud.dcompany.erp.web`), an obsolete version, and no release
acceptance evidence. Installing it would create a second Android app instead
of upgrading D Company ERP.

The only supported Android source is:

```text
android-native/
```

Use [`../docs/DISTRIBUTION.md`](../docs/DISTRIBUTION.md) for its signed APK/AAB
workflow. The supported package ID is `cloud.dcompany.erp`.

The web ERP remains the primary hosted client. No iOS build is currently
supported or distributable.

## Retained iOS finance compatibility

Although it is not a release target, the native iOS source is kept compatible
with the current authenticated expense API so it cannot silently use an unsafe
legacy money path if distribution is reconsidered:

- expense responses include shift identity, void evidence, and receipt
  count/status;
- cash is recorded as an explicit paid-out from a selected open shift in the
  same branch, using one canonical `expense:<uuid>` idempotency identity across
  retries; the action is not restricted to the employee who opened the shift;
- voiding uses `POST /finance/expenses/{id}/void` and requires a 3–500 character
  reason; the client does not call the legacy delete route;
- an optional bill can come from the document camera, photo library, or a
  supported JPEG, PNG, WebP, or PDF file up to 10 MB; and
- the expense is created once before its receipt is uploaded. Receipt retries
  retain both the saved expense ID and their own idempotency key, so an upload
  failure cannot create a second expense. Receipt metadata/status and
  authenticated temporary previews are available from the expense list.

This source-level compatibility is not build, signing, device, distribution,
or production acceptance evidence.
