# D Company ERP 3.1.17 (code 27) release candidate

Code 27 is a narrowly scoped corrective successor to signed Code 26. Its coordinated
identity is tag `v3.1.17`, Android `versionName=3.1.17`, Android
`versionCode=27`, and database migration head `0071`.

Code 26 (`3.1.16`) was signed, so its version code and artifact must remain
immutable. Its production installer stopped during candidate-environment
preparation, before candidate image builds, maintenance, database migration,
or cutover. Code 27 corrects that defect by passing the existing production
configuration to the frozen preparation helper as the absolute
`$REPO_DIR/.env` path instead of the relative `.env` path. No Android, backend,
money, permission, or synchronization-queue behavior changes from the signed
Code 26 source are authorized. The only Web application exception is the
narrow session-renewal correction below. Code 27 requires a new protected
signing approval and new artifact; the signed Code 26 APK must not be relabelled
or reused.

## Required Web realtime expiry correction

The exact Code 27 physical/Web observation found a release-blocking natural
access-expiry gap. The backend correctly closed the authenticated WebSocket
with `4401`, but the Web client reconnected with the expired access token and
reset its delay on TCP `open` before the server's authenticated `connected`
message. The preserved raw observer report recorded 66 empty reconnect attempts
and an approximately 75-second event gap; an unrelated REST `401` was the only
operation that eventually renewed access.

The reviewed correction is limited to `frontend/src/lib/api.ts` and
`frontend/src/lib/realtime.ts` plus additive auth-lifecycle regressions. HTTP
and WebSocket expiry share one refresh promise; same-origin Web continues using
the HttpOnly cookie body/header contract, while native/cross-origin clients keep
their JSON refresh contract. A definitive refresh `401`/`403` signs out, a
transient network failure preserves credentials and reconnects with bounded
backoff, and logout/replacement-login generations abort and reject stale work.
Reconnect delay resets only after the backend sends authenticated `connected`.
No credential is added to the WebSocket URL or logs, and there is no proactive
refresh polling.

The exact-source Web gate must include lint, typecheck, the complete frontend
suite and production build. Runtime evidence must additionally cross natural
access expiry without a triggering REST request, show a single shared renewal,
resume authenticated events without a repeated-`4401` storm, preserve the
session through a temporary renewal-network failure, and prove that definitive
revocation returns to login. Existing physical and financial gates remain
required; the earlier observer failure is evidence of the defect, not approval
of the correction.

## Required development-tool security correction

The exact `6170b3253fd19fe39cc75c0dd75e92dfce7657aa` Code 27 CI run failed its
frontend audit gate before release completion because transitive development
dependency `js-yaml` 4.3.1 is affected by the reviewed high-severity
[GHSA-2883-xcg3-v3hh](https://github.com/advisories/GHSA-2883-xcg3-v3hh).
The advisory affects 4.x versions below 4.3.2 and identifies 4.3.2 as patched.
The corrective source updates only the `package-lock.json` entry to 4.3.2,
within both existing parent ranges (`^4.1.0`); it does not change frontend
application source or runtime dependencies.

The audit also reports moderate
[GHSA-82fw-gwwq-j7x9](https://github.com/vitest-dev/vitest/security/advisories/GHSA-82fw-gwwq-j7x9)
through Vitest 3.2.7. Its patched stable line requires a major Vitest upgrade to
4.1.11, so it is disclosed rather than hidden or force-upgraded in this narrow
high-severity correction. All Code 27 exact-source gates must be rerun after the
lockfile change; the failed `6170b32` evidence cannot approve the new source.

This ledger records requirements, not completed Code 27 evidence. It does not
claim that exact-source tests, builds, physical validation, signing, upgrade,
deployment, staging, installation, or a live trial have passed.

## Required exact-source phases

All evidence must bind to one clean Code 27 commit and, where applicable, the
same newly signed artifact. Code 26 evidence remains historical and may inform
the regression floor, but cannot complete a Code 27 gate.

1. **Fix and regression protection.** Review the one-line installer correction
   and narrow Web realtime-expiry repair, execute their focused regressions,
   validate coordinated release identity, and prove that the complete 491-file
   Code 25 regression surface and all non-allow-listed signed Code 26 application
   behavior remain unchanged.
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
