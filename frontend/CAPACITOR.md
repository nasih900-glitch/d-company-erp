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
