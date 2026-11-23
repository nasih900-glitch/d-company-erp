# Build 69 / 3.1.62 candidate

Android now offers Home to signed-in Gaming & POS staff whose profile grants
Gaming or POS read access. Gaming remains their default where available.
Staff Home shows a greeting and permitted operational shortcuts without
loading executive dashboard data. Executive Home retains its existing access
rules.

The backup restore verifier accepts the two exact PostgreSQL 16 renderings
observed for the Android update policy and Gaming void receipt constraints.
The equivalence applies only to comparing the backup restore. The live
migration proof still compares the raw catalogue and existing data. The
historical Build 55 verifier remains unchanged.

The supported predecessor is the exact released Build 65 / 3.1.58. The
installer still requires Alembic 0094 on the predecessor and proves 0095 and
the exact 3.1.62 candidate runtime before opening ingress. The tablet retains
Room 58. Build 68 remains immutable. [Guarded deployment procedure](BUILD66_GUARDED_DEPLOYMENT.md) ·
[Build 68 candidate history](V3_1_61_RELEASE_CANDIDATE.md)

Canonical CI, signing, retained upgrade, device and production acceptance
remain release gates. The existing compatibility floor and offer controls
govern rollout.
