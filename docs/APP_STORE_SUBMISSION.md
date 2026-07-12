# D Company Private App Submission Checklist

This app is now positioned as a private/custom D Company ERP app. Do not submit it as a normal public App Store app. Apple already flagged the public submission under Guideline 3.2 because the app is intended for one business, its staff, owners, partners, or approved operators. That assessment is correct.

## Current Private App Build

- App name: `D Company ERP`
- Bundle ID: `cloud.dcompany.erp`
- Version: `1.0`
- Build: `7` or newer
- iOS root view: `NativeERPAppView`
- Backend: `https://dcompany.duckdns.org/api/v1/`
- Sign-in: required
- Distribution: Apple Business Manager custom app / private distribution

## What The App Is

D Company ERP is a private operations app for D Company cafe, games, lounge, shisha, and streaming operations. It is for authorized users only.

The app includes:

- POS billing and order handling
- Gaming, VR, simulator, shisha, and streaming session management
- Inventory, stock movement, and supplier control
- Pricing, settings, staff, and owner controls
- Reports, analytics, P&L, and operational summaries
- Audit logs for sensitive business actions

It is not a general public consumer app. It should not be discoverable by normal App Store users.

## Apple Review Position

Use this direction in App Review responses:

```text
We agree this app is intended for a specific business. This submission is being moved to private/custom distribution through Apple Business Manager. D Company ERP is for authorized D Company staff, owners, and approved business users only. It is not intended for general public discovery on the App Store.

The app supports private business operations including POS, inventory, session management, reports, pricing/settings, and audit logs. Sign-in is required and access is granted by D Company.
```

## App Store Connect Metadata

Use these values:

- App name: `D Company ERP`
- Subtitle: `Private cafe operations`
- Category: `Business`
- Secondary category: `Productivity`
- Privacy Policy URL: `https://dcompany.duckdns.org/privacy.html`
- Support URL: `https://dcompany.duckdns.org/support.html`
- Copyright: `D Company`
- Sign-in required: checked

Keywords:

```text
erp,pos,inventory,reports,audit,cafe,operations
```

Promotional text:

```text
Private operations app for D Company POS, inventory, reports, sessions, pricing, and audit tracking.
```

Description:

```text
D Company ERP is a private custom operations app for authorized D Company users. It supports cafe POS, orders, inventory, gaming and lounge sessions, shisha and streaming sessions, pricing controls, staff workflows, reports, P&L visibility, and audit tracking.

Access is restricted to approved D Company users. Sign-in is required.
```

## Privacy Labels

Do not answer `Data Not Collected` for this private ERP build. That was only suitable for the old public venue shell.

Recommended App Store privacy direction:

- Tracking: `No`
- Third-party advertising: `No`
- Data sold: `No`
- Data used for third-party advertising: `No`

Data handled by the app may include:

- Name, email address, user ID, role, and login status
- Phone number or customer contact details when entered for orders or billing
- Purchases, orders, invoices, refunds, payments, and POS records
- Inventory records, suppliers, stock movements, and pricing changes
- Staff activity, audit logs, timestamps, and security events
- Photos or scans only when an authorized user chooses OCR/document scanning
- Device, IP, error, and server logs needed for security and reliability

Purpose:

- App functionality
- Business operations
- Fraud prevention and security
- Legal, accounting, tax, and audit compliance

Do not mark any data as used for advertising or cross-app tracking.

## Screenshots

For private/custom distribution, ERP screenshots are acceptable, but use demo data only.

Upload:

- iPhone 6.5-inch screenshots
- 13-inch iPad screenshots

Recommended screenshot set:

- Login
- Home/workspace
- POS checkout
- Gaming/session management
- Inventory
- Reports/P&L
- Audit Log locked screen or clean demo audit entries

Do not show:

- Real customer data
- Real phone numbers
- Real payment details
- Real owner passwords
- Real audit entries
- Live private financial numbers

## Review Notes

Enter the demo account only in App Store Connect review fields, not in committed files.

```text
This app is a private custom app for D Company operations. Sign-in is required. Access is limited to authorized D Company users.

The app supports private business workflows including POS, inventory, gaming/shisha/streaming session management, pricing controls, reports, and audit logs.

Please do not publish this app for public App Store discovery. It is intended for private/custom distribution to D Company through Apple Business Manager.
```

## Before Resubmission

1. Cancel or replace the public App Store review submission if it is still active.
2. In App Store Connect, use private/custom distribution rather than public App Store availability.
3. Add the D Company Apple Business Manager organization/customer for custom app distribution.
4. Archive and upload build `7` or newer from Xcode.
5. Select the new build in App Store Connect.
6. Set sign-in required to checked.
7. Fill privacy labels as data collected, not `Data Not Collected`.
8. Upload only demo/sanitized screenshots.
9. Put demo credentials in the App Review sign-in fields.
10. Submit the private/custom app for review.

## Separate Public ERP Product

If you want a public SaaS ERP later, keep it separate from D Company. Use the separate public-product plan in `docs/PUBLIC_ERP_PRODUCT.md`. That product needs generic branding, public signup, company workspace creation, tenant isolation, subscription/payment design, and public support/legal pages.
