# D Company ERP 3.1.9 (code 20) release candidate

Code 20 is the corrected immutable release identity. Code 18 failed its backend
gate and Code 19 later passed the coordinated product and emulator gates but
exhausted runner memory while lint compiled both release variants concurrently.
Neither tag produced a signed APK; neither may be moved or reused.

Code 20 preserves the already tested application source. Its release workflow
runs Play and direct lint, JVM tests, and package compilation sequentially with
one Gradle worker and an isolated 4 GiB in-process Kotlin compiler. This changes
build resource scheduling, not application behaviour. It also gates the signing
job behind an owner-reviewed GitHub Environment and makes production preparation
replace and validate stale predecessor `APP_VERSION` metadata rather than
silently carrying it into the new images.

Only a completely green `v3.1.9` workflow may produce the signed direct APK and
matching `release-manifest.json` used for staging. Production backend/web must
be deployed and smoke-tested before the staged row is activated. Keep the
compatibility minimum at code `8`, update code `14` in place, and do not clear
tablet data or an offline outbox.

The isolated signing job is additionally gated by the
`android-release-signing` GitHub Environment. That environment permits only
`v*` tags and requires explicit repository-owner approval before the signing
secrets become available.
