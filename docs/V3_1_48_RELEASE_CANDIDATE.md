# v3.1.48 / Android build 55 release candidate

This replaces the withheld, unsigned v3.1.47 candidate. Preserve that tag at
`58d89f7ea1ccf33d9415cfe361eb59af3d104e91`; it must not be moved. Its release
workflow stopped before APK signing or draft publication. A fresh read-only
production check found no registered release or reported Android installation
at code55 or higher. Code55 remains unused for production distribution; the
replacement has a new coordinated version name and will use a new immutable tag.

## Application scope

The approved liquid-glass workspace, original logo, shared selectable workspace
background, sidebar gestures, Build54 dial geometry and transitions, keyboard
reachability, session lifecycle, durable voids, billing and shift recovery are
carried forward. Gaming voids below thirty minutes permit an empty reason;
thirty minutes or more require one. POS-linked voids require Owner authority,
and served add-ons remain billable. Existing Login methods remain unchanged.

The database transition remains0091→0093 and Android Room remains58. Production
remains Build54 until guarded deployment passes. Historical financial, Audit,
release and tablet-queue evidence must be preserved. The exact current-version
profile in [BUILD55_GUARDED_DEPLOYMENT.md](BUILD55_GUARDED_DEPLOYMENT.md) applies;
it does not authorize a general migration or a queue drain/clear operation.

## Security corrections

The failed image scans identified CVE-2026-111497 in native Brotli and
GHSA-mgcc-cww5-fp2q in both major versions of go-jose embedded in Caddy.
The replacement pins the verified Alpine Brotli1.2.1-r0 package, the official
go-jose v3 RSA-JWK backport commit
`0e502b87c7aa651155b11ae8e195ec33ce10230a`, and go-jose v4.1.7. The v3 fix is an
official unreleased commit selected by its canonical Go pseudo-version and
checksum; it is not an unreviewed fork or a severity suppression.

The existing pinned Go toolchain, complete standard Caddy module set, CA trust,
bind capability and runtime package-preservation checks remain required. Exact
binary, source and image identities must be regenerated and independently
verified. Both image stores and all five runtime images must pass their full
SBOM/CVE gates. No new ignore or VEX exception is authorized by this document.

Upstream v3 still has two Moderate advisories without v3 fixes:
[JSON parsing](https://github.com/advisories/GHSA-vvm8-gf78-28g4) and
[CBC-HMAC processing](https://github.com/advisories/GHSA-4w3g-jfj2-cw2q).
Those are separate documented dependency limitations. This candidate
does not claim zero third-party vulnerabilities. Critical/High findings must
be resolved and the complete fresh image scans must pass before signing.

## Verification and delivery

The preceding58d89f7e source passed all647 hosted Android instrumentation cases,
including ordered background persistence, component stress and positive alarm
reruns, plus native JVM/lint, frontend and2126 backend tests. That evidence
remains attached to its original source and is not a fresh pass for this
replacement. Refreeze and rerun affected contracts, builds and full release
gates after the security and version changes.

Required delivery gates remain independent: protected signing, original-key
45/49/50/54→55 installation tests, fresh business quiescence, normal-login
authenticated acceptance, non-destructive guarded deployment, immutable APK
staging, Owner activation and public update verification. No signed artifact,
deployment, staging or offer is asserted here.

The Redmi Pad2 is unavailable. The user accepted emulator testing; presentation
measurements and prolonged workflow evidence retain their documented scope and
limitations. They do not prove physical installation, hardware behavior,
perfect60fps, absence of all leaks or a crash-free application.
