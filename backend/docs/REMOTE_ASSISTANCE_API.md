# Remote Assistance API and Threat Model

Status: backend contract for Code 17. The authoritative prefix is
`/api/v1/remote-assistance`.

## Security boundary

Remote assistance is a first-party D Company ERP support channel. It is not a
general remote-desktop feature.

- A registered Android `ClientInstallation` is the device identity. Its public
  `installation_id` is an opaque UUIDv4; no new device token or parallel device
  registry is introduced.
- Owner operations require `admin.system`, which is restricted by the existing
  protected-owner permission boundary.
- Device operations require a normal authenticated ERP user, an Android client
  header, the same company, and the user currently attributed to the registered
  installation.
- A user decision is required before any session starts. An `anytime` grant may
  authorize later sequential sessions until its own expiry or revocation, but
  every session is independently capped at 15 minutes.
- Commands are a closed semantic set. There are no tap coordinates, keystrokes,
  selectors, URLs, scripts, shell operations, arbitrary JSON arguments, payment
  operations, refunds, ledger operations, or other financial mutations.
- The only image is the latest sanitized JPEG in Redis. No frame bytes, object
  key, screenshot path, or historical frame metadata are persisted in the
  database or durable queue.
- Redis is an availability and privacy boundary. Starting a session, issuing a
  command, polling an active device session, uploading a frame, or viewing a
  frame fails closed when the relay is unavailable.

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

All device endpoints require the normal bearer access token and
`X-Client-Platform: android`.

| Method and path | Request | Response |
|---|---|---|
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
  type: navigate | refresh | sync_now | collect_diagnostics
  module: dashboard | gaming | pos | shift | help   # navigate only

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

Owner Stop is authoritative through `POST /sessions/{id}/end`; there is no
duplicate queued `end_session` command that would become unreachable after the
state transition. Android observes the ended lifecycle through realtime/poll
recovery and stops. The user Stop action still stops capture locally first and
retries its same `end_id` when connectivity returns.

## TTLs and limits

- One-time grant: 60-900 seconds. Product default: 600 seconds.
- Anytime grant: 60 seconds to the configured maximum; server default maximum
  is 30 days. Product default: 86,400 seconds (24 hours).
- Consent/start request window: server default 300 seconds, never beyond grant
  expiry.
- Session: 60-900 seconds. Product default and hard maximum: 900 seconds.
- One requested/active grant and one requested/active session per device.
- At most 100 monotonically sequenced commands per session.
- Device online window: default 45 seconds; returned by `GET /devices`.

Environment settings:

```text
REMOTE_ASSISTANCE_DEVICE_ONLINE_SECONDS=45
REMOTE_ASSISTANCE_REQUEST_TTL_SECONDS=300
REMOTE_ASSISTANCE_SESSION_MAX_SECONDS=900
REMOTE_ASSISTANCE_ANYTIME_GRANT_MAX_SECONDS=2592000
REMOTE_ASSISTANCE_FRAME_MAX_BYTES=524288
REMOTE_ASSISTANCE_FRAME_MAX_WIDTH=1920
REMOTE_ASSISTANCE_FRAME_MAX_HEIGHT=1200
REMOTE_ASSISTANCE_FRAME_TTL_SECONDS=5
REMOTE_ASSISTANCE_FRAME_RATE_PER_SECOND=1
REMOTE_ASSISTANCE_FRAME_DECODE_MIN_INTERVAL_MS=2000
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

`X-ERP-Frame-Redacted: true` means the frame passed the own-window privacy and
redaction pipeline. It is required for every ordinary safe app-window frame as
well as a generated privacy placeholder; it does not mean that visibly obscured
pixels must exist in every frame.

Default limits are 512 KiB, 240x180 minimum, 1920x1200 maximum, one frame per
second per session, a two-second pre-decode admission interval, and five-second
Redis TTL. Android's normal four-second cadence remains below both gates.
Portrait frames such as 256x540 and landscape frames such as 960x540 are valid.
Declared dimensions must equal decoded dimensions. The Redis admission gate
runs before body streaming and fails closed; Pillow decode and metadata-stripping
JPEG re-encode then run off the async API loop. Frame sequence must strictly
increase; replay is rejected.

Frame retrieval returns `image/jpeg` with `Cache-Control: private, no-store` and
the headers `X-Frame-Id`, `X-Frame-Sequence`, `X-Frame-Width`,
`X-Frame-Height`, and `X-Frame-Received-At`. Missing/expired frames return 404;
relay failure returns 503.

## Idempotency and conflicts

Request, session, command, decision, start, revoke, and end action identifiers
are immutable idempotency keys. Exact replay returns the original immutable
result. Within a company, reuse for another entity, payload, sequence, decision,
or terminal outcome returns 409. Request/session/command primary identifiers
also reject a collision with another tenant; decision/start/revoke/end evidence
is intentionally company-scoped. PostgreSQL unique constraints and transition
triggers provide a second enforcement layer.

The API uses the normal error envelope. Relevant HTTP statuses are:

- 403: missing protected-owner permission, non-Android device endpoint, or
  authenticated device user mismatch.
- 404: tenant-scoped device/grant/session/command/frame not found.
- 409: stale/not-ready device, invalid lifecycle transition, open-session
  conflict, out-of-sequence command/frame, or idempotency conflict.
- 422: UUIDv4, enum, extra-field, MIME, byte, geometry, or JPEG validation.
- 429: frame-rate limit, with `Retry-After`.
- 503: Redis relay unavailable or invalid relay data.

## Audit and data retention

Manual append-only audit rows cover grant requested/accepted/declined/revoked/
expired/consumed, session requested/started/ended/expired, and command issued/
acknowledged/rejected. They include requester or device-user actor attribution,
terminal attribution when present, lifecycle state, semantic command type and
sequence, and a one-way hashed device reference. They do not contain bearer
tokens, raw installation UUIDs, JPEG bytes, request headers, or screenshots.

PostgreSQL constraints and triggers enforce tenant/device/actor scope,
idempotency uniqueness, immutable evidence, allowed state transitions, and
retention of consent/session/command evidence. Migration `0061` is reversible;
downgrade removes the remote-assistance evidence tables and the three nullable
heartbeat columns from `client_installations`.
