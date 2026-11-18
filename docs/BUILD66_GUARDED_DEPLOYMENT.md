# Build 66 guarded deployment

Build 66 / 3.1.59 is an upgrade from the exact signed Build 65 / 3.1.58
deployment, Git commit `9e31d0daaa5fbf8b46647a213f6e7beab1ca087a`.
Room stays at 58 and Alembic moves from 0094 to 0095. Retain the signed
predecessor, its source and its five admitted image identities.

1. Admit the exact candidate source and canonical CI, signed artifact,
   upgrade, security and final-image runtime evidence. Before scheduling
   maintenance, run a read-only production-source capture and verify the
   corresponding backup in a separate isolated restore. The Build 66
   restore verifier permits only the existing reviewed CHECK renderings.
   Any remaining mismatch holds the deployment.
2. Use a scheduled write outage and normal Owner browser acceptance. The
   operator supplies the existing private credential input using
   `--build66-acceptance-credential-file`. The installer verifies the current
   Owner identity and scopes, checks that business activity is quiescent and
   verifies that the exact owned ingress and writers are stopped. It then
   seals the full pre-migration evidence and verifies a separate transactional
   backup restore.
3. Run Alembic through the exact scanned candidate backend image without
   normal application startup. Before starting writers, verify the same
   database, all legacy table digests and sequences, the unchanged legacy
   shift projection, null custody fields on prior shifts, an empty new
   handover ledger and the exact additive 0095 catalogue and trigger delta.
   This includes every client-installation row and its reported pending work.
4. Start the pinned internal services using the admitted no-seed backend
   command. Verify runtime identity, both PostgreSQL driver probes, absence
   of SQLite and the exact 0095 head. Reverify the same Owner through the
   candidate before opening Caddy. Record actual post-deploy parity and
   acceptance.
5. A pre-writer failure permits an automatic restore only after verified
   containment and a valid backup. Once any candidate writer may have
   started, preserve the current database and use forward repair with
   ingress held closed. Treat an unverified shutdown as a containment failure.
   Keep the offer optional unless rollout is separately approved. Coordinate
   the staff tablet upgrade with the Owner-only summary API change.

The local PostgreSQL rehearsal used synthetic data and supplies no production
deployment or rollback verdict. The [candidate notes](V3_1_59_RELEASE_CANDIDATE.md)
describe the emulator checks and the retained startup Retry qualification.
