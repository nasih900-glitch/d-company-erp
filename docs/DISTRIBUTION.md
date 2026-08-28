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

The workflow fails closed before building when any signing secret is absent.
Create the Play Console app with package name `cloud.dcompany.erp`, matching
`android-native/app/build.gradle.kts`. Play Store requires a Google Play Console
account; direct APK sideloading does not.

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

## Version 8 compatibility floor and candidate

Version `3.0.7` with version code 8 introduces authoritative terminal purposes
(`cafe_pos`, `gaming`, and `hybrid`) and the explicit Gaming Area to Cafe POS
handoff. Older Android clients do not understand that contract and can select
the wrong local shift or attempt an invalid local handoff. They must therefore
receive a clear update-required response before an operational write reaches a
handler; version code 8 is both the minimum supported and latest code for this
coordinated release.

Treat the app and backend as one coordinated release:

1. Produce, sign, and verify the version-code-8 artifact before changing the
   server or advertising it to installed clients.
2. While the old backend is still active, bring every installed older app
   online and confirm its offline queue is empty. Do not uninstall an app with
   pending work.
3. Make the version-8 APK/update channel available to staff.
4. Back up the database, complete the deployment preflight, and run
   `alembic upgrade head`; for this candidate, verify the database reaches
   revision `0052` before starting the backend. Deploy with
   `ANDROID_MIN_SUPPORTED_VERSION_CODE=8`,
   `ANDROID_LATEST_VERSION_CODE=8`, and
   `REQUIRE_NATIVE_VERSION_HEADERS=true` in the same maintenance window.
5. Verify version 7 and older receive HTTP 426 before a write handler and
   version 8 is current. Then run Gaming start, timer extension, paid package
   extension, stop, and the explicit Cafe POS handoff from version 8.

Do not lower the minimum to keep an older APK operating against this backend.
If version 8 is not ready to distribute, do not advertise it as latest or deploy
the matching production compatibility configuration.

## Android artifacts

The Android job runs release lint, JVM tests, emulator instrumentation tests,
and signature verification. It emits:

- a signed `.apk` for controlled direct installation;
- a signed `.aab` for Play Console;
- `SHA256SUMS` for artifact integrity; and
- `release-manifest.json` with source revision, version, API base URL, and
  signing-certificate fingerprint.

Do not distribute an artifact until the release workflow is green and the
device acceptance checklist has passed on the target café tablet.

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
