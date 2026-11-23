# Build64 guarded deployment

Candidate: Android64 / 3.1.57, Room58, PostgreSQL Alembic0094. Build63 artifacts,
tags and operational evidence remain immutable. Its fresh VM security hold
must not be bypassed by reusing an earlier scanner result.

Before signing or offering this successor, require the full source/release
gates and actual final-image PostgreSQL-only proof described in
[the candidate](V3_1_57_RELEASE_CANDIDATE.md). Both image security gates and a
fresh VM scan must pass without exclusions or threshold changes. The SQLite
removal concerns only the production backend image; development tests and
Android Room retain their independent SQLite support.

Deploy only the admitted exact source archive with the guarded installer and
existing maintenance, backup, rollback, pending-data and Owner acceptance
checks. Preserve prior54 eligibility and all operational data. The three
existing firewall rules are checked idempotently, with any check error failing
closed. Do not flush, delete or rewrite chains to make validation pass.

Candidate source pins remain unfrozen until independent archive admission.
After canonical signing, separately admit the signed artifacts, original
signer, upgrade matrix, actual API35 profiles, update installation and live
offer. No source check alone authorizes deployment or claims hardware proof.
