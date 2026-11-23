# Build 86 / 3.1.79 release candidate

Build 86 corrects the guarded Build 71 upgrade for databases with historical
keyed Goods Receipt Notes (GRNs). Migration 0103 backfills completion proofs and
records their audit entries. The installer proves the expected source-derived
backfill while continuing to check original history and unrelated records.

Build 85 stopped on its retained-data verifier and the guarded rollback
restored Build 71. Its tag and signed artifact remain immutable.
Build 86 retains Room 58, Alembic 0105, and the optional update policy. Protected
signing, signed in-place upgrade, deployment, and an active tablet offer each
require separate evidence for this exact source.
