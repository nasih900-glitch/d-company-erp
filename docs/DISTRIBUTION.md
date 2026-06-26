# Distribution & Signing — D Company ERP

How D Company ships installers to customers. Everything here is one-time setup for the company, not per-release work.

## The pipeline at a glance

```
git tag v1.2.3
        │
        ▼
GitHub Actions (release.yml)
   ├── macOS runner ──► .dmg (signed + notarized)
   ├── Windows runner ─► .exe + .msi (signed)
   ├── Linux runner ──► .apk + .aab (signed with release.keystore)
   └── macOS runner ──► iOS .xcarchive (Transporter.app uploads to App Store / TestFlight)
        │
        ▼
GitHub Release attaches the binaries
        │
        ▼
download/index.html auto-points to the latest release
```

Everyone with a download link always gets the latest **signed** build. Updates are manual: users come back to the download page when a release is announced.

## One-time accounts you'll need

| Platform | Account | Cost | What it gets you |
|---|---|---|---|
| **macOS** | [Apple Developer Program](https://developer.apple.com/programs/) | **$99/yr** | "Developer ID Application" cert for signing + notarization (no Gatekeeper warning) |
| **iOS** | Same Apple Developer Program | (same $99/yr) | App Store submission, TestFlight beta distribution |
| **Windows** | [Azure Trusted Signing](https://learn.microsoft.com/azure/trusted-signing/) **(Recommended)** | ~**$10/mo** | Modern cloud-signed certs, no USB tokens, no SmartScreen warnings after building reputation |
| Windows (alt) | DigiCert / Sectigo **EV Code Signing** | $300–$500/yr | Instant SmartScreen reputation, hardware token (USB) required |
| Windows (alt) | DigiCert / Sectigo **OV Code Signing** | $100–$200/yr | Cheaper, builds SmartScreen reputation over weeks/months |
| **Android (Play Store)** | [Google Play Console](https://play.google.com/console/) | **$25** one-time | Publish to Play Store; required for the public APK / AAB |
| **Android (sideload)** | none | $0 | Just generate a keystore once; users enable "Install unknown apps" |

Total to ship to all four platforms with no warnings: ~**$220 first year**, ~**$219/yr** after (Apple + Azure Trusted Signing + one-time Google).

## macOS — Developer ID + Notarization

### One-time

1. Enrol in the Apple Developer Program ($99/yr).
2. In Xcode → Settings → Accounts → "Manage Certificates", create a **Developer ID Application** certificate. Export it as a `.p12` with a password.
3. Generate an **app-specific password** at [appleid.apple.com](https://appleid.apple.com/account/manage) → Security → App-Specific Passwords.
4. Set these GitHub Actions secrets:
   - `APPLE_CERTIFICATE_BASE64`     – the .p12, base64-encoded
   - `APPLE_CERTIFICATE_PASSWORD`   – the .p12 password
   - `APPLE_SIGNING_IDENTITY`       – e.g. `Developer ID Application: D Company (A1B2C3D4E5)`
   - `APPLE_ID`                     – your Apple ID email
   - `APPLE_APP_SPECIFIC_PASSWORD`  – the app-specific password from step 3
   - `APPLE_TEAM_ID`                – the 10-char Team ID (Apple Developer → Membership)

### Per release
Tagging `v1.2.3` triggers `release.yml` → the macOS job signs the .dmg with the cert and submits it to Apple's notarization service. The result lands in the GitHub Release.

## Windows — Code Signing

### One-time (Azure Trusted Signing, recommended)
1. Create an Azure subscription. Open [Trusted Signing](https://portal.azure.com/#browse/Microsoft.CodeSigning%2Fcodesigningaccounts).
2. Create a Code Signing Account, an Identity Validation request (~1-2 days), and a Certificate Profile.
3. Update `release.yml` to use [`azure/trusted-signing-action`](https://github.com/Azure/trusted-signing-action) instead of the raw cert variables (drop-in replacement, comment in the YAML).

### One-time (traditional cert, alternative)
1. Buy an OV or EV Code Signing cert from DigiCert / Sectigo / Comodo.
2. Export as `.pfx` with a password.
3. Set GitHub secrets:
   - `WINDOWS_CERT_BASE64`   – .pfx, base64-encoded
   - `WINDOWS_CERT_PASSWORD` – .pfx password

### Per release
The Windows job produces both NSIS `.exe` (recommended for end users) and `.msi` (for corporate Group Policy deploys).

## Android — Keystore + Play Store

### One-time

```bash
# Generate the keystore. KEEP THIS FILE FOREVER — losing it means
# you cannot publish updates to the same app on Play Store.
keytool -genkey -v -keystore release.keystore -alias dcompany \
        -keyalg RSA -keysize 2048 -validity 10000
```

Set GitHub secrets:
- `ANDROID_KEYSTORE_BASE64`   – the keystore, base64-encoded
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`         – `dcompany`
- `ANDROID_KEY_PASSWORD`

Pay the **$25** one-time Google Play Console fee. Create the app listing with package name `cloud.dcompany.erp` (must match `capacitor.config.ts`).

### Per release
The Android job signs and emits both an `.apk` (for direct download from the website) and an `.aab` (the Android App Bundle that Play Store requires). Upload the `.aab` to Play Console for review (typically 1-3 days for the first release, hours for subsequent ones).

## iOS — App Store + TestFlight

This is the only platform where the publish step is **not** fully automated; Apple requires a manual click in App Store Connect.

### One-time
1. Apple Developer Program ($99/yr — same membership as macOS).
2. In App Store Connect, create the app entry with bundle ID `cloud.dcompany.erp`.
3. Install Apple's free [Transporter app](https://apps.apple.com/app/transporter/id1450874784) on a Mac.

### Per release
1. CI produces an `.xcarchive` (the iOS-build job in `release.yml`).
2. Open it in Xcode → **Distribute App** → **App Store Connect** → **Upload**. Or use Transporter with a signed `.ipa`.
3. In App Store Connect, the build appears under TestFlight in 5-30 minutes.
4. Add it to a TestFlight group → testers get an invite link.
5. To go fully public on the App Store, submit for review (1-3 days for first release).

### POS-app caveats
Apple's review team checks that the app isn't selling physical goods through the App Store (which would require their in-app purchase 30% cut). Café POS is *not* selling digital goods, so it's fine — but be ready to explain that in the review notes.

## Version bumping (each release)

```bash
# 1. Decide the version
NEW_VERSION=1.2.3

# 2. Bump in the four files where it lives
sed -i '' "s/\"version\": \".*\"/\"version\": \"$NEW_VERSION\"/" frontend/package.json
sed -i '' "s/\"version\": \".*\"/\"version\": \"$NEW_VERSION\"/" frontend/src-tauri/tauri.conf.json
sed -i '' "s/^version = \".*\"/version = \"$NEW_VERSION\"/" frontend/src-tauri/Cargo.toml
sed -i '' "s/^__version__ = .*/__version__ = \"$NEW_VERSION\"/" backend/app/__init__.py

# 3. Update Android versionCode in frontend/android/app/build.gradle (must be a monotonically increasing integer)
# 4. Update iOS CFBundleShortVersionString in frontend/ios/App/App/Info.plist

# 5. Commit, tag, push
git add -A && git commit -m "chore: release v$NEW_VERSION"
git tag "v$NEW_VERSION"
git push --follow-tags
```

The tag push triggers `release.yml` and within ~30 minutes all installers are signed and attached to a new GitHub Release.

## Updating the download page

The CI workflow can be extended to rewrite `download/index.html` on each release (`RELEASE.version` and `RELEASE.downloads` keys). Until that script lands, edit those two values by hand and commit.

## What to do if a release has problems

1. **Yank the GitHub Release** (mark as draft). The download page falls back to the previous one.
2. Fix the bug.
3. Bump version, tag, push.
4. Tell affected users via email / in-app banner.

There is no rollback inside an installed app (we picked manual updates). Users on the bad version keep running it until they download again.

## Privacy / legal hooks

- Each installer should ship with a link to `privacy.html` and `terms.html` (hosted on `dcompany.cloud`).
- iOS App Store requires a privacy policy URL in the app's listing.
- Play Store requires the same plus a data-safety form (what data you collect; for D Company: cashier email + name, customer phone if entered).
