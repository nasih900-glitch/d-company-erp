# Mobile builds (Capacitor)

Capacitor wraps the same React app into native Android + iOS shells.

## First-time setup (do this once)

```bash
cd frontend
npm install

# Generate the native projects (creates ./android and ./ios folders).
# Only needs to be run once per platform; commit the resulting folders.
npx cap add android
npx cap add ios   # macOS only, Xcode required
```

## Building (each release)

```bash
# 1. Build the web bundle with the production API URL baked in
VITE_API_URL=https://api.dcompany.cloud/api/v1 \
VITE_APP_VERSION=$(git describe --tags --always) \
npm run build

# 2. Push the new bundle into the native projects
npx cap sync

# 3a. Android APK (Linux/Mac/Windows)
cd android
./gradlew assembleRelease       # → app/build/outputs/apk/release/app-release-unsigned.apk
./gradlew bundleRelease         # → app/build/outputs/bundle/release/app-release.aab (Play Store)

# 3b. iOS (Mac + Xcode only)
npx cap open ios
# In Xcode: Product → Archive → Distribute App → App Store Connect / TestFlight
```

## App Store review build

Use the dedicated script for Apple submission:

```bash
cd frontend
npm run ios:prepare:store
npm run ios:open
```

This builds with `VITE_APP_STORE_REVIEW=true`, which hides hookah/tobacco-related surfaces for App Review while leaving the normal web/VPS build unchanged.

See `../docs/APP_STORE_SUBMISSION.md` for the full App Store checklist.

## Signing

### Android
Generate a keystore once and KEEP IT SAFE — Google Play will reject any APK signed with a different key for the same app ID.

```bash
keytool -genkey -v -keystore release.keystore -alias dcompany \
        -keyalg RSA -keysize 2048 -validity 10000
```

Put the keystore path + passwords in `android/keystore.properties` (git-ignored). See `docs/DISTRIBUTION.md`.

### iOS
Code signing happens through Xcode + your Apple Developer account. Xcode handles certificate creation and provisioning profiles automatically when you sign in with your Apple ID and have a paid Developer Program membership.
