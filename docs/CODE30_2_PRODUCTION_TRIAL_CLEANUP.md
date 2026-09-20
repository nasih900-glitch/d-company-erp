# Code30.2 production trial-data cleanup

This is a one-time, fail-closed maintenance procedure for the exact test rows
verified in the D Company production snapshot on 20 September 2026. It does not
select rows by date, company, status, or a text label. Every deletion uses a
reviewed primary-key allowlist, and both the complete source-table fingerprint
and target-row fingerprint must match before any mutation is attempted.

The test-data classification is based on the owner's confirmation that the ERP
has had no real use since 5 September, the exact post-cutoff production rows,
the complete wipe of all 18 local AVDs, and the verified backup. The evidence
does not directly map the retained server installation UUID to any one AVD.

The procedure removes only these verified test artifacts:

- 3 shifts, including the one open test shift
- 3 void, unpaid, unissued orders and their 3 voided lines
- 5 ended or cancelled gaming sessions
- 1 unavailable `SESSION-SIMULATOR` helper item
- 10 matching start/stop idempotency receipts
- 37 matching audit rows

The exact Code30.1 test installation remains completely unchanged as historical
evidence. It still reports one stale saved action. The evidence does not link
that server installation UUID to any of the 18 wiped local AVDs, so cleanup
does not clear the counter or alter `last_seen_at`, `last_successful_sync_at`,
`updated_at`, or any other installation field. All 29 expired remote-assistance
device keys also remain unchanged. The durable fence independently prevents
the 13 exact known deleted action identities from replaying.

For the reviewed Code30.2 production cutover, `install-on-vm.sh` performs this
procedure automatically only after it accepts the exact one-time stale-outbox
bridge. After migration `0078` and tariff acceptance, the installer keeps Caddy
closed, stops the backend, creates the fresh custom-format backup, obtains the
dry-run fingerprint, applies this guarded runner, restarts the backend, and
rechecks readiness and release-image parity before it can start Caddy. A failure
in that window leaves both Caddy and the backend stopped and preserves the
database and protected backups for a guarded retry. Ordinary upgrades with a
zero outbox and fresh installs skip this one-time cleanup path. The manual
commands below remain the recovery and independent-audit reference. This
integration does not change the evidence boundary: the retained `d664...`
installation remains at `pending_outbox_count=1` and cannot be tied to any
specific wiped AVD.

Later patch installers do not treat that preserved counter as newly queued
tablet work. Before accepting it, they run a read-only database query and
require exactly one canonical cleanup receipt, the exact unchanged retained
installation-row and 29-key hashes, all 13 structurally valid replay-fence
entries, and zero surviving rows from every reviewed deletion allowlist. The
receipt must retain the original pre-cleanup fingerprint, backup and quarantine
evidence, the exact 13 action identities, deleted IDs and counts, null offline/sync markers, source/image
identity, and exact cleanup metadata. A missing, duplicate, malformed or
mismatched receipt, a changed retained row, a missing fence, a reappeared
deleted row, or any additional nonzero tablet counter blocks the installer.
This exception is read-only and does not rerun cleanup or change the historical
counter.

It does not delete payments, refunds, customers, staff, pricing, stations,
settings, inventory, accounting entries, release records, device security-key
history, other device history, or pre-cutoff business rows. It writes one
durable audit receipt containing the backup hash, separate 18-AVD quarantine
evidence JSON hash, deployed backend image identity, source commit, executor,
deleted IDs, the unchanged historical installation snapshot, the retained
29-key evidence hash, explicit null offline/sync markers, and the immutable
identity of all 13 deleted retryable actions. The backend consults that receipt
so a delayed offline retry cannot recreate a deleted gaming action or shift.

## Required order

1. Keep tablets offline, then stop Caddy first and the backend second so no new
   request can enter while the backend drains. Apply refuses a running backend.
   The SQL also takes every public table with `ACCESS EXCLUSIVE NOWAIT`; it
   refuses to wait behind any remaining database activity.
2. Upgrade the restored test database and production database to exactly
   migration `0078`. Any other revision is refused.
3. Create a fresh PostgreSQL custom-format backup while writers remain stopped.
4. Retain its absolute local path. The apply command computes its SHA-256,
   restores it to a disposable database in the same PostgreSQL container,
   verifies migration `0078`, runs the complete cleanup dry run there, compares
   its full-database fingerprint with the fresh production dry run, and drops
   the disposable database on every exit.
5. Verify the canonical tracked evidence file
   `releases/evidence/code30-2-emulator-quarantine.json` has SHA-256
   `379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8`.
   Apply computes this hash from the clean exact-source checkout; the operator
   cannot supply it. The JSON records the complete 18-AVD wipe and embeds the
   ancillary Room archive metadata. It does not prove that the retained server
   installation UUID belonged to a specific AVD.
6. Run the production dry run below. It performs all target, dependency and
   postcondition checks inside a rolled-back transaction. It does not insert an
   audit receipt or consume an audit sequence value.
7. Review the JSON result and copy its `state_fingerprint` into the apply
   command. Do not reuse a fingerprint after any database activity.
8. Apply once, while writers are still stopped. The command also requires the
   named stopped backend container and verifies that its immutable image
   revision is exactly the supplied source commit before any cleanup SQL runs.
   It verifies that PostgreSQL is the `postgres` service and the stopped
   backend is the `backend` service in the same Docker Compose project, then
   refuses if any backend service container in that project is still running.
   A second application refuses because the original fingerprints and counts
   no longer exist.
9. Verify the committed counts, then start the backend first. Wait for its
   health check before starting Caddy. Keep tablets offline until the final
   server reconciliation is complete.

Quiesce the services before the backup and apply:

```bash
docker stop d-company-erp-caddy-1
docker stop d-company-erp-backend-1
```

Apply must be run from the clean deployed Git checkout at the exact
`--source-git-sha`. The runner rejects a dirty checkout, an untracked or
symlinked runner/SQL/evidence file, or bytes that differ from that commit. It
hashes the canonical evidence file itself and can inspect the stopped backend
container's immutable image without starting it. An unrelated stopped
container made from the same image cannot satisfy the Compose-project check.

Dry run:

```bash
infra/scripts/cleanup-code30-production-trial-data.sh \
  --postgres-container d-company-erp-postgres-1
```

Apply after the backup restore and dry run have passed:

```bash
infra/scripts/cleanup-code30-production-trial-data.sh \
  --postgres-container d-company-erp-postgres-1 \
  --backend-container d-company-erp-backend-1 \
  --apply \
  --confirm APPLY_CODE30_1_VERIFIED_TRIAL_CLEANUP \
  --expected-state-fingerprint '<fresh dry-run SHA-256>' \
  --backup-file '/absolute/path/to/fresh-code30-backup.dump' \
  --source-git-sha '<deployed 40-character Git commit>' \
  --executor '<named operator>'
```

The expected committed state is 7 closed shifts, 4 completed gaming sessions,
4 closed orders with 4 lines, 7 retained installation heartbeats, 379 retained
remote-assistance device keys, 3 payments, no refunds, no customers, no open or
held orders, no active or paused sessions, no open shift, one unchanged stale
historical saved-action report on the retained installation, and no unresolved
Google Sheets delivery. All 18 local AVDs remain wiped, and the 13 exact known
deleted action identities remain permanently fenced. The state fingerprint
covers the row count and full-row SHA-256 of every public table. All public
tables outside the seven explicitly changed tables must have the same row count
and full-row SHA-256 before and after the transaction. Every retained row in
those seven tables must also have the same SHA-256. The retained installation
and 29 expired remote-assistance keys must retain their exact reviewed SHA-256.

Restart only after verification:

```bash
docker start d-company-erp-backend-1
# Wait until the backend health check is healthy.
docker start d-company-erp-caddy-1
```

If any check fails, PostgreSQL aborts the transaction. Do not weaken a hash,
remove an ID, disable a trigger, change an expected count, or widen a delete to
make it pass. Re-run the read-only production audit and review the new evidence.
