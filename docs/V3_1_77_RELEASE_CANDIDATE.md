# Build 84 / 3.1.77 release candidate

Build 84 fixes the Android authority transition after a verified till handover.
The incoming cashier must be able to close the same shift and use attendance
without restarting the app. Signed Build 83 remains immutable and unoffered.

The installer restores the checked-in modes of the two public OpenSSL runtime
verifiers after extracting the root-private release snapshot. This allows the
hardened scanner to read both read-only bind mounts as UID 65532. The scanner
still verifies the exact locally built images with the existing thresholds.

Build 84 uses Room 58 and Alembic 0105. Build 71 remains the reviewed direct
upgrade predecessor. The existing business-quiescence, retained-work, backup,
migration, release identity and runtime parity checks still apply.

Protected signing, signed upgrade acceptance, successful deployment and an
active tablet offer require separate evidence for this exact source.
