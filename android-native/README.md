# android-native — incomplete scaffold, does not build yet

A ground-up Kotlin/Compose rewrite of the Android app, intended to replace the
Capacitor WebView shell in `frontend/android`.

**Status: foundation only. This module does not compile and produces no APK.**
It is committed so the design decisions below are not lost, not because it runs.

## What exists

| File | What it is |
| --- | --- |
| `build.gradle.kts`, `settings.gradle.kts`, `app/build.gradle.kts` | AGP 8.7.3 / Kotlin 2.0.21, Compose BOM, Retrofit + kotlinx-serialization, DataStore |
| `app/src/main/java/.../ui/theme/Theme.kt` | Brand palette and Material3 dark scheme, shared with the web and iOS apps |
| `app/src/main/java/.../core/net/Dtos.kt` | Auth wire models, field-for-field against the FastAPI Pydantic schemas |

## What is missing before it builds

`AndroidManifest.xml`, `MainActivity`, the Retrofit/OkHttp client and auth
interceptor, token storage, and every screen. In short: everything but the
foundation.

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
