# D Company ERP 3.1.9 (code 20) rejected release attempt

Status: **rejected before signing**. Code 20 passed the coordinated product,
emulator, lint, JVM-test, and unsigned-package gates. Its isolated signer then
failed closed before reading signing secrets because `apkanalyzer` was not on
the restricted runner PATH. No signed APK or release manifest was produced;
tag `v3.1.9` is immutable and must not be moved or reused. The corrected
identity is `3.1.10` / code `21`; see
[`CODE21_RELEASE_CANDIDATE.md`](CODE21_RELEASE_CANDIDATE.md).

Code 20 was the corrected immutable release identity. Code 18 failed its backend
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

The tagged Code 20 workflow is not green and cannot produce a release. Its
unsigned artifacts are not staging authority. Keep the compatibility minimum
at code `8`, update code `14` in place only from a later authorised candidate,
and do not clear tablet data or an offline outbox.

The isolated signing job is additionally gated by the
`android-release-signing` GitHub Environment. That environment permits only
`v*` tags and requires explicit repository-owner approval before the signing
secrets become available.
