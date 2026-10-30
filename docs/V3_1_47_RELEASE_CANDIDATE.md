# v3.1.47 / Android build 55 release candidate

This candidate implements the owner-directed liquid-glass workspace and Gaming
workflow corrections. The local core-screen review is separate from final
performance acceptance, release signing, in-place upgrades, production
installation and offering an update. This document records scope and required
evidence; it does not assert those later gates have passed.

## Reviewed corrections

- The Gaming dial uses distinct clock positions: 30 minutes at the bottom,
  with 60 minutes revealed after clockwise movement past 30. Published package
  identity, price and Start remain aligned; jitter cannot wrap the hour to
  thirty. The ring retains its geometry through the session states.
- Gaming Void covers eligible active, paused and stopped unpaid sessions before
  POS. Below 30 minutes a reason is optional; at or above 30 minutes it is
  required. POS-linked void operations require the Owner. Original amounts,
  attendance, settlement and attribution remain in immutable audit/void evidence;
  the voided Gaming charge becomes non-billable. Served add-ons remain payable
  through a separately retained bill and immutable line snapshots. Exact review
  revisions, idempotency, workspace, shift and pending-work guards remain enforced.
- Inline player and drink drafts are bound to the exact session. Drink reviews
  retain the selected catalogue snapshot and require a new review if it changes.
  Valid offline pending Starts permit anonymous joins through the existing
  ordered outbox protocol. Capacity, paused/rejected-state and busy guards remain.
- Gaming status, POS product presentation and Shift review copy are more concise.
  Checkout controls remain reachable, and explicit zero-total completions do not
  claim a payment was collected. Shift exposes saved close blockers without
  claiming complete server approval or assuming a physical drawer count.
- Android uses the approved transparent liquid-glass sidebar, cards, controls,
  dialogs and additional workspaces. One selectable 3840 × 2160 background is
  shared by Home and the other workspace pages; the original Web logo appears
  once in the shared sidebar. Login and Splash keep their approved artwork
  (1672 × 941 assets), so this is not an assertion that every asset is native 4K.
  The sidebar supports hide/reveal swipes and held-touch sliding navigation;
  successful Login offers the upward swipe into Home. Existing login methods
  and real POS catalogue data remain in use. Optional updater progress stays
  visible during the exact current download or verification; Pause/Resume remains.

## Release identity and preservation boundaries

The coordinated product version is `3.1.47`, Android installation code `55`.
Room advances to `58`. Alembic `0092` adds the protected global Android
update-policy singleton; `0093` adds immutable Gaming Void/served-addon evidence
and the nullable retained-addon bill link. The guarded transition preserves all
legacy rows, including an exact pre-column Gaming-session projection, sequences
and tablet fleet/queue evidence. Financial arithmetic and outbox protocols remain
subject to the existing regression and idempotency checks.

The repository's latest/minimum Android template defaults remain `8`, and its
compatibility-policy defaults are unchanged. Deployed required-update settings
are independent operational state. Preparing or signing this candidate does not
change them, stage an artifact, or offer an update.

Historical release freezes and compatibility helpers remain unchanged. The
Build 55 guarded installer/preservation path must be independently reviewed and
validated; this note grants no pending-work exception and does not repurpose a
previous build's signing, compatibility or deployment evidence.

The source-to-requirement map is in [Build55 acceptance](BUILD55_ACCEPTANCE.md).
The exact54/0091→55/0093 preservation and recovery procedure is documented in
[the guarded deployment runbook](BUILD55_GUARDED_DEPLOYMENT.md). These documents
record scope and gates, not a claim that deployment has completed.

## Remaining release gates

1. Validate the final exact-source coordinated identity and full CI/root contracts.
2. Obtain protected signing approval and verify the exact signed artifact identity.
3. Complete signed retained-data upgrade, interrupted/slow download, resume,
   installer, backup/restore and production-image checks for this release.
4. Apply the existing fresh shop-readiness and guarded deployment gates. Retain
   unknown pending work and never close or settle another person's work as a
   deployment shortcut.
5. Offer the verified artifact through the normal in-app update path only after
   the release gates pass. Report physical-tablet installation and acceptance
   separately from emulator results.

Focused regression sources include the Gaming duration, workspace restoration,
participant Void, addon, POS receipt and Shift close-safety tests under
`android-native/app/src/test`, `android-native/app/src/androidTest`,
`backend/tests/integration/test_gaming_participant_void.py`, and
`frontend/src/modules/gaming`. Actual run receipts and artifact hashes must be
reported for the final release source; a test's presence alone is not a pass.


## Protected Optional / Required update control

The existing Devices & Updates workspace includes a deployment-controller-only
Optional / Required control on Web and Android. It uses the same exact configured
company/user binding as release activation. This policy is global across direct
Android installations, including the controller's own app. An empty policy table
inherits the deployed environment policy and does not change an existing offer.
Required selects only the exact active artifact after public APK and live-release
verification. The owner cannot supply a download URL, checksum or signing identity.
Optional retains the deployment safety floor (54 in the current deployment); it
cannot make an older unsafe app supported. Every change uses revision compare and
swap, a reason, and an audit entry. A required artifact cannot be withdrawn or
silently replaced until the controller explicitly relaxes that policy.

Build 55 checks policy about once a second while online and in the foreground,
with a single bounded request and error backoff. This is best effort, not remote
installation: Android still asks the user to approve installation, and offline or
background devices cannot receive an immediate request. Telemetry remains on its
existing fifteen-minute cadence. No Room rows, outbox entries or rejected/ambiguous
work are erased. The highest confirmed policy revision survives relaxation and
process recreation for the same installed build, preventing late stale responses
from reinstating an old requirement.

Legacy builds below55 clear a stored Required block only after a newer definitive
Supported response. Therefore an explicit Optional choice suppresses their optional
banner while leaving the deployment floor enforced. The default inherited rollout
retains the existing optional banner. Legacy polling/foreground timing cannot be
retrofitted through a server response. Play/managed clients retain their environment
minimum and revision and never receive direct APK metadata. Policy lookup/live-runtime verification failures return503 and preserve
the tablet's previous requirement instead of falsely declaring it supported.

This source feature still requires independent authorization, concurrency, client
lifecycle and release validation before any signing or offer. Final retained-build
upgrade checks must explicitly include45,49,50 and54 to55.


## Runtime trust and image-library security correction

The runtime lock raises only certifi to 2026.10.19 and Pillow to 13.0.0; the CI
lock also raises pip to 26.2.2. Runtime source requirements declare the same
minimum security floors. Unrelated package versions and hash sets stay fixed.
These locks were regenerated with pip-tools 7.6.2 and pip 26.2.2 because the
previous pip-tools 7.5.1 is incompatible with the corrected pip API. Complete
release-file hash sets come from official PyPI metadata; selected host/Linux
wheels are separately hash checked. This is not a claim that every platform
artifact was downloaded or that host tests establish Alpine compatibility.

A fixed official pip wheel is independently size/SHA256 verified before offline
installation, including both backend stages, CI prerequisites and operations
venv reuse. Both backend stages pin Alpine's ca-certificates and
ca-certificates-bundle to 20261020-r0, rebuild the default store with Alpine's
updater, then check the actual default bundle and lazy hashed CA directory for
the advisory-specific emSign G3 root before pip networking. Other image bases,
OpenSSL/zlib fixes and host operating-system trust are unchanged.

The production-image gate runs the exact attested backend image offline and
checks the installed pip/vendor CA, application certifi, system CA package/store
identity and small PNG/JPEG/WebP codec round trips. Final Linux image checks and
full exact-lock backend regressions must pass before release. A separate image
replay regression preserves raw-upload idempotency when canonical PNG encoding
changes. No application image upload, authentication or financial logic changes.
