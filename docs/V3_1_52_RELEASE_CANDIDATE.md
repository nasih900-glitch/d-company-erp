# v3.1.52 / Android 59 release candidate

This successor corrects the deployment guard for the already pinned frontend
Nginx package and keeps the wide Login version footer outside the keyboard-visible
scrolling controls. Android is 59 / 3.1.52; Room remains 58 and Alembic remains
0093. Dial, billing, permissions, business models, backgrounds, glass and workspace
navigation retain the immutable Build 58 implementation.

The signed Build 58 candidate passed hosted CI, reproducibility, original-certificate
signing and original 45/49/50/54 offline empty-database replacement tests. Its
real server preflight then refused before authentication, maintenance or
migration: only frontend image metadata `NGINX_VERSION=1.31.5` to `1.31.8` differed.
The original five live Build 54 services, environment and schema 0091 remained exact.
Keep Build 58's source, immutable tag, signed artifacts and refusal evidence intact;
it was not offered. Its unconsumed Owner session expired and was retired.

One shared transition contract now admits exactly that frontend metadata
change in both candidate preflight and post-start runtime checks. All other
environment values remain unchanged, including business settings and secrets.
Prior runtime hashes and command/image pins remain strict. Frontend Compose
NGINX_VERSION overrides and env_file masking are refused. The existing exact
backend Python patch remains unchanged. Refusals name the affected service
without printing environment contents.

The corrected core was rehearsed read-only against the original protected Build 58
server inputs: frozen Build 58 rejects them, the new core accepts all five, and wrong
Nginx, Compose masking and unrelated-setting controls still reject. This is
compatibility evidence, not Build 59 release authority. Focused tests independently
record public old/new metadata rather than generating it from the expected
helper. Both CI image-store lanes also inspect the actual built frontend image
metadata before deployment overrides. The earlier 70-case daemon oracle remains
synthetic Compose-merging evidence, not a real private Build 54 environment fixture.

Wallpaper cancellation verification now observes actual test-provider entry
and holds the provider until an explicit release. It checks both an import
retry and preset selection from another store instance, with bounded cleanup,
caller verification and single-use fixture tokens. This replaces unobserved
timing assumptions without changing production locking or timeouts. Repeated
real-provider tests and deliberately broken shared-lock controls verify that
the assertions remain sensitive to overlapping work; fresh hosted acceptance
is still required.

The Login regression uses the actual software keyboard for both fields, verifies
that the footer cannot cover visible controls, scrolls the complete submit target
into the keyboard-visible area, and submits the unchanged values twice. The old
layout fails the overlap assertion; the measured-footer fix passed repeated
focused runs and the complete Login test class on the API 35 tablet emulator.

The remote-assistance slow-upload regression now mocks only its router's thread
adapter instead of changing AnyIO globally. Readiness has a separate ten-second
bound and a delayed-start control; the actual revoke and upload assertions retain
their two-second limits. Retaining the database lock deliberately fails both
controls at the revoke assertion. No production upload behavior changed.

One hosted backend run returned update_policy_unavailable before Gaming recovery
reached its handler. Isolated replay did not reproduce that failure, so its cause
remains unproved. Existing fail-closed handling now records only the lookup phase,
exception class, elapsed time and task state. It excludes exception text, SQL,
URLs, credentials and tracebacks, and logging failure cannot replace the original
503 response. The 0.8-second lookup bound, shielding, single-flight and runtime
parity checks are unchanged. Fresh full regression and hosted CI remain required;
the diagnostic change alone does not establish an availability fix.

Preserve every historical freeze-map assignment, adding a separate Build 59 overlay
above immutable Build 58 at `6eb61bf097afb7d6e306f3878a64f266adf451a1`. Require current
full source/backend/web/native tests, both real image stores, reproducibility,
original-certificate signing and 45/49/50/54 upgrades. Offer only after fresh
guarded production acceptance and independent staged-byte verification. Keep
the deployed floor 54 and policy revision 4 unchanged.

Physical Redmi Pad 2 acceptance remains unavailable. Emulator, performance,
signed-artifact, production and download results require separate evidence;
neither earlier passes nor the compatibility rehearsal prove these gates.
