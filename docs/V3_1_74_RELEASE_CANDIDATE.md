# Build 81 / 3.1.74 release candidate

This is the reviewed, unshipped successor candidate to Build 80. No Build 81
tag, pipeline, signed Android artifact, production deployment or tablet update
offer is established by this note; those remain separate protected gates.

Build 80 / v3.1.73 at `66317203a6db380962824393293e21d4d59ed6a1`
and all earlier source/tags/assets and 53 historical literal maps remain
immutable. A separate Build 81 overlay binds the exact successor delta.

The candidate retains the two scoped instrumentation-harness synchronization
corrections: password visibility checks read fresh semantics after the Hide
control is visible, and the saved-customer join test obtains the exact matched
node through a synchronized collection before reading its root and geometry on
the UI thread. Existing value, identity, touch, focus and no-write-before-confirm
assertions remain. These test changes do not change the product APK's behavior.
The remaining changes coordinate the exact 81 / 3.1.74 release identity and
fail-closed admission; the candidate source cannot stage its predecessor.

Room remains 58 and Alembic remains 0105. PostgreSQL stays at its pinned official
16.16 image, including its same-volume predecessor/rollback and fresh-restore
proof requirements. The four OpenSSL-consuming runtime images install a pair of
locally built, signed Alpine APKs from the verified OpenSSL 3.5.9 source plus the
reviewed Alpine and DANE backport series. The producer is built once per trust
domain and passed to those image builds through a read-only named context; its
ephemeral signing key is used only to authenticate that candidate package pair.

The scanner binds the signed package archives, producer provenance, installed
ELF hashes and Syft package projection to each exact image. Its generated VEX
covers only the exact raw findings observed for that service: nine paired CVEs
for Caddy and five paired CVEs for backend, frontend and PostgreSQL. Unknown or
unrelated High/Critical findings still fail, the same Grype database and exact
raw-to-final record multiset remain mandatory, and backend's independent
`_hashlib` proof is unchanged. Redis does not consume the custom package.
Scanner thresholds, all historical OpenSSL review profiles, and unrelated
tablet, role, payment, offline and UI behavior remain unchanged.

The existing calendar and selected-tablet fresh-sync/normal-close admission is
unchanged. A normal close proves its exact shift only. Compatible replay,
retained queues, optional updates, signed in-place upgrade preservation, exact
runtime/Redis retention, repeated running/stopped checks and idle-shop admission
remain mandatory.

The protected exact-source workflow remains authoritative before signing or
offering; a reviewed tag may start while branch CI is in progress. Full root,
backend/frontend, Android instrumentation/unit/lint, both image-store security
checks and independent reproduction must pass. Actual signed upgrade and
production/tablet admission remain separate requirements. Existing TCL evidence
does not establish newly signed Build 81 or Redmi acceptance, and no zero-stutter
claim is made.
