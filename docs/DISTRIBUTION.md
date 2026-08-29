# Web & Android Distribution — D Company ERP

D Company currently supports the hosted web ERP and the native Android app.
iOS and desktop installers are outside the release scope.

## Release paths

The web ERP at `https://dcompany.duckdns.org` is deployed through the existing
VPS/Docker Compose procedure in `docs/DEPLOY_LIVE.md`. An Android GitHub Release
does not deploy the web application.

```
git tag v3.1.0
        │
        ▼
GitHub Actions (release.yml)
        │
        ▼
native Android .apk + .aab (tested and signed)
        │
        ▼
GitHub Release attaches the signed Android binaries
        │
        ▼
download/index.html checks the official latest GitHub Release
```

The Tauri desktop and iOS projects are not built or published by the supported
release workflow.

## Android signing and Play Store setup

Generate the release keystore once and preserve it securely. Losing the key
prevents updates to the same Play Store application.

```bash
keytool -genkey -v -keystore release.keystore -alias dcompany \
        -keyalg RSA -keysize 2048 -validity 10000
```

Set these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` — normally `dcompany`
- `ANDROID_KEY_PASSWORD`

Also set the repository Actions variable
`ANDROID_EXPECTED_SIGNER_SHA256` to the 64-hex SHA-256 certificate
fingerprint from a previously trusted, installed direct APK (or from the
preserved release certificate). The fingerprint is public and is deliberately
stored independently from the keystore secrets. Do not populate it from an
artifact produced by the same workflow run: the check exists to reject a wrong
or substituted keystore and a first-install APK signed by an unexpected key.

The release workflow runs both third-party emulator automation and the Gradle
build without signing secrets. The build emits an exact checksummed unsigned
handoff, then a separate fresh runner verifies that handoff before it receives
the keystore. No Gradle process or third-party action runs in that signing job.
It uses the pinned Android 35.0.0 signing tools, verifies every APK/AAB against
the expected fingerprint, and removes the keystore before inspecting or
uploading signed artifacts. GitHub Releases are created with the runner's `gh`
CLI so a third-party publishing action cannot rewrite verified artifacts.

The workflow fails closed before producing signed artifacts when any signing
secret is absent.
Create the Play Console app with package name `cloud.dcompany.erp`, matching
`android-native/app/build.gradle.kts`. Play Store requires a Google Play Console
account; direct APK sideloading does not.

Building both artifacts does not mean they are interchangeable on an installed
tablet. The server currently advertises one Android update URL, and Google Play
App Signing may sign the delivered app with a certificate different from the
direct APK. Use one delivery channel for the active fleet, verify an in-place
upgrade from an app installed through that same channel, and do not raise the
minimum supported version until that proof passes. See
[`SERVER_DRIVEN_ANDROID_UPDATES.md`](SERVER_DRIVEN_ANDROID_UPDATES.md).

## Versioning each release

Choose one version and apply it consistently:

```bash
NEW_VERSION=3.1.0

# Update web/backend versions where applicable.
sed -i '' "s/\"version\": \".*\"/\"version\": \"$NEW_VERSION\"/" frontend/package.json
sed -i '' "s/^__version__ = .*/__version__ = \"$NEW_VERSION\"/" backend/app/__init__.py

# In android-native/app/build.gradle.kts:
# - set versionName to $NEW_VERSION
# - increment versionCode to an integer greater than the last published build

git add -A
git commit -m "chore: release v$NEW_VERSION"
git tag "v$NEW_VERSION"
git push --follow-tags
```

The workflow rejects a release unless all of these are true:

- it runs from a Git tag using the `v<version>` format;
- the tag without `v` exactly matches Android `versionName`;
- `versionCode` is a direct positive integer; and
- generated APK metadata matches the source version.

Play Console additionally requires every uploaded `versionCode` to be greater
than the last published one; the repository cannot verify Play's remote history,
so increment it for every release. A manual workflow dispatch must target an
existing tag. Dispatches from branches are rejected.

## Version-code-8 floor and the 3.1.0 candidate

Version `3.0.7` with version code `8` introduced authoritative terminal
purposes (`cafe_pos`, `gaming`, and `hybrid`) and the explicit Gaming-to-POS
handoff. Older Android clients do not understand that contract and can select
the wrong local shift or attempt an invalid local handoff, so code `8` remains
the minimum-supported compatibility floor.

The current unreleased candidate is `3.1.0` with version code `11`. It retains
the internal tenant/branch/terminal safety model while presenting the current
one-shop installation as one automatic workspace, and it adds the Gaming Centre
profile, durable offline session add-ons, combined POS billing, contextual
Support, and verified direct updates. The complete release gates and signed
upgrades from code `8` and the earlier schema-37 code-`9` candidate must pass
for code `11`. It has not passed physical Redmi Pad 2 acceptance,
been uploaded to Play, or been rolled out to production.

Code `11` is also the first build with the verified in-app direct updater.
Install it once through Android's normal installer; only later builds can use
that new in-app path. The production template intentionally continues to
advertise code `8` until the code-`11` APK and all manifest fields are published.

Treat the app and backend as one coordinated release:

1. Produce, sign, and verify the version-code-11 artifact before changing the
   server or advertising it to installed clients.
2. While the old backend is still active, bring every installed older app
   online and confirm its offline queue is empty. Do not uninstall an app with
   pending work.
3. Make the version-11 APK/update channel available to staff.
4. Back up the database, complete the deployment preflight, and run
   `alembic upgrade head`; for this candidate, verify the database reaches
   revision `0055` before starting the backend. Deploy with
   `ANDROID_MIN_SUPPORTED_VERSION_CODE=8`,
   `ANDROID_LATEST_VERSION_CODE=11`, a verified HTTPS update URL, and
   `REQUIRE_NATIVE_VERSION_HEADERS=true` in the same maintenance window.
5. Verify version 7 and older receive HTTP 426 before a write handler and
   version 8 remains operational with an optional update while version 11 is
   current. Then run shift open/close, Gaming start/add item/stop/Send-to-POS,
   cash and UPI settlement, offline retry, finance reconciliation, and Support
   submission from version 11.

Do not lower the minimum to keep an older APK operating against this backend.
If version 11 is not ready to distribute, do not advertise it as latest or
deploy the matching production compatibility configuration. Do not raise the
minimum to `11` until every active tablet is upgraded and accepted.

## Android artifacts

The Android job runs release lint, JVM tests, emulator instrumentation tests,
and signature verification. It emits:

- a signed `.apk` for controlled direct installation;
- a signed `.aab` for Play Console;
- `SHA256SUMS` for artifact integrity; and
- `release-manifest.json` with source revision, version, API base URL, and
  signing-certificate fingerprint.

Do not distribute an artifact for live café operation until the release
workflow is green and the device acceptance checklist has passed on the target
café tablet. Emulator proof supports candidate review but is not physical-device
acceptance.

## Download page

`download/index.html` checks the official repository's latest public GitHub
Release at runtime. It exposes an APK only when an asset matches the signed
workflow filename. If the check fails, the page links to the official Releases
page instead of guessing an installer URL.

When hosting the page with a Content Security Policy, allow
`https://api.github.com` in `connect-src`. Keep the bundled logo and favicon next
to `index.html`; do not enter release versions or download URLs by hand.

## If a release has problems

1. Mark the affected GitHub Release as a draft so it is no longer advertised.
2. Fix and verify the defect.
3. Increment `versionCode`, choose a new version/tag, and publish a replacement.
4. Notify affected staff to update.

There is no automatic rollback inside the installed Android app. Preserve the
previous signed APK until the replacement passes acceptance testing.

## Privacy and store requirements

The web ERP and Android app should link to the published privacy policy and
terms. Play Store also requires a data-safety form describing collected staff
and customer data.
