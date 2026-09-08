# D Company ERP 3.1.17 (code 27) release candidate

Code 27 is the deployment-only successor to signed Code 26. Its coordinated
identity is tag `v3.1.17`, Android `versionName=3.1.17`, Android
`versionCode=27`, and database migration head `0071`.

Code 26 (`3.1.16`) was signed, so its version code and artifact must remain
immutable. Its production installer stopped during candidate-environment
preparation, before candidate image builds, maintenance, database migration,
or cutover. Code 27 corrects that defect by passing the existing production
configuration to the frozen preparation helper as the absolute
`$REPO_DIR/.env` path instead of the relative `.env` path. No Android, backend,
or Web ERP feature behavior changes from the signed Code 26 source are
authorized. Code 27 requires a new protected signing approval and new artifact;
the signed Code 26 APK must not be relabelled or reused.

This ledger records requirements, not completed Code 27 evidence. It does not
claim that exact-source tests, builds, physical validation, signing, upgrade,
deployment, staging, installation, or a live trial have passed.

## Required exact-source phases

All evidence must bind to one clean Code 27 commit and, where applicable, the
same newly signed artifact. Code 26 evidence remains historical and may inform
the regression floor, but cannot complete a Code 27 gate.

1. **Fix and regression protection.** Review the one-line installer correction,
   execute its frozen-source boundary regression, validate coordinated release
   identity, and prove that the complete 491-file Code 25 regression surface
   and signed Code 26 application behavior remain unchanged.
2. **Full automated and bounded physical validation.** Run the complete backend,
   frontend, Android JVM/instrumentation, release-safety, migration, and build
   suites from the exact source. The existing 413-step, 16-session synthetic
   physical Gaming/POS/Finance lane may run in parallel with the automated lane,
   but both results must bind to that same commit and Code 27 identity.
3. **Signing, upgrade, final audit, and deployment.** After explicit signing
   approval, create and verify a new Code 27 artifact and provenance. Prove the
   same-key Code 21-to-Code 27 in-place upgrade without data or queued-work loss.
   Obtain fresh maintenance confirmation and tablet/outbox quiescence, complete
   the final source audit and production backup/restore test, then deploy and
   verify the matching backend/Web runtime and compatibility endpoints.
4. **Owner-visible inactive staging.** Register only the exact verified Code 27
   artifact as an inactive, owner-visible Web ERP record. Staging must not
   activate, advertise, or automatically install it.

The separate eight-hour whole-day requirement was cancelled. Preserved Code 26
shop-day evidence must not be reclassified as a completed Code 27 trial. After
all four phases pass, the owner may explicitly activate the exact inactive
candidate for the coordinated target-tablet installation. Activation is a
channel-wide offer, not a per-device allowlist, and Android still requires user
installation approval. The supervised real-live operational acceptance trial
then verifies authenticated health, natural token expiry, queued-work behavior,
and financial reconciliation before broader staff rollout.

Cloud physical testing does not prove Redmi Pad 2 or HyperOS behavior. The
target tablet must still prove same-key upgrade, reboot/lock-screen alarm
delivery, notification-denial recovery, and OEM battery-policy behavior.
