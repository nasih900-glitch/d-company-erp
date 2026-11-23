# Build 85 / 3.1.78 release candidate

Build 85 carries the Android handover fix and guarded installer permission
correction from Build 84. Its predecessor's immutable tag, v3.1.77, stopped at
a hosted Linux repository test whose sample archive omitted the two OpenSSL
verifiers. The corrected test archives all five reviewed inputs and proves the
verifier modes, exact extracted bytes, and rejection of missing and linked
OpenSSL scripts.

The reviewed direct upgrade remains Build 71 to Build 85 with Room 58 and
Alembic 0105. The existing compatibility, business quiescence, retained work,
backup, migration, runtime security and staging checks apply. Protected signing,
signed in-place upgrade, deployment and an active tablet offer require separate
evidence for this exact source.
