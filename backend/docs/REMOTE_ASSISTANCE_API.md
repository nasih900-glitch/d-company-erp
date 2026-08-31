# Remote Assistance API and Threat Model

Status: backend contract for Code 17. The authoritative prefix is
`/api/v1/remote-assistance`.

## Security boundary

Remote assistance is a first-party D Company ERP support channel. It is not a
general remote-desktop feature.

- A registered Android `ClientInstallation` remains the tenant-scoped device
  identity. Its public `installation_id` is an opaque UUIDv4. A separate
  `RemoteAssistanceDeviceKey` proves possession of a P-256 private key held in
  Android Keystore; the server stores only canonical public SPKI bytes and a
  SHA-256 fingerprint, never a private key.
- Owner operations require `admin.system`, which is restricted by the existing
  protected-owner permission boundary.
- Enrollment requires the normal bearer-authenticated Android user currently
  attributed to the installation plus P-256 proof of possession. Enrollment
  creates a short-lived `pending` key only; it cannot activate or replace a key.
- The protected owner must enter the 12-character code displayed on the
  physical tablet to approve a pending key. The server derives this code with a
  dedicated secret-keyed HMAC. Owner list/admin APIs never return the code,
  pending public key, or pending full fingerprint.
- After enrollment, device operations require both the normal bearer identity
  and a tenant/installation-bound signed request from the active key. A claimed
  Android platform header alone is never accepted as device proof. The signed
  status endpoint is the narrow exception: pending, active, revoked, and
  expired keys may query their own status so Android can recover safely.
- A user decision is required before any session starts. An `anytime` grant may
  authorize later sequential sessions until its own expiry or revocation, but
  every session is independently capped at 15 minutes.
- Commands are a closed semantic set. There are no tap coordinates, keystrokes,
  selectors, URLs, scripts, shell operations, arbitrary JSON arguments, payment
  operations, refunds, ledger operations, or other financial mutations.
- `navigate` is Help-only. Dashboard, POS, Gaming, Shift, Finance, and every
  transaction workflow are outside the Code 17 remote-action boundary.
- The only image is the latest sanitized JPEG in Redis. No frame bytes, object
  key, screenshot path, or historical frame metadata are persisted in the
  database or durable queue.
- The sanitized JPEG is sealed with AES-256-GCM before Redis storage. Versioned
  authenticated metadata binds company, session, frame ID, sequence, geometry,
  receipt time, and TTL. Invalid ciphertext or metadata fails closed and is
  deleted; Redis never contains plaintext JPEG bytes.
- Redis is an availability, privacy, and replay boundary. Signed device requests
  fail closed if the nonce cannot be claimed with `SET NX`; starting a session,
  issuing a command, polling an active device session, uploading a frame, or
  viewing a frame also fails closed when the relay is unavailable.
- Production Redis is isolated on the internal backend-data network, disables
  its default user, and gives the backend ACL user only the commands and
  `dcompany:*` keyspace needed by application Lua/rate-limit/relay operations.

The server can verify authenticated first-party provenance, the affirmative
privacy-pipeline header, JPEG format, geometry, size, sequence, and lifecycle.
It cannot semantically inspect every pixel and prove that Android redaction was
correct. Android must capture only its own ERP window, visibly show sharing,
redact protected surfaces before upload, and stop locally immediately when the
user presses Stop. Physical-device validation remains a release gate.

## Heartbeat and online semantics

Android sends `POST /device/heartbeat` every 20 seconds while its remote-support
coordinator is in foreground scope. The default server online window is 45
seconds, which covers more than two normal heartbeat intervals plus jitter.
`GET /devices` returns the effective value as `online_within_seconds`.

`is_remote_online` means the authenticated heartbeat is recent. It is
independent of `sharing_capability`:

- `permission_required`: online and eligible to receive a consent request, but
  a session cannot start yet.
- `available`: online and permission-ready; a granted session may start.
- `unsupported`: not eligible for a request.

A new assistance request rejects a stale heartbeat. Exact idempotent replay of
an already-created request still returns its immutable result.

## Owner endpoints

All endpoints in this table require `admin.system`.

| Method and path | Request | Response |
|---|---|---|
| `GET /devices` | none | `DeviceList` |
| `POST /device-keys/{key_id}/approve` | `DeviceKeyApproveWrite` | `DeviceKeyAdminRead` |
| `POST /device-keys/{key_id}/revoke` | `DeviceKeyRevokeWrite` | `DeviceKeyAdminRead` |
| `POST /requests` | `RemoteRequestCreate` | `RemoteRequestRead` |
| `POST /sessions` | `SessionCreate` | `SessionRead` |
| `POST /sessions/{session_id}/start` | `SessionStart` | `SessionRead` |
| `GET /sessions` | optional filters below | `SessionPage` |
| `POST /grants/{grant_id}/revoke` | `OwnerGrantRevokeWrite` | `GrantRead` directly |
| `POST /sessions/{session_id}/end` | `OwnerEndWrite` | `SessionRead` |
| `POST /sessions/{session_id}/commands` | `CommandCreate` | `CommandRead` |
| `GET /sessions/{session_id}/commands/{command_id}` | none | `CommandRead` |
| `GET /sessions/{session_id}/frame` | none | JPEG bytes and frame headers |

`GET /sessions` accepts `installation_id`, `status`, `limit` (1-100, default
50), and `offset` (0-100000, default 0). Its response is:

```json
{
  "total": 1,
  "limit": 50,
  "offset": 0,
  "items": []
}
```

After a command is queued, the owner client bounded-polls
`GET /sessions/{session_id}/commands/{command_id}` until status is
`acknowledged` or `rejected`. The lookup is company/session scoped and uses
`Cache-Control: private, no-store`; a 200 response from the POST means queued,
not executed.

## Device endpoints

All device endpoints require the normal bearer access token,
`X-Client-Platform: android`, the existing native version headers, same-company
installation scope, and the current installation user. Except for enrollment,
they also require the signed-request headers documented below. Enrollment uses
its body-contained proof of possession. All operational endpoints require an
`active` key; status permits the same key in any lifecycle state.

| Method and path | Request | Response |
|---|---|---|
| `POST /device/keys/enroll` | `DeviceKeyEnrollmentWrite` | `DeviceKeyStatusRead` |
| `GET /device/keys/{key_id}/status?installation_id=...` | signed empty body | `DeviceKeyStatusRead` |
| `POST /device/heartbeat` | `DeviceHeartbeatWrite` | `DeviceHeartbeatRead` |
| `GET /device/state` | `installation_id`, optional `after_sequence` | `DeviceStateRead` |
| `POST /device/grants/{grant_id}/decision` | `GrantDecisionWrite` | `GrantRead` |
| `POST /device/grants/{grant_id}/revoke` | `DeviceGrantRevokeWrite` | `GrantRead` |
| `POST /device/sessions/{session_id}/end` | `DeviceSessionEndWrite` | `SessionRead` |
| `POST /device/commands/{command_id}/result` | `CommandResultWrite` | `CommandRead` |
| `PUT /device/sessions/{session_id}/frame` | raw JPEG and required headers | `FrameAcceptedRead` |

`GET /device/state?installation_id=<uuid>&after_sequence=0` is the authoritative
recovery poll. It returns pending grant requests, the latest session including
terminal state, and pending commands after the supplied sequence. Realtime
resource `remote_assistance` is only an invalidation accelerator; bounded polling
must remain enabled.

## Device-key enrollment, pairing, and request signing

`company_id` is never supplied by Android. It comes from the validated bearer
claims and is included by both parties in the enrollment statement. Enrollment
accepts this exact JSON shape:

```text
DeviceKeyEnrollmentWrite
  key_id: UUIDv4
  enrollment_id: UUIDv4
  installation_id: UUIDv4
  public_key_spki: unpadded base64url of canonical DER P-256 SPKI
  signed_at_epoch_seconds: canonical Unix epoch integer
  nonce: UUIDv4
  signature: unpadded base64url of DER ECDSA-P256/SHA-256 signature
```

The enrollment signature covers these ASCII bytes with LF separators and no
trailing LF:

```text
D-COMPANY-ERP-REMOTE-ENROLLMENT-V1
{company_id canonical lowercase UUID}
{installation_id canonical lowercase UUID}
{key_id canonical lowercase UUID}
{enrollment_id canonical lowercase UUID}
{signed_at_epoch_seconds canonical decimal}
{nonce canonical lowercase UUID}
{sha256 of DER SPKI as 64 lowercase hexadecimal digits}
```

The enrollment response is `DeviceKeyStatusRead`:

```text
server_time: datetime
key_id: UUID
installation_id: UUID
status: pending | active | revoked | expired
fingerprint_sha256: 64 lowercase hexadecimal digits
enrolled_at: datetime
pending_expires_at: datetime
approved_at: datetime | null
revoked_at: datetime | null
pairing_code: 12-character Crockford Base32 | null
```

`pairing_code` is returned only to a correctly signed pending-key enrollment or
status request. It uses the alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ` and is
a 60-bit HMAC-SHA-256 derivation over a domain plus company, installation,
key id, and the full SPKI fingerprint. The owner enters the tablet-displayed
code through:

```text
POST /device-keys/{key_id}/approve
  approval_id: UUIDv4
  pairing_code: 12 Crockford characters

POST /device-keys/{key_id}/revoke
  revocation_id: UUIDv4
```

Pairing input is canonicalized by uppercasing and removing spaces/hyphens, then
validated against the 12-character alphabet. A wrong well-formed code returns
`403 remote_pairing_code_mismatch`; missing `admin.system` returns
`403 forbidden`; malformed input returns 422; an expired/non-pending key
returns `409 conflict`; cross-company keys are hidden with 404. Approval and
revocation return `DeviceKeyAdminRead` directly:

```text
key_id, installation_id, status, fingerprint_sha256,
enrolled_by_user_id, enrolled_by_name, enrolled_at, pending_expires_at,
approved_by_user_id, approved_by_name, approved_at,
revoked_by_user_id, revoked_by_name, revoked_at
```

`fingerprint_sha256` is null in an owner response while the key is pending. It
may be returned after approval as audit metadata. Approval atomically revokes
the previous active key using a distinct server-generated revocation action and
terminates open grants/sessions. Explicit active-key revocation does the same.

Every post-enrollment device request carries:

```text
X-ERP-Device-Key-Id: canonical UUIDv4
X-ERP-Device-Timestamp: canonical Unix epoch seconds
X-ERP-Device-Nonce: canonical UUIDv4
X-ERP-Content-SHA256: SHA-256 of exact raw body bytes, lowercase hex
X-ERP-Device-Signature: DER ECDSA-P256/SHA-256, unpadded base64url
```

The signature covers these exact bytes, again LF-separated with no trailing LF:

```text
D-COMPANY-ERP-REMOTE-REQUEST-V1
{UPPERCASE HTTP method}
{exact ASGI raw path and, when present, ? plus untouched raw query}
{content hash}
{timestamp}
{nonce}
{key id}
```

The raw target includes the mounted prefix, for example
`/api/v1/remote-assistance/device/state?installation_id=<uuid>&after_sequence=0`.
Query order and encoding are signed exactly; Android must sign the same target
it sends. GET uses SHA-256 of zero bytes. JSON uses the exact serialized UTF-8
body bytes. Frame upload uses SHA-256 of the raw JPEG bytes, not multipart data.

The default clock-skew window is 90 seconds. Each nonce is claimed once in
Redis for 240 seconds and replay returns 401; Redis failure returns 503. An
idempotent mutation retry retains its original business action UUID but must use
a fresh signed nonce, timestamp, and signature. Pending status uses realtime
invalidation plus one initial recovery poll after 5 seconds and bounded 15-second
polling thereafter; the separate heartbeat cadence is 20 seconds.
`pending_expires_at` and `server_time` are authoritative. A revoked/expired key
may continue signing only its own status request. Android should refresh the
bearer before treating a failed status request as key-state evidence.

## Write DTOs

All mutation/action identifiers and installation identifiers are random UUIDv4.
Extra JSON fields are rejected.

```text
DeviceHeartbeatWrite
  installation_id: UUIDv4
  protocol_version: integer 1..10
  sharing_capability: available | permission_required | unsupported

RemoteRequestCreate
  request_id: UUIDv4                 # also becomes grant.id
  installation_id: UUIDv4
  grant_kind: one_time | anytime
  grant_ttl_seconds: integer
  session_ttl_seconds: integer

SessionCreate
  session_id: UUIDv4
  installation_id: UUIDv4
  grant_id: UUIDv4
  session_ttl_seconds: integer

SessionStart
  start_id: UUIDv4

OwnerGrantRevokeWrite
  revoke_id: UUIDv4

OwnerEndWrite
  end_id: UUIDv4

GrantDecisionWrite
  installation_id: UUIDv4
  decision: accepted | declined
  decision_id: UUIDv4

DeviceGrantRevokeWrite
  installation_id: UUIDv4
  revocation_id: UUIDv4

DeviceSessionEndWrite
  installation_id: UUIDv4
  end_id: UUIDv4
  reason: user_ended | permission_revoked | capture_stopped | app_backgrounded

CommandCreate
  command_id: UUIDv4
  sequence: integer 1..100
  type: navigate | refresh | collect_diagnostics
  module: help                              # navigate only

CommandResultWrite
  installation_id: UUIDv4
  sequence: integer 1..100
  outcome: acknowledged | rejected
  reason_code: null for acknowledged; required for rejected
```

Rejection reason codes are `unsupported_command`, `module_unavailable`,
`permission_denied`, `not_in_foreground`, `session_inactive`,
`execution_failed`, and `session_ended`.

`collect_diagnostics` does not accept or return an arbitrary diagnostic blob.
It is an instruction to collect the existing sanitized ERP diagnostics; durable
diagnostic ingestion remains under the separate `/client-diagnostics` privacy
contract.

## Read DTOs

```text
DeviceList
  server_time: datetime
  online_within_seconds: integer
  total: integer
  items: DeviceRead[]

DeviceRead
  installation_id: UUID
  terminal_id: UUID | null
  terminal_name: string | null
  version_name: string
  version_code: integer
  last_user_id: UUID | null
  last_user_name: string | null
  last_seen_at: datetime
  remote_support_last_seen_at: datetime | null
  is_remote_online: boolean
  protocol_version: integer | null
  sharing_capability: available | permission_required | unsupported | null
  device_key_id: UUID | null
  device_key_status: pending | active | revoked | expired | null
  device_key_fingerprint_sha256: string | null
  device_key_approved_at: datetime | null
  pending_device_key_id: UUID | null
  pending_device_key_enrolled_by_user_id: UUID | null
  pending_device_key_enrolled_by_name: string | null
  pending_device_key_enrolled_at: datetime | null
  pending_device_key_expires_at: datetime | null
  pairing_required: boolean
  grant_status: GrantStatus | null
  current_grant_id: UUID | null
  current_grant_kind: one_time | anytime | null
  current_grant_expires_at: datetime | null
  current_grant_responded_by_user_id: UUID | null
  current_grant_responded_by_name: string | null
  current_grant_responded_at: datetime | null
  session_status: SessionStatus | null
  current_session_id: UUID | null
  current_session_expires_at: datetime | null
  current_session_next_sequence: integer | null

GrantRead
  id: UUID
  installation_id: UUID
  kind: one_time | anytime
  status: GrantStatus
  requested_by_user_id: UUID
  requested_by_name: string | null
  requested_for_user_id: UUID
  requested_for_name: string | null
  responded_by_user_id: UUID | null
  responded_by_name: string | null
  requested_at: datetime
  expires_at: datetime
  responded_at: datetime | null
  revoked_at: datetime | null
  consumed_at: datetime | null

SessionRead
  id: UUID
  installation_id: UUID
  grant_id: UUID
  status: SessionStatus
  duration_seconds: integer
  requested_by_user_id: UUID
  requested_by_name: string | null
  started_by_user_id: UUID | null
  started_by_name: string | null
  ended_by_user_id: UUID | null
  ended_by_name: string | null
  requested_at: datetime
  request_expires_at: datetime
  started_at: datetime | null
  expires_at: datetime | null
  ended_at: datetime | null
  end_reason: string | null
  next_sequence: integer

CommandRead
  command_id: UUID
  session_id: UUID
  sequence: integer
  type: CommandType
  module: NavigationModule | null
  status: pending | acknowledged | rejected
  issued_by_user_id: UUID
  issued_at: datetime
  resolved_by_user_id: UUID | null
  resolved_at: datetime | null
  rejection_reason_code: string | null

DeviceStateRead
  server_time: datetime
  pending_grants: GrantRead[]
  session: SessionRead | null
  commands: CommandRead[]

RemoteRequestRead
  grant: GrantRead
  session: SessionRead

FrameAcceptedRead
  frame_id: UUID
  sequence: integer
  width: integer
  height: integer
  received_at: datetime
  expires_at: datetime
```

Grant statuses are `requested`, `active`, `declined`, `revoked`, `expired`, and
`consumed`. Session statuses are `requested`, `active`, `ended`, and `expired`.
Session end reasons currently include `owner_ended`, `user_ended`,
`permission_revoked`, `capture_stopped`, `app_backgrounded`, `grant_revoked`,
and `grant_declined`.

`current_grant_*` is present for the authoritative latest `requested`, `active`,
or `consumed` grant. The three `current_grant_responded_*` fields are the
authoritative consent evidence and remain null while unanswered; callers must
never substitute `last_user_*`. `current_session_*` is present only for a
`requested` or `active` session. The separate status fields preserve the latest
terminal state.

The primary `device_key_*` fields describe the active key when one exists;
otherwise they describe the latest pending/terminal key. Pending fingerprint is
always null in this owner DTO. The separate `pending_device_key_*` fields allow
replacement pairing while an older active key remains authoritative.

## Consent and session lifecycle

```text
grant:   requested -> active -> consumed   # one_time when session starts
                     |       -> revoked
                     -> revoked | expired
         requested -> declined | revoked | expired

session: requested -> active -> ended | expired
         requested -> ended | expired

command: pending -> acknowledged | rejected
```

An owner revoke is distinct from ending one session. `POST /grants/{id}/revoke`
is idempotent, records only the revocation actor/evidence, and atomically ends
the grant's requested or active session. Revoking an unanswered request does
not falsely populate device-response evidence.

`requested_for_user_id` is immutable consent targeting captured from the
installation's current user when the owner requests assistance. A decision is
accepted only when bearer user, installation `last_user_id`, and this target
are identical. An account handoff expires an unanswered request, revokes active
authority, ends its open session, rejects pending commands, and removes the
frame. The new user never sees the old user's prompt; the owner must issue a
fresh request. `SessionRead` binds through `grant_id` and does not duplicate
this field.

Owner Stop is authoritative through `POST /sessions/{id}/end`; there is no
duplicate queued `end_session` command that would become unreachable after the
state transition. Android observes the ended lifecycle through realtime/poll
recovery and stops. The user Stop action still stops capture locally first and
retries its same `end_id` when connectivity returns.

## TTLs and limits

- One-time grant: 60-900 seconds. Product default: 600 seconds.
- Anytime grant: 60 seconds to the hard server maximum of 86,400 seconds
  (24 hours).
- Consent/start request window: server default 300 seconds, never beyond grant
  expiry.
- Session: 60-900 seconds. Product default and hard maximum: 900 seconds.
- One requested/active grant and one requested/active session per device.
- At most 100 monotonically sequenced commands per session.
- At most one unresolved command per session. New commands have a two-second
  per-session issue cooldown; `collect_diagnostics` has a 60-second cooldown.
- Device online window: default 45 seconds; returned by `GET /devices`.

Environment settings:

```text
REMOTE_ASSISTANCE_DEVICE_ONLINE_SECONDS=45
REMOTE_ASSISTANCE_REQUEST_TTL_SECONDS=300
REMOTE_ASSISTANCE_SESSION_MAX_SECONDS=900
REMOTE_ASSISTANCE_ANYTIME_GRANT_MAX_SECONDS=86400
REMOTE_ASSISTANCE_PAIRING_SECRET=<dedicated strong secret, at least 32 chars>
REMOTE_ASSISTANCE_RELAY_SECRET=<independent standard-base64 32-byte AES key>
REDIS_PASSWORD=<independent 64-character lowercase hexadecimal ACL password>
REMOTE_ASSISTANCE_DEVICE_KEY_PENDING_SECONDS=600
REMOTE_ASSISTANCE_DEVICE_SIGNATURE_MAX_SKEW_SECONDS=90
REMOTE_ASSISTANCE_DEVICE_NONCE_TTL_SECONDS=240
REMOTE_ASSISTANCE_FRAME_MAX_BYTES=524288
REMOTE_ASSISTANCE_FRAME_MAX_WIDTH=1920
REMOTE_ASSISTANCE_FRAME_MAX_HEIGHT=1200
REMOTE_ASSISTANCE_FRAME_TTL_SECONDS=5
REMOTE_ASSISTANCE_FRAME_RATE_PER_SECOND=1
REMOTE_ASSISTANCE_FRAME_DECODE_MIN_INTERVAL_MS=2000
REMOTE_ASSISTANCE_FRAME_READ_TIMEOUT_SECONDS=5
```

## Frame relay contract

Upload uses `PUT /device/sessions/{session_id}/frame` with raw JPEG bytes and:

```text
Content-Type: image/jpeg
X-Installation-Id: UUIDv4
X-Frame-Id: UUIDv4
X-Frame-Sequence: positive signed 64-bit integer
X-Frame-Width: integer
X-Frame-Height: integer
X-ERP-Frame-Redacted: true
```

The five signed-request headers are also mandatory. `X-ERP-Content-SHA256` is
the digest of the exact JPEG body.

`X-ERP-Frame-Redacted: true` means the frame passed the own-window privacy and
redaction pipeline. It is required for every ordinary safe app-window frame as
well as a generated privacy placeholder; it does not mean that visibly obscured
pixels must exist in every frame.

Default limits are 512 KiB, 240x180 minimum, 1920x1200 maximum, one frame per
second per session, a two-second pre-decode admission interval, and five-second
Redis TTL. Android's normal four-second cadence remains below both gates.
Portrait frames such as 256x540 and landscape frames such as 960x540 are valid.
Declared dimensions must equal decoded dimensions. Admission order is strict:
validate bounded headers/key -> verify the signature over its claimed hash ->
claim the Redis nonce -> apply Redis pre-decode frame admission -> bounded body
read -> constant-time actual hash comparison -> threadpool Pillow decode and
metadata-stripping JPEG re-encode. A compromised authenticated tablet therefore
cannot force unbounded body reads or Pillow CPU work before replay/rate gates.
The database installation lock is released before the bounded network read and
image work, then reacquired to revalidate the exact key, user, grant, and
session immediately before storage. Slow uploads therefore cannot hold the DB
pool or block Stop/revoke/heartbeat, and a revocation during upload prevents
storage. Frame sequence must strictly increase; replay is rejected.

Frame retrieval returns `image/jpeg` with `Cache-Control: private, no-store` and
the headers `X-Frame-Id`, `X-Frame-Sequence`, `X-Frame-Width`,
`X-Frame-Height`, and `X-Frame-Received-At`. Missing/expired frames return 404;
relay failure returns 503. AES-GCM authenticates the receipt time and expiry as
well as the frame identity and geometry. Retrieval checks that authenticated
wall-clock expiry independently from Redis key TTL, so copying a valid envelope
back with a longer Redis TTL cannot extend its life. Stale, malformed, or
authentication-failed frame/metadata keys are compare-and-deleted without
deleting the monotonic sequence key or a newer concurrent writer.

## Idempotency and conflicts

Request, session, command, decision, start, revoke, and end action identifiers
are immutable idempotency keys. Exact replay returns the original immutable
result. Within a company, reuse for another entity, payload, sequence, decision,
or terminal outcome returns 409. Request/session/command primary identifiers
also reject a collision with another tenant; decision/start/revoke/end evidence
is intentionally company-scoped. PostgreSQL unique constraints and transition
triggers provide a second enforcement layer.

The API uses the normal error envelope. Relevant HTTP statuses are:

- 401 `unauthorized`: invalid/missing device proof, forged signature, stale
  timestamp, replayed nonce, altered target/body, or inactive key on an
  operational device endpoint.
- 403 `forbidden`: missing protected-owner permission, non-Android device
  endpoint, or authenticated device user mismatch.
- 403 `remote_pairing_code_mismatch`: a well-formed physical pairing code did
  not match; this is distinct from owner authorization denial.
- 404: tenant-scoped device/grant/session/command/frame not found.
- 409: stale/not-ready device, invalid lifecycle transition, open-session
  conflict, out-of-sequence command/frame, or idempotency conflict.
- 410 `remote_action_gone`: an otherwise well-formed durable device mutation is
  no longer actionable after expiry, owner revocation, or account handoff.
- 422: UUIDv4, enum, extra-field, MIME, byte, geometry, or JPEG validation.
- 408 `remote_frame_timeout`: the bounded raw-JPEG body did not arrive in time.
- 429: frame-rate limit, with `Retry-After`.
- 503: Redis relay unavailable or invalid relay data.

## Audit and data retention

Manual append-only audit rows cover device-key enrolled/approved/rotated/
revoked/expired, grant requested/accepted/declined/revoked/expired/consumed,
session requested/started/ended/expired, and command issued/acknowledged/
rejected. They include requester or device-user actor attribution, terminal
attribution when present, lifecycle state, semantic command type and sequence,
and one-way hashed device/key references. They do not contain bearer tokens,
private/public key bytes, pairing codes, raw installation UUIDs, JPEG bytes,
request headers, or screenshots. The full fingerprint is audit metadata only
after protected-owner approval.

PostgreSQL constraints and triggers enforce tenant/device/actor scope,
idempotency uniqueness, immutable evidence, allowed state transitions, one
active/one pending key per installation, and retention of device/grant/session/
command evidence. Migration `0062` creates the key evidence table and is tested
upgrade -> downgrade -> re-upgrade. Its upgrade explicitly aborts if a
historical command exists outside the closed set; it never silently leaves an
unvalidated command constraint.

`REMOTE_ASSISTANCE_PAIRING_SECRET` must be generated independently from all
other secrets (for example `openssl rand -base64 48`) and delivered through the
deployment secret channel. Production/staging startup rejects the public
placeholder. Rotating it changes every outstanding pending pairing code; active
approved keys remain valid, but pending tablets must refresh signed status and
display the newly derived code.

`REMOTE_ASSISTANCE_RELAY_SECRET` is independent from JWT, pairing, Redis,
database, storage, and owner credentials. Rotation intentionally invalidates
only the current ephemeral frame (at most the configured five-second TTL); a new
tablet frame repopulates the relay. Redis password rotation requires updating
`.env` and recreating Redis and backend together.

## Existing-install production upgrade and rollback

`install-on-vm.sh` treats an upgrade as a scheduled, fail-closed migration, not
a fresh deployment. It takes a nonblocking root-owned deployment lock; a second
SSH invocation cannot race the first. Run an existing install only after staff
activity is stopped and every tablet is synced:

```text
sudo bash infra/scripts/install-on-vm.sh erp.example.com --maintenance-confirmed
```

Normal upgrades require the CLI domain to equal the existing normalized
`DOMAIN`. A domain change is deliberately rejected: it needs a separate atomic
DNS/TLS/CORS/public-URL migration. If `.env` is absent but project containers,
volumes, networks, `.env.pre-code17.*`, or rollback evidence remain, fresh
secret generation is also rejected; recover the prior environment instead.

The original Code16 installer wrote `CHANGE_ME_git_commit_sha` into otherwise
normal 3.1.5 image labels. That exact legacy case has one explicit bridge:

```text
sudo bash infra/scripts/install-on-vm.sh erp.example.com \
  --maintenance-confirmed \
  --legacy-code16-revision 2ac3fc88e4ce14d0f05d049b443a6a09c387a78a
```

The bridge accepts only that known Code16 commit, immutable image version
3.1.5, and database head `0060`. It creates a stopped container from the
immutable backend image, copies its filesystem without executing image code,
and host-verifies the exact tracked backend file set, content hashes, and
entrypoint against the commit. Unexpected or linked source files fail closed.
Correctly labelled releases go through the same source verification.

Before maintenance the installer:

1. requires a clean exact current Git commit and records the prior running
   Compose project, database head, immutable image IDs, protected rollback tags,
   source-manifest digest, prior Compose file, and mode-0600 prior `.env` in a
   mode-0700 `.deployment-rollbacks/` directory;
2. creates and validates a mode-0600 candidate without overwriting real JWT,
   Postgres, MinIO, owner, pairing, relay, or Redis credentials; all seven
   managed credentials must be pairwise independent, and no secret is printed;
3. writes the current 40-hex Git HEAD as the candidate `APP_REVISION`, validates
   domain/origin consistency and Compose resolution, builds candidate images
   while the old service remains live, and proves existing HTTPS `/readyz`;
4. checks independent free-space reserves on the rollback filesystem and
   PostgreSQL volume for the dump and a full restore-test database.

Only after those checks does it stop Caddy, backend, and frontend, verify the
server-recorded tablet outbox total is zero, and create the final quiesced
custom-format dump. It records a SHA-256 checksum, verifies the archive list,
fully restores the dump into a disposable database, verifies its exact Alembic
head, and drops the test database. Writes stay closed from that final snapshot
through migration and internal acceptance. The candidate `.env` is then
atomically promoted; Postgres/Redis/MinIO/backend/frontend start without Caddy;
the installer requires `/readyz`. A fresh install additionally signs in with
the generated `SEED_OWNER_EMAIL`/password over the internal interface and
requires protected audit authority plus `admin.system`, without displaying the
password. Seed and role failures are fatal, not reported as “already applied.”

Before Caddy is reopened, a failure automatically checksum-verifies and restores
the quiesced database, prior `.env`, protected image tags, and prior Compose
services, then requires prior `/readyz`. Once Caddy may accept writes, that old
dump is never automatically restored. A separate failure trap stops Caddy,
preserves the current Code17 database, and points the operator to the protected
snapshot so no post-cutover payment/shift can be silently lost.

Keep the snapshot until physical-tablet and owner-web acceptance gates pass.
For a deliberate Code16 application rollback, first re-establish maintenance,
stop Caddy and all application writers, and take a new verified final backup.
With the Code17 migration image still available, downgrade to the exact Code16
head `0060` (not `0061`; Code16 does not know revision 0061), for example with
the Code17 backend image's entrypoint overridden to run `alembic downgrade
0060`. Verify the head, run `restore-images.sh`, atomically restore the prior
mode-0600 `.env`, and start `docker-compose.prior.yml` with the recorded Compose
project and `--project-directory`/`--no-build`. If downgrade fails, keep ingress
closed and restore the latest quiesced verified dump; never boot Code16 against
a database at 0061/0062. Verify `/readyz`, owner login, a POS read/write, and
remote-assistance absence before reopening staff traffic. The disposable
migration gate proves `0061 -> 0062 -> 0060 -> 0062`, including POS persistence
across the Code16 rollback boundary.
