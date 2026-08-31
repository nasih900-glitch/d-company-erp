# D Company ERP 3.1.8 (code 19) rejected release attempt

Status: **rejected before signing**. The coordinated backend/web and Android
instrumentation gates passed, but the unsigned Android job compiled both
release variants concurrently and exhausted the hosted runner. It produced no
authorised APK and was never staged or advertised. Do not move or reuse tag
`v3.1.8`. The corrected identity is `3.1.9` / code `20`; see
[`CODE20_RELEASE_CANDIDATE.md`](CODE20_RELEASE_CANDIDATE.md).

Code 19 was the corrected immutable identity for the standard-premium Android,
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

The release contract required a fully green tagged workflow before signing, so
this failed run produced no APK or `release-manifest.json` that can be staged.
Local builds and old evidence bundles are not substitutes. Code 20 retains the
same non-advertising staging and separate authenticated-owner activation rules.

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
