# v3.1.46 / Android build 54 release candidate

Build 54 repairs the guarded installer's environment-verifier dependency path.
Build 53's guarded deployment refused before migration: its frozen Build 52
profile called environment, JSON and Docker primitives on the Build 47 module,
which does not expose them. The deployed Build 50 remained unchanged.

The new explicit Build 54 profile uses Build 50's existing hash-verified
`_load_historical(candidate_source_root)` API to reach the strict Build 45
primitives. It supplies the exact deployed 50/successor 54 release context and
retains Build 52's full effective-environment equality check. It does not reuse
the older whole-environment Python-image exceptions. Frozen Build 52/53 and all
older compatibility helpers remain byte-for-byte unchanged; no function is
monkeypatched and no gate is skipped.

The deployed predecessor is commit
`29e32b440ab8dc162b6c8f5d40f8283fad53ce04`, version 3.1.42, Build 50, Alembic 0089.
This candidate coordinates version 3.1.46, Android build 54, Room 56 and Alembic 0091.
Android/Web UI and business code match the reviewed Build 53 tree; only release
identity, installer/profile controls, tests and documentation change. No Room
or database migration is added. Existing 0089→0091 preservation/rollback proof
formats remain unchanged.

The exact 13-installation fleet and both nonzero counters stay pinned. Both
queues remain `unknown_preserved_work`: they are not inspected, drained,
rewritten, retired, or claimed replay-compatible. The profile permits only the
exact Build 50→54 identity, environment bytes, image bindings and reviewed
source. It adds no version-range or generic pending-work exception.

Production rollout policy and repository template defaults remain unchanged.
Preparing/signing54 does not offer it; repository latest/minimum defaults stay 8,
while the deployed latest 50 is an independent operational setting. Forced
updates remain disabled. Do not rewrite the immutable Build 53 tag or relabel
its signing, emulator, retained-upgrade or CI evidence as Build 54 evidence.

The complete root contract suites in CI and Release Android discover
`tests/test_build54_pending_compatibility.py`, including the real transitive
loader and strict refusal cases. Fresh backend/frontend/Android checks,
production-image migration/security gates, reproducible signing, independent
final freeze and exact-source signed artifact evidence remain mandatory.
Before production, rehearse full `pre`, `post` and `rollback` CLI invocations
with root-owned private source snapshots and exact 13-fleet/history/transition
captures in an isolated runtime. Do not mock ownership guards to claim that
rehearsal. Then obtain fresh quiescence, backup/restore, guarded installer,
runtime parity and user acceptance evidence before any optional offer.
