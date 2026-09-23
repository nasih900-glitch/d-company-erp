# Code30.3 combined cleanup maintenance handoff

This handoff supersedes any `CODE30_3_MAINTENANCE_RUNBOOK.md` instructions
limited to the original five shifts. The reviewed cohort is now the immutable
original five plus the complete sixth-shift graph, including the additional
Gaming session observed after the earlier notes. No cleanup window may begin
until its final orders and sessions are settled, the shift is closed, and the
originating installation records a later successful zero-pending sync.

This maintenance is separate from the immutable `v3.1.30` application release.
The deployed backend and image revision remain
`ad5adfb93c3488f1f931ca27da53824aa57d3dc5`; the maintenance checkout commit
and SQL SHA-256 are independent evidence and must never replace the app
revision in a release manifest.

The entire later shift graph is not classified yet. It now includes multiple
Gaming sessions and may gain more orders, payments, or dependent rows before
closure. After every item is final, the owner must explicitly choose `delete`
or `retain` for that complete graph. Mixed trial/genuine classification inside
the same shift is outside this artifact and requires a redesign; the generator
must not split it implicitly. A candidate
manifest must then be generated deterministically from a new post-closure
custom-format backup restored into a disposable PostgreSQL 16 database. Do not
copy IDs or hashes by hand. The candidate remains unusable until the actual
classification message is preserved by hash and an operator review file binds
that classification, the canonical candidate digest, backup hash, decision,
and review timestamp. Both evidence hashes are embedded in the final manifest.
The generator connects only to `127.0.0.1`; set `PGUSER=postgres` (or the
actual disposable-cluster role) when the operating-system user is not a
PostgreSQL role.

The second maintenance window is a separate controlled outage after the exact
tagged backend is deployed. Close ingress, stop every backend writer, verify
global business quiescence, and take a new backup. The earlier installer backup
is stale once ingress has reopened. Verify the stopped backend image revision
is the tagged app SHA above. Restore the new backup, validate its SHA-256 and
migration `0082`, run the restore-only rehearsal, and preserve its output. The
rehearsal requires the clean reviewed maintenance commit, exact SQL hash,
classification and operator-review hashes, full-database digests, original 223-row hashes, the
explicit later-row target or retained allowlist, replay fences for every
deleted keyed shift, invoice counter, trigger definitions/functions, and all
foreign-key dependencies. It exercises the single SERIALIZABLE transaction,
creates one v2 receipt, restores all four guarded triggers byte-identically,
checks retained and unrelated data, then always rolls back.

The production apply runner remains unusable until the classification, tablet
evidence, final backup, clean committed maintenance source, operator review,
and restore rehearsal all exist. Rehearsal and apply include the same
hash-pinned SQL body; their wrappers differ only in database identity and
ROLLBACK versus COMMIT. The apply runner requires a confirmation bound to the
exact manifest and rehearsal-evidence hashes, canonical stopped Caddy/backend
containers in Compose project `d-company-erp`, the tagged backend image
revision, the tagged checkout at `/opt/d-company-erp`, and the root-private
frozen Compose snapshot recorded on those containers. It derives the snapshot
path from container labels and rejects operator-supplied project identity. It
also requires equality between the locked
live database fingerprint and the rehearsed fingerprint. It commits one
SERIALIZABLE transaction and one v2 receipt. The zero-v2-receipt guard makes a
second pass invalid.

Database quiescence alone does not prove tablet replay safety. The exact
installation recorded on the later shift must report a successful sync with
zero pending outbox work after the shift closes and after the final order,
payment, void, Gaming end/cancel, and POS handoff timestamp. Earlier
zero-pending observations are insufficient. Free-text quarantine assertions
are rejected; quarantine stays disabled until a separately hashed physical
retirement/reset proof format is reviewed. Keep all tablets disconnected
throughout maintenance. An in-place
APK upgrade preserves local outbox state and could otherwise recreate unsynced
customer or business rows after cleanup.

The exact tagged Code30.2 post-cleanup verifier passed its read-only check on
the current server. The installer has a guarded path for the historical stale
outbox count, so that count is not an independent pre-install blocker. This
does not waive the installer's live business-quiescence gate: the current held
order and open shift must still be resolved before installation. This
maintenance rehearsal is not deployment-readiness evidence.

After apply, keep Caddy and backend stopped while the independent read-only
postcheck proves exact
post-counts, absence of every deleted ID, byte-identical enabled triggers and
functions, unchanged retained rows and invoice counter, one valid receipt, and
the replay-fence structure for every deleted keyed shift. Never POST a retired
action to the live database as a test: run HTTP 409 replay testing only against
an isolated clone of the committed post-cleanup state with the tagged backend.
Reconcile database, Web, Android, and the owner-reviewed Sheets deletions. If
any check fails, keep the
service closed and restore the fresh backup; do not edit the receipt or run a
second cleanup.

The earlier rollback-only SQL was exercised directly on disposable PostgreSQL
16 restores. Synthetic
closed-later-shift fixtures passed both decisions with a void order and with a
paid order, payment, invoice `00071`, and delivered Sheets event. Negative
checks rejected a missing later replay fence, an omitted later payment, a
changed retained-row hash, a mismatched paid total, and a missing delivered
Sheets event. Every rehearsal rolled back, leaving zero v2 receipts, and all
four guarded triggers remained enabled. These synthetic tests are not evidence
from the final backup. The refactored shared-body rehearsal, apply wrapper, and
independent postcheck require fresh PostgreSQL 16 verification before this
revision is reviewed for operational use. The Docker runners have not been
executed in this development environment because Docker is unavailable.
