# D Company ERP App Store Submission Checklist

This project is now prepared as a Capacitor iOS app, but final App Store upload still has to be done from Xcode with your Apple Developer account.

## What Apple Will Check

- Build with the current required Apple SDK in Xcode 26 or newer.
- The app must be complete, stable, and use HTTPS for backend traffic.
- Privacy details in App Store Connect must match the app's data collection.
- A working support URL and privacy policy URL must be provided.
- The reviewer must be able to sign in with a demo account.
- Apps that facilitate tobacco, vape, or other controlled-substance sales can be rejected, so the App Store review build hides hookah/tobacco surfaces.

Official references:

- https://developer.apple.com/app-store/submitting/
- https://developer.apple.com/app-store/review/guidelines/
- https://developer.apple.com/documentation/bundleresources/privacy-manifest-files

## Build The App Store Version

Run this from the project frontend folder:

```bash
cd "/path/to/d-company-erp/frontend"
npm run ios:prepare:store
npm run ios:open
```

The `ios:prepare:store` script builds with:

- `VITE_API_URL=https://dcompany.duckdns.org/api/v1`
- `VITE_ROUTER_MODE=hash`
- `VITE_APP_STORE_REVIEW=true`

That last flag is important. It keeps the normal ERP behavior for the live web/VPS build, but hides hookah/tobacco-related UI in the App Store build.

## Xcode Steps

1. Open `frontend/ios/App/App.xcodeproj`.
2. Select the `App` target.
3. Confirm the bundle identifier is `cloud.dcompany.erp`.
4. Set Team to your paid Apple Developer team.
5. Confirm signing is automatic.
6. Confirm deployment target and device families are correct for iPhone/iPad.
7. Product -> Archive.
8. In Organizer, Distribute App -> App Store Connect -> Upload.

The live backend must allow the Capacitor app origins in `CORS_ORIGINS`:

```text
https://localhost
capacitor://localhost
ionic://localhost
```

Without those origins, the iOS app opens but login fails with a generic network error.

If Xcode blocks you with license terms, run this in Terminal first:

```bash
sudo xcodebuild -license
```

If Xcode says the iOS platform is not installed, open Xcode -> Settings -> Components and install the latest iOS platform, then archive again.

## App Store Connect Metadata

Use these values unless you want different branding:

- App name: `D Company ERP`
- Category: `Business`
- Secondary category: `Productivity`
- Privacy Policy URL: `https://dcompany.duckdns.org/privacy.html`
- Support URL: `https://dcompany.duckdns.org/support.html`
- Copyright: `D Company`

You must deploy the latest frontend before submission so those two URLs work publicly.

## Privacy Labels

The iOS project includes `PrivacyInfo.xcprivacy`, but App Store Connect privacy labels still need manual entry.

Expected data categories:

- Contact Info: name, email address, phone number
- Identifiers: user ID
- Purchases: purchase history
- Financial Info: business finance/expense records

Expected answers:

- Data is linked to user/business records.
- Data is used for app functionality.
- No third-party advertising.
- No tracking across other companies' apps or websites.

## Review Notes

Put this in App Review notes:

```text
D Company ERP is a private staff/admin ERP for our cafe operations. It manages POS, orders, inventory, finance, audit logs, reports, customers, memberships, and staff access.

The app does not sell digital goods or unlock digital content for consumers. Any payments recorded in the app are for physical goods/services handled by the business outside Apple's digital content system.

This App Store build hides controlled-substance/tobacco-related business surfaces. The review account has limited demo data and can be used to inspect the app.

Demo login:
Email: [create a dedicated reviewer account]
Password: [create a temporary reviewer password]
```

Do not give Apple your real owner password. Create a separate review account with enough permissions to inspect the app and delete or rotate it after review.

## Screenshots Needed

Prepare screenshots for each device size Apple asks for in App Store Connect. Use clean demo data only.

Recommended screens:

- Login
- POS
- Inventory
- Reports
- Audit Log locked state
- Settings or dashboard

## Hard Blockers Before Upload

- Accept the Xcode license on this Mac.
- Install the latest iOS platform from Xcode -> Settings -> Components.
- Create a dedicated App Review demo account.
- Deploy latest frontend so `/privacy.html` and `/support.html` are live.
- Confirm production `CORS_ORIGINS` includes the Capacitor iOS origins listed above.
- Confirm the live backend is reachable over HTTPS from outside your network.
- Archive and upload from Xcode with your Apple Developer team.
