# android-native — real Kotlin/Compose app, core working

A ground-up Kotlin/Compose rewrite of the Android app, intended to replace the
Capacitor WebView shell in `frontend/android`. No WebView, no HTML, no
JavaScript: Compose renders every pixel.

**Status: builds, installs, runs, and talks to live production.** Verified on an
Android 15 tablet emulator (2560×1600): the app launches, raises the real OS
notification-permission dialog, renders the Compose login screen, and a sign-in
attempt returns the backend's own `invalid credentials` message through the
error interceptor. No crashes in logcat.

Not yet a replacement for the shipping app — see "What is missing" below.

## What works

| Area | State |
| --- | --- |
| Gradle / AGP 8.7.3, Kotlin 2.0.21, Compose BOM | builds `assembleDebug` and `assembleRelease` |
| `ui/theme/Theme.kt` | brand palette, Material3 dark scheme, forced light system-bar icons |
| `core/net/ApiClient.kt` | OkHttp + Retrofit + kotlinx-serialization, auth interceptor, single-flight 401 refresh, error-envelope unwrapping |
| `core/auth/TokenStore.kt` | DataStore-backed tokens, cleared only on a definitive 401/403 |
| `core/alarm/AlarmReceiver.kt` | `setExactAndAllowWhileIdle` via AlarmManager + IMPORTANCE_HIGH channel |
| `ui/screens/LoginScreen.kt` | Compose sign-in, real server errors surfaced |
| `ui/screens/PosScreen.kt` | live menu grid + cart with Indian-format ₹ money |

## What is missing

Everything except POS browsing: bill preparation, payment capture and the
idempotent-retry recovery, shift open/close, gaming timers, tables, kitchen
display, inventory, customers, finance, reports, analytics, settings. Order
creation and payment endpoints are declared in `ErpApi` but not yet wired to a
checkout flow — the "Prepare bill" button is intentionally inert rather than
half-wired, because a partially-correct payment path is worse than none.

## Two decisions worth keeping

**Same signing key as the Capacitor build.** Android identifies an app by
`(applicationId, signing key)`. This module shares `cloud.dcompany.erp`, so
signing it with a different key would make it refuse to install over the app
already on the partners' tablets. `keystore.properties` and the `.keystore`
file are gitignored and must be copied in locally — never committed.

**System bar icons are hard-coded light.** `DCompanyTheme` sets
`isAppearanceLightStatusBars = false` unconditionally rather than deriving it
from the device theme. Deriving it is exactly the bug that made the clock and
battery invisible on a light-themed device in the Capacitor build.

## The app that is actually in use

`frontend/android` — Capacitor, shipping, signed with this same key. Until this
module can log in, place an order and take a payment, that remains the real
Android app and this one should not be installed on a till.
