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

The saved-customer join UI test now establishes the real shown, settled
software-keyboard viewport before selecting a saved customer. It observes raw
keyboard removal and native window/layout completion before the original
strict focus assertion. Interactive-window observation is scoped and restored
in `finally`. The review's fixed sleep is replaced by actual confirmation
button readiness. Existing no-write-before-confirm and exact saved-identity
checks remain unchanged. Focus is neither forced nor polled to obtain a pass.

The original hosted focus failure was not causally reproduced locally. These
changes harden physical-input test preconditions; they do not establish that a
production focus defect was fixed. Diagnostic evidence and earlier failures
must remain available for review.

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
