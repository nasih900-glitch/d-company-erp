# Semantic Audit API (build 51)

This is an additive protected-owner read contract. The original
`GET /api/v1/admin/audit` still returns a list with its original fields and
optional semantic fields. No historical Audit row is rewritten.

All catalogue, event, legacy-list and facet reads require `admin.audit.read`
and a valid current-user/company-bound `X-Audit-Token` obtained through the
existing password recheck. A page cursor grants no authorization. The scope
is explicitly **company-wide protected owner**; it does not promise branch
isolation. The company/selected-branch timezone supplies labelled event dates.

## Catalogue

`GET /api/v1/admin/audit/catalogue` returns `version`, `actions`, `categories`,
`outcomes` and `views`. Each of the 27 actions has a stable `code`, human label,
category, aliases and permitted stages/outcomes. Codes classify actual supported
operations, not transaction identities. There is no unsupported stock-transfer
or picking code. Future finance corrections use `POS-PCM` and `FIN-REC` only
when their authoritative source operation exists.

## Events and filters

`GET /api/v1/admin/audit/events` accepts:

- `view=business` (default) or `all`. Business includes operational domains and
  meaningful sign-in/recovery/access evidence, while routine connection-key
  activity remains accessible under All / System. Unknown records are retained.
- Exact `code`, `category`, `outcome`, `actor_user_id`, `target_user_id`,
  `reference`, `entity_type`, `entity_id`, `action`, `terminal_id`, `request_id`.
- `date_from` / `date_to`: inclusive **server-recorded event shop dates**, not
  sale business dates, device-reported timestamps or a browser-local date.
- `q`: bounded escaped text/reference/current-staff-label search and catalogue
  label/alias matches. An exact known code token is treated as a code filter.
  Broad matches are labelled as such. Failed-login targets are never joined
  into staff-actor-name matches; target identity remains separately searchable.
- `limit` (1–200, default 50), then opaque `cursor` with unchanged filters.

Unknown/duplicate query parameters, unknown codes/categories/outcomes, invalid
date ranges and changed/invalid/expired cursors are rejected explicitly.

Response: `entries`, `next_cursor`, `has_more`, `snapshot_max_id`, `loaded_count`
(current page), `matching_count` (captured snapshot), `new_entries_available`,
`shop_timezone`, `date_filter_basis`, `count_basis`, `scope`,
`catalogue_version`, `snapshot_expires_in`.

Counts are Audit entries, **not revenue or unique business operations**. Payment
legs and batch movements remain distinct evidence. Related raw CRUD/journal
rows must not be added together as additional collections.

### True visibility snapshots

The initial query materializes matching visible IDs in one SQL statement.
Only these IDs enter a private tenant/user-scoped Redis snapshot, with a
10-minute TTL, 50,000-entry cap and eight recent snapshots per owner. No Audit
payload, password or raw filter text is cached. A signed cursor binds snapshot
identity, user, tenant, normalized filters, limit, shop timezone and branch
context. Changed filters require a new first page. Excessive matches require
narrower filters; expired/evicted snapshots and Redis outages give explicit
retry/refresh errors and never fall back silently to moving history.

An out-of-order lower-sequence-ID commit cannot appear halfway through an old
snapshot or disappear from its count. `new_entries_available` tells clients to
preserve loaded history and offer a refresh. Refresh creates a new snapshot.
This is stronger than using `id <= initial_max_id` alone.

## Entry semantics

Original raw fields remain, with `action_code`, `action_label`, `category`,
`stage`, `outcome`, `classification_source`, `catalogue_version`, `actor_kind`,
`target_user_id`, `source_reference`, `operation_id`, `summary`,
`business_details`, `actor_label_source`, `match_explanation`.

- `recorded`: semantic metadata captured on a forward write.
- `derived_legacy`: a deterministic code/stage derived from sufficient retained
  snapshots/explicit action names. Never consult today's mutable source row to
  invent yesterday's values. Missing evidence stays unclassified/unknown.
- `actor_kind=authenticated`: authenticated endpoint context or successful
  password authentication. `recorded_actor`/`requester_recorded` are not current
  bearer proof. Public OTP approval uses `otp_approved_requester` or
  `otp_approved_unknown`; it never claims the requester submitted that public
  confirmation while authenticated.
- Failed-login actor is unauthenticated; the attempted account is
  `target_user_id`. A legacy stored target ID is retained in the database but
  projected as a target, not an authenticated initiator.
- Joined actor names/emails are explicitly current account profile labels.
- `client_reported_at`, offline headers and receive-time `synced_at` remain
  reported provenance, not independently proven offline occurrence, commit,
  provider payout or device identity. `created_at` is server-recorded time.
- Refund request, awaiting payout, value captured before accounting
  finalization, withdrawal, reversal and finalized physical settlement are
  separate event-time stages. Incomplete older refund evidence is recorded /
  unknown. QR/wallet POS rails and membership rails retain their own rules.
  A historical pending stage is not the current unresolved obligation count.
- Stock entries snapshot available ingredient name/unit and actual retained
  quantity history at the source write. Missing historical balances are not
  guessed from today's stock. Nested structures remain bounded typed JSON;
  credential fields are redacted, while safe business codes remain readable.

## Write and database integrity

Business-operation events are added to the same transaction as the source
mutation; normal idempotent replay returns the original result without another
operation event. ORM INSERT snapshots are captured after final source IDs and
server defaults exist, then inserted on that same transaction's connection.
Updates/deletes retain their pre-flush evidence. Bulk staff-role replacement
has an explicit prior/new-role and branch-scope event.

Migration `0090` adds nullable metadata/indexes and PostgreSQL guards against
Audit UPDATE, DELETE and TRUNCATE. Actor/terminal UUIDs are retained without
mutable-parent foreign keys, so parent lifecycle cannot erase their identities
or introduce a reverse FK lock against operational Shift/Terminal locks.
Current-label joins remain tenant-scoped; normal staff soft deletion and
terminal archival are unchanged. A database owner/superuser can disable
triggers: this is application/database enforcement, not external WORM storage.

Downgrade refuses to drop semantic columns after evidence has been recorded.
An older application can retain the additive schema; an operational rollback
must preserve these facts rather than deleting them to reach an old head.
