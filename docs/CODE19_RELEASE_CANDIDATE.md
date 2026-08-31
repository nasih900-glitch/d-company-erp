# D Company ERP 3.1.8 (code 19) release candidate

Code 19 is the corrected immutable identity for the standard-premium Android,
backend, and web release. Tag `v3.1.7` / code `18` failed its backend release
gate before signing and produced no authorised APK; it must never be moved or
reused.

## Scope

- Preserve the Code 18 source-level visual and runtime hardening.
- Remove the two timing-dependent release-test failures exposed by the tagged
  workflow without weakening production behaviour.
- Coordinate Android, backend, frontend, and production metadata at `3.1.8` /
  code `19`.
- Permit a one-time, fail-closed deployment bridge only from the exact observed
  Code 14 production image, version, source commit, and database head.

## Release authority

Only a fully green tagged `v3.1.8` GitHub Actions run may produce the signed APK
and `release-manifest.json` used for staging. Local builds and old evidence
bundles are not release artifacts. Staging is non-advertising; activation is a
separate authenticated owner action after production deployment and smoke
testing.

Keep the compatibility minimum at code `8`. The tablet must update in place from
the installed, same-signer code `14` app; do not uninstall or clear app data.

## Required gates

- backend migrations and full tests;
- frontend audit, lint, typecheck, tests, and production build;
- Android release contracts, JVM tests, lint, signed assembly, package identity,
  signer, checksum, and emulator instrumentation;
- exact CI APK/manifest staging verification;
- production backup and restore proof, migration, readiness, and authenticated
  Gaming/POS/Finance smoke;
- code `14` update offer, in-place installation, offline/outbox preservation, and
  one-tablet canary validation before wider rollout.

Physical Redmi Pad 2 behaviour remains a separate acceptance gate. Do not call
the release bug-free or physical-device verified on CI/emulator evidence alone.
