# v3.1.49 / Android build 56 release candidate

This is the narrow successor to signed, unoffered v3.1.48 / build55 at
`f1be9f9ed62e535cf845eb9bbf678ddfe61b0e56`. Preserve its tag, signed artifacts,
source maps and failed-deployment evidence. Do not relabel or replace them.

## Deployment checker correction

The production preservation guard stopped before promotion because generated
full-table SQL joined row strings with literal backslash-n, while the direct
Gaming projection used a newline. With multiple Gaming rows, unchanged data
therefore had different hashes. The predecessor services were restarted;
no migration or candidate application writer started.

The generated SQL now uses explicit `chr(10)`. No field is excluded, no digest
is rewritten, and no comparison is waived. A real PostgreSQL16 regression
reproduced the original failure with three Gaming sessions while the one-session
case passed. The corrected query passes both cases, exact0091→0093 migration,
finance/Audit preservation and full backup/restore. An actual same-count change
to a historical Gaming rate remains rejected. Empty-table hashing is also
checked against the projection.

## Native form verification

The first56 branch run exposed a keyboard-dependent assumption in the shared
form test: after text entry opened the IME, it expected the lower picker to
already be visible. The test now reaches fields through the existing form
scroll owner and checks visibility before pixel sampling. Its disabled-control,
contrast and callback assertions remain intact. No production form behavior is
changed. The failed run is retained; the corrected candidate requires a fresh
complete instrumentation run before signing.

## Preserved application and release boundaries

The liquid glass design, shared backgrounds, original logo, sidebar gestures,
Build54 dial, player management, void rules, billing and shift workflows carry
forward. Room remains58; Alembic remains0093. This correction adds no business
schema change and does not alter the update-policy authority or defaults.

The exact deployed predecessor remains Build54 / v3.1.46 / schema0091 at
`5ad146ae076f5178acc2c8e4cb840ecbb0d0343a`. The preservation profile retains its
historical `build55` helper names and JSON protocol. Its current candidate
version, source closure and image checks must be freshly frozen for3.1.49;
the signed55 closure is not admission for56. See
[BUILD56_GUARDED_DEPLOYMENT.md](BUILD56_GUARDED_DEPLOYMENT.md).

Every historical reviewed source map remains immutable. A separate build56
overlay accounts for this successor's reviewed changes. New source, images,
workflow provenance, signed package, registry manifest and offer fingerprint
must agree. Required source, CI, signing, retained-upgrade, guarded deployment,
Owner acceptance, staging and public-offer checks remain separate gates.

Production remains Build54 until those gates pass. This document does not assert
that56 is signed, deployed, staged or offered. The physical Redmi Pad2 remains
unavailable; emulator evidence does not certify physical performance, perfect
60fps, hardware behavior or absence of every crash or memory leak.

The existing dependency limitations remain documented with the retained55
evidence, including Moderate advisories and Redis Alpine3.21 support ending on
2026-11-01. No scanner waiver or new suppression is introduced by this fix.
