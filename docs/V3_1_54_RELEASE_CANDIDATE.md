# v3.1.54 / Android 61 release candidate

This successor preserves Build 60 / v3.1.53 at
`3401d1fb985f272c5cef568421cf3638c2c2e528`, including its immutable tag and
failed hosted test evidence. Do not retag it or relabel its artifacts. The
original signed Build 59 base and all earlier preservation maps remain intact.

Application behavior remains inherited, including the Owner-only staff hours,
base-pay estimate and optional overtime review described in
[the preceding candidate](V3_1_53_RELEASE_CANDIDATE.md). This change adds no
feature, payment posting, migration or Sheets integration. Room stays 58 and
Alembic stays 0094. Production Gaming focus handlers and dial mechanics are
unchanged.

The saved-customer join UI test begins with Android-dispatched finger input and
verifies native touch mode. It establishes the real shown, settled keyboard
viewport, observes raw keyboard removal and native window/layout completion,
and retains the strict unfocused, no-write-before-confirm and exact saved-ID
assertions. No downstream assertion is retried or polled to obtain a pass.

An original-APK local control reproduced the same strict focus failure seen
in hosted CI: the Login appearance fixture entered Android keyboard/non-touch
mode, but its old
Compose-level cleanup touch left that native mode enabled across activities.
The unchanged Gaming test failed in that leaked state and passed again after
an actual native touchscreen event. The Login fixture now captures and restores
its original native input mode, including a legitimate keyboard-mode start,
with checked event delivery, release, recycling and bounded native-state
verification. A separate fixed-producer/original-consumer control passed
before changing the consumer's initial finger input. Production focus handlers
and customer selection behavior remain unchanged. Retain the original failed
runs, same-APK causal controls and subsequent regression evidence. The local
control used ARM64 and separate instrumentation invocations; exact native
input mode was not recorded in the hosted run. Fresh complete hosted validation
remains required.

Complete exact-source **branch CI must pass before tagging this successor**.
Then require canonical tagged CI, original-certificate signing, byte-verified
artifacts, signed upgrade/download checks and the separate guarded production
acceptance and optional offer. Local or emulator success alone is insufficient.
The actual Redmi Pad 2 remains unavailable, so emulator results are not
hardware-performance proof. Build 61 is not yet tagged, signed, deployed,
staged or offered.

Follow [BUILD61_GUARDED_DEPLOYMENT.md](BUILD61_GUARDED_DEPLOYMENT.md). Record the
backend inventory digest, backend **wire manifest** digest and protected-source
inventory digest separately; their serializations are not interchangeable.
