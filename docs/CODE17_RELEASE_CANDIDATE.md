# D Company ERP 3.1.6 (code 17) release candidate

Code 17 is a new signed Android identity. Code 16 (`3.1.5`) remains its
immutable predecessor and must not be rebuilt or overwritten. Code 17 is not a
production deployment merely because an APK exists or its automated checks
pass.

## Scope

- Preserve the Gaming Centre-first staff workflow and Finance reporting added
  in the earlier releases.
- Add first-party ERP-only remote assistance for a protected owner.
- Keep the existing restrained Android visual language. No unapproved colour
  redesign is included in this release.
- Keep one Hybrid Gaming + POS workspace visible to staff. A workspace is not
  the same thing as a physical Android installation; every enrolled tablet
  remains independently visible and revocable in the owner console.

## Remote-assistance security boundary

Remote assistance is deliberately narrower than a whole-device remote-control
product:

- the Android user must review and accept the owner's request in Help;
- every active session has a persistent Android notification and an in-app Stop
  control;
- a session is limited to 15 minutes and stops when the ERP leaves the
  foreground, consent is revoked, the device key is revoked, or the visible
  indicator is unavailable;
- capture uses only the D Company ERP activity window and only the Help route;
- other ERP routes and sensitive overlays produce a fixed privacy placeholder;
- the owner may open Help, refresh Help, request the existing safe diagnostics
  pipeline, or end the session;
- raw taps, arbitrary text entry, shell access, file access, microphone,
  camera, other-app capture, payments, voids, refunds, Shift close, and
  arbitrary navigation are not implemented;
- owner access requires the protected `admin.system` permission and every
  request, decision, session, command, device approval, rotation and revocation
  is retained as tenant-scoped audit evidence;
- every Android installation uses its own non-exportable P-256 AndroidKeyStore
  key. An owner must enter the 12-character code displayed on the physical
  tablet before the key becomes active;
- signed device requests use bounded clock skew and one-use Redis nonces. The
  latest JPEG is re-encoded and retained only in Redis under a short TTL; it is
  never written to PostgreSQL or an offline queue.

This is a support and diagnosis facility, not TeamViewer. It cannot control the
Redmi Pad operating system and it cannot silently connect without the tablet
user's approval.

## Release gates

The candidate may be handed off only after all of the following have current,
recorded evidence:

- [ ] backend unit and integration suites;
- [ ] migration `0062` clean upgrade, failure-on-legacy-data, downgrade and
  re-upgrade proof against disposable PostgreSQL;
- [ ] real Redis device-signature replay and frame-relay checks;
- [ ] frontend typecheck, zero-warning lint, full tests and production build;
- [ ] Android direct-release JVM tests, lint and assembly;
- [ ] Android debug and instrumentation APK assembly;
- [ ] signed APK package, code/name, SHA-256 and signing certificate checks;
- [ ] in-place code 16 to code 17 emulator upgrade without clearing app data;
- [ ] API-35 emulator instrumentation and authenticated visual smoke where
  credentials are available;
- [ ] Firebase physical-device install/launch and relevant instrumentation;
- [ ] secret scan and forbidden-capability scan;
- [ ] no production deployment or production business-data mutation.

Firebase Pixel coverage is additional device evidence. It is not physical
Redmi Pad 2 / HyperOS acceptance, and it does not prove an authenticated live
owner-to-tablet session unless a dedicated staging backend and test identities
are used.

## Time-bounded web dependency disposition

Code 17 pins `react-router-dom` and `react-router` to `6.30.5`. This removes the
actionable v6.30.4 XSS/open-redirect advisory. Two moderate upstream advisories
remain without a v6 patch:

- `GHSA-wrjc-x8rr-h8h6` (backslash open redirect); and
- `GHSA-337j-9hxr-rhxg` (SSR hydration constructor injection).

The SSR advisory is not reachable because this is a client-only Vite SPA with
no server rendering or hydration. The redirect advisory is contained by the
closed `internalAppRouteOr` boundary at every data-driven Router destination;
it rejects absolute and scheme-relative URLs, raw/encoded separators,
backslashes, scripts, dot segments, query strings, fragments, whitespace and
control characters. Focused tests cover 20 accepted/rejected cases, and the
remaining Link/Navigate destinations are fixed source constants.

This exception expires on **2026-09-30 or before Code 18, whichever comes
first**, and is owned by the release maintainer. React Router 7.18+ must be
evaluated in a separate compatibility cycle. Introducing SSR or any
user/server-controlled return URL, redirect, `to`, `navigate`, or Router
basename reopens this as a release blocker immediately.

## Deployment prerequisites

Before the backend portion can be deployed, production must receive a dedicated
`REMOTE_ASSISTANCE_PAIRING_SECRET` generated independently from the JWT secret.
Redis must be healthy because device replay protection and the short-lived frame
relay fail closed without it. Run migration `0062` before enabling the owner
Device Centre.

Do not stage or activate the code 17 server update until the exact signed APK is
hosted at an immutable same-origin HTTPS URL and its public bytes, package,
version, size, SHA-256 and signing certificate have all been verified.
