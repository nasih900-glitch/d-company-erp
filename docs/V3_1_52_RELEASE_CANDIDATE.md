# v3.1.52 / Android 59 release candidate

This successor corrects the deployment guard for the already pinned frontend
Nginx package, keeps the wide Login version footer outside the keyboard-visible
scrolling controls and puts app-level overlays inside their current glass scene.
Android is 59 / 3.1.52; Room remains 58 and Alembic advances to 0094. The
Build 58 dial, billing and workspace foundations remain, with separately reviewed
Login/currency fixes and additive Staff attendance/policy/Sheets capabilities.
The expanded candidate requires fresh complete source, integration and release
acceptance; previous Build 58 results do not cover these additions.

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
The check reads all rectangles in one UI-thread snapshot during keyboard/scroll
animations. Diagnostic capture selects the stable Login node, and any capture
failure is suppressed onto the original assertion. The previous hosted recovery-
link failure was not reproduced locally; fresh complete hosted acceptance is
required rather than attributing that failure to timing without evidence.

Root support, sign-out, till and consent dialogs now render in the scaffold's
current scene. Global account/access/notification notices and update banners use
the existing Login, setup or workspace scene host, with one solid fallback for
other authentication states. Required-update priority, fresh-login suppression,
notice ordering, callbacks and runtime admission stay unchanged. Overlay state
remains owned by the app root. A movable overlay composition rebinds the current
scene across wide/compact layouts without recreating drafts or capture-privacy
leases; account/company keys prevent state crossing authority changes.
Missing-scene paths retain readable solid surfaces. Runtime regressions compare
actual dialog pixels under two controlled scenes and verify draft, callback and
privacy-revision retention through resizing, plus fallback rendering.

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

## Staff time and fixed monthly salary

Sheets activation is deferred by the Owner until after the Android release.
The temporary live salary register and generated costs were reversed, and the
original workbook was independently verified restored. Retained optional mirror
code remains inactive; receiver deployment and exports are outside this release's
activation scope. Staff attendance and draft agreement controls remain in scope.

Staff time is separate from the Owner access-administration screen. Ordinary
staff can see and clock their own attendance without requesting the staff
identity directory or salary policies. Owners with payroll permission can review
scoped people, correct attendance with a reason and optimistic revision, and
append immutable draft policy revisions. The API serializes each person's
attendance, preserves idempotent receipt replay and rejects overlapping/future
intervals. Legacy default zero breaks remain unconfirmed until explicit input
establishes them. Attendance versions are persisted monotonically under the
per-user lock; the Sheets projection must not regress after reordered delivery.

Alembic 0094 adds an initially empty, append-only staff policy table. It seeds no
staff, salary or hours and refuses nonempty downgrade. Preservation acceptance
covers 0091 through 0094, including retained attendance, payroll, finance, gaming,
audit and fleet records. Activating a new writer is still the forward-only
boundary; an old schema/runtime restore is not authorized after that boundary.

Native and Web writes retain the same idempotency key and exact body after an
unknown outcome. Later 4xx replies cannot prove that the original write failed.
Authority changes fence dispatch/replay and clear private projections. Native
single-attempt transport fences each network exchange, including automatic HTTP
follow-ups. No salary, overtime payment or offline clock result is invented.

The optional staff-hours Sheets capability has a separate exact schema/scope
handshake. It enrolls only people with an Owner-written policy, appends minimal
signed facts atomically with the business transaction, and holds delivery until
that generation's capability proof succeeds. Existing financial/daily exports
remain independently gated. Owner-reviewed worksheet hour overrides do not write
back to ERP or activate payroll. See [STAFF_HOURS_SHEETS.md](STAFF_HOURS_SHEETS.md).

The fixed monthly agreement is 12,000 per person. The separate Owner-editable
salary register accepts optional manual overtime and a Paid checkbox. Explicit
month/person entries generate one Running Cost allocation; checking Paid does
not deduct the cost again or transfer money. Breaks do not reduce this fixed
amount. No hourly rate, automatic overtime, historical backfill or future month
is inferred. Existing workbook sharing is retained by explicit Owner decision,
so protection controls editing, not confidentiality.

## Login and currency presentation

Wide Login handles transient zero-height keyboard insets while preserving the
focused field and entered values. Submit and recovery dismiss input consistently.
The complete Login and affected screen regressions must pass on the final source.
System-generated monetary presentation omits the rupee symbol across native,
Web and affected backend outputs. Integer minor units, INR metadata, parsing and
historical/free-text values remain authoritative. Formatter checks include signed
and extreme integer values; no currency conversion or money rounding is added.

Preserve every historical freeze-map assignment, adding a separate Build 59 overlay
above immutable Build 58 at `6eb61bf097afb7d6e306f3878a64f266adf451a1`. Require current
full source/backend/web/native tests, both real image stores, reproducibility,
original-certificate signing and 45/49/50/54 upgrades. Offer only after fresh
guarded production acceptance and independent staged-byte verification. Keep
the deployed floor 54 and policy revision 4 unchanged.

Physical Redmi Pad 2 acceptance remains unavailable. Emulator, performance,
signed-artifact, production and download results require separate evidence;
neither earlier passes nor the compatibility rehearsal prove these gates.
