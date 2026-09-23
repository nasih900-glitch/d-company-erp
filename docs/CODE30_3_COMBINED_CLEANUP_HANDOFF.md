# Code30.3 combined cleanup maintenance handoff

This maintenance is separate from the immutable `v3.1.30` application release.
The deployed backend and image revision remain
`ad5adfb93c3488f1f931ca27da53824aa57d3dc5`; the maintenance checkout commit
and SQL SHA-256 are independent evidence and must never replace the app
revision in a release manifest.

The later shift and Gaming session are not classified yet. After that work is
closed, the owner must explicitly choose `delete` or `retain`. A candidate
manifest must then be generated deterministically from a new post-closure
custom-format backup restored into a disposable PostgreSQL 16 database. Do not
copy IDs or hashes by hand. The candidate remains unusable until the actual
classification message is preserved by hash and an operator review file binds
that classification, the canonical candidate digest, backup hash, decision,
and review timestamp. Both evidence hashes are embedded in the final manifest.

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

There is intentionally no production apply command in this branch while the
classification and final backup are unknown. The eventual apply artifact must
use the same reviewed transaction body and add all of these gates: an explicit
one-use confirmation, production database/container identity, stopped ingress
and backend services, tagged backend image revision, clean maintenance commit,
exact SQL/manifest/classification/review/backup hashes, and an actual pre-state fingerprint
equal to the rehearsed canonical fingerprint. It must commit exactly one
transaction and one v2 receipt; any failed assertion must roll back. The
existing zero-v2-receipt guard means two cleanup passes are invalid.

Database quiescence alone does not prove tablet replay safety. The current
installation must report a successful sync with zero pending outbox work after
the held order is finally resolved and the shift is closed, or that
installation must be explicitly quarantined and retired. The earlier
zero-pending observation occurred before those two business actions and is not
sufficient. Keep all tablets disconnected throughout maintenance. An in-place
APK upgrade preserves local outbox state and could otherwise recreate unsynced
customer or business rows after cleanup.

The exact tagged Code30.2 post-cleanup verifier passed its read-only check on
the current server. The installer has a guarded path for the historical stale
outbox count, so that count is not an independent pre-install blocker. This
does not waive the installer's live business-quiescence gate: the current held
order and open shift must still be resolved before installation. This
maintenance rehearsal is not deployment-readiness evidence.

After apply, keep ingress closed while an independent verifier proves exact
post-counts, absence of every deleted ID, byte-identical enabled triggers and
functions, unchanged retained rows and invoice counter, one valid receipt, and
HTTP 409 replay refusal for each deleted keyed shift. Reconcile database, Web,
Android, and the owner-reviewed Sheets deletions. If any check fails, keep the
service closed and restore the fresh backup; do not edit the receipt or run a
second cleanup.

The rollback SQL at SHA-256
`9292f6478c16ce35614053e1d73c51d435d45ee52b71d7eacf2c78a40634df45`
has been exercised directly on disposable PostgreSQL 16 restores. Synthetic
closed-later-shift fixtures passed both decisions with a void order and with a
paid order, payment, invoice `00071`, and delivered Sheets event. Negative
checks rejected a missing later replay fence, an omitted later payment, a
changed retained-row hash, a mismatched paid total, and a missing delivered
Sheets event. Every rehearsal rolled back, leaving zero v2 receipts, and all
four guarded triggers remained enabled. These synthetic tests are not evidence
from the final backup. The Docker restore runner has not been executed in this
development environment because Docker is unavailable; it still requires a
containerized rehearsal before any apply artifact is accepted.
