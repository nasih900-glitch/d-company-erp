# Build55 guarded deployment — exact54/0091 to55/0093

This is a preservation profile for the deployed Build54 source
`5ad146ae076f5178acc2c8e4cb840ecbb0d0343a`, version3.1.46 and schema0091.
The candidate is version3.1.47, Android code55 and schema0093. It is not a
general migration bypass. Historical release helpers remain unchanged.

The profile admits only its independently reviewed source closures, exact
PostgreSQL16 catalogue/trigger changes and nonempty migration/restore evidence.
Missing or mismatched pins refuse admission; they must not be guessed from
migration source. A local test result, signed APK, workflow approval or an earlier
read-only server snapshot does not by itself admit production deployment.

## Before maintenance

Use the normal production installer from a reviewed immutable release checkout.
The existing install lock, fresh business-readiness checks, source verification,
five release-image scans and maintenance confirmation remain mandatory. Do not
close a customer's session or shift as a release shortcut. An open shift,
unbilled game or unpaid order blocks admission.

The55 profile is selected before historical pending-work classification, even
when the reported queue count is zero. It retains every installation identity,
version, timestamp and nonzero reported count. Old37/41 work and current54 work
are opaque saved work; no telemetry row is relabelled, cleared or drained.
Preserving those records does not claim to inspect the tablet's actual queue.

The installer captures all five actual containers before maintenance: immutable
image ID, effective environment, entrypoint, command and user. It also binds the
actual Compose and operational-override files. The deployed backend starts with
the reviewed fixed Uvicorn entrypoint and an empty command; the candidate and
rollback preserve that startup behavior. No captured arbitrary command is run.
The inert existing update-message setting is retained. No seed/bootstrap writer
is introduced by this transition.

Existing environment bytes must remain identical except the coordinated
`APP_VERSION` and `APP_REVISION`. In particular, the deployed supported floor54,
latest54 and policy revision4 stay unchanged. Installing55 is separate from
activating its APK offer or changing the protected Optional/Required policy.

The backend image additionally receives the reviewed CPython trust-store
security patch, from 3.14.8 to 3.14.10. The55 gate admits only that exact version
and upstream source-checksum pair, plus the new official image's `LANG=C.UTF-8`
where the prior image had no `LANG`. Every other effective environment value is
retained. Compose cannot override `PATH`, `PYTHON_VERSION`, `PYTHON_SHA256` or
`LANG` to disguise the image. Prior-runtime and
rollback verification still require the original54 image and environment;
historical45–54 compatibility helpers are unchanged. All five candidate images
must pass fresh vulnerability scans before maintenance.

## Legitimate authenticated acceptance

Supply `--build55-acceptance-credential-file /ABSOLUTE/PRIVATE/INPUT.json` only
for this exact existing54 transition. The file must be root-owned mode0600,
regular, single-link, bounded to16KiB, outside release and rollback trees, with
root-owned non-writable nonlinked ancestry. Its strict JSON contract is:

```json
{
  "schema_version": 1,
  "access_token": "LEGITIMATE_SHORT_LIVED_NORMAL_LOGIN_ACCESS_TOKEN",
  "expected_company_id": "CANONICAL_UUID",
  "expected_user_id": "CANONICAL_UUID",
  "expected_branch_id": "CANONICAL_UUID",
  "expected_terminal_id": "CANONICAL_UUID"
}
```

These are explanatory field descriptions, not executable credentials. An
operator must obtain the token through the normal authorized login workflow.
The installer never acquires, resets, refreshes or mints credentials, and never
creates an account or bypasses authentication. No legitimate input means refusal
before maintenance.

After building/scanning and immediately before maintenance, a bounded HTTPS
probe checks normal `/auth/me`, `/gaming/stations` and open-shift reads using the
configured domain and explicit terminal scope. Account and workspace must match;
no open shift may have appeared. Redirects and environment proxies are refused.
No token enters argv, environment, logs, receipts or rollback archives. Only the
digest of descriptor-read input bytes is retained to bind the internal probe.

After preservation and internal-service checks, the same validated input bytes
go through stdin to the fixed reviewed checker in the attested backend container.
The receipt is restricted to fresh UTC identity/readiness facts. Expiry, scope
change, refusal or input replacement holds ingress closed; there is no automatic
credential rotation or authentication downgrade.

## Quiesced backup and migration

1. Stop ingress and app writers, then recheck business readiness from a new DB
   snapshot. Capture full-row hashes/counts of **every public table**, including
   complete Audit semantics, finance receipts and Alembic history, plus all
   sequence states/definitions, schema catalogue and raw fleet proof.
2. Take the final stopped dump and checksum. Restore it into an owned disposable
   database in the same PostgreSQL cluster. Prove all rows, sequences, schema and
   fleet equal the stopped source before dropping only that verification DB.
   Raw schema evidence stays intact. Restore/rollback recognizes only the five
   reviewed full-string CHECK deparser equivalences; no column, index, function
   or unrelated constraint normalization is permitted. Live post-migration
   catalogue comparison admits only the independently reviewed exact removed and
   added rows. Full raw table hashes remain in the evidence alongside the
   explicit legacy gaming-session projection described below. Baseline raw hashes and its mode0400
   checksum-manifest digest are retained by the installer process and rechecked
   at each boundary; losing or changing that seal requires manual review.
3. Re-resolve all five scanned image tags, produce a digest-only Compose override
   and prove its only config changes are the five attested image IDs. Start only
   the exact attested PostgreSQL image. Run Alembic through the exact backend
   image with an explicit Python entrypoint; do not start the normal app yet.
4. Immediately prove the exact0091→0093 transition. The three new tables
   `android_update_policy`, `gaming_void_receipts` and `gaming_void_addon_lines`
   must all be empty. Every existing sequence and fleet record must match.
   Every legacy table retains its exact full-row hash except `gaming_sessions`,
   whose new nullable UUID `retained_addon_order_id` changes its raw JSON shape.
   Retain that full raw hash too, require zero non-null links, and compare the
   projection removing only that field against the pre-migration full-row hash
   and count. No other legacy field may change.
   Schema admission is the exact independently derived removed/added catalogue
   delta, including the replaced reporting-snapshot function and removed old
   add-on-cancel function/trigger. Compare user trigger definitions and enabled
   and deferral state separately; trigger names alone are not sufficient.
   Unexpected seeding, business/Audit/finance drift or another schema change
   refuses the transition before a candidate app writer starts.
5. Start internal services with the same digest-only operational configuration.
   Create Caddy without starting it; inspect all five actual effective runtime
   environments, commands/users and images before ingress. Complete normal
   authenticated reads, then start ingress and recheck all five running images
   and actual runtime fields. Keep every evidence file private.

## Recovery boundary

Before any candidate application writer starts, failure may restore the exact
stopped54 database and prior five images, environment and reviewed operational
startup. Full-row/fleet and actual-runtime verification must pass before old
ingress reopens.

**Once any candidate application writer starts, recovery is forward-only on the
current database, even if public ingress has not opened.** Never restore the
older54 dump, run its history projection against55 data, delete a new policy row
or force telemetry to zero. Failure stops ingress and preserves current records
for a reviewed forward repair. A token expiring at internal acceptance follows
this same boundary. The exact54→55 installer is not a55→55 repair command.

Migration0092 also locks the policy table before its downgrade emptiness check
and refuses **any** policy row, including Optional: dropping its retained
revision could revive an older Required decision on installed clients.

Release signing, retained45/49/50/54 upgrades, resumable/slow downloads, optional
banner offering and physical-tablet acceptance remain separate gates. This
runbook does not assert that any of them has completed.
