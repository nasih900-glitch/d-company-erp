# Build 67 / 3.1.60 candidate

Build 67 carries forward the attendance cards, shared clocking, and online
cashier handover described in the [Build 66 candidate notes](V3_1_59_RELEASE_CANDIDATE.md).
Build 66 was withheld before signing. The supported deployment predecessor is
the exact released Build 65 / 3.1.58, with Room 58 and Alembic 0094. Build 67
retains Room 58 and advances the backend to Alembic 0095.

On startup, the tablet waits for any in-progress read of the durable handover
journal. It then rechecks the original login, till and cache before the required
shift read and again before displaying the workspace. A retained handover,
storage failure, or changed login still holds the workspace closed. The
startup shift read replaces the server projection only after verifying its
scope and response. Local shift operations remain under the existing outbox
rules.

Focused JVM tests reproduced the startup ordering race by pausing the durable
read. They covered a valid scope, a failed read, a retained handover and a
login change during the wait. Those tests and the loopback debug build are
local evidence. Canonical CI, signing, retained signed upgrade, physical
Redmi acceptance and production acceptance remain release gates.

The existing [guarded 0094 to 0095 transition](BUILD66_GUARDED_DEPLOYMENT.md)
applies with the candidate and image attestation bound to 3.1.60. It requires
the admitted Build 65 source and all five running image identities. The
isolated PostgreSQL 16 rehearsal covered a separately restored backup and
the exact migration delta. A fresh-source restore with unreviewed catalogue
differences was refused. Rehearse the actual production backup in a separate
restore before maintenance.

Coordinate the optional Android offer with the staff privacy change because
the Build 65 staff clock screen uses a summary endpoint that the candidate
reserves for authorized Owners. Compatibility floors and the release offer
remain governed by the existing approval and rollout gates.
