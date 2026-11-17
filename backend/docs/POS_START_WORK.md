# Online Start work

`POST /api/v1/pos/shifts/start-work` accepts only
`{"opening_float_minor": <non-negative bigint>}` and a canonical
`Idempotency-Key: shift-open:<UUIDv4>`. The active actor needs both
`pos.shift.open` and `staff.attendance.write` in the selected branch and Hybrid
terminal. Android additionally supplies its canonical originating installation.
`X-Offline-Captured` is refused. Fresh server time records a new clock-in;
a valid existing clock-in in this branch keeps its original time. Shift,
attendance, audit, optional mirror events, domain receipt and ordinary idempotency
reservation commit together.

A receipt describes the original committed operation. Retrying that exact action
returns the original receipt even after attendance clock-out and shift close;
it never reopens either record. Actual shift state is read separately using the
existing scoped shift list with `shift_id=<receipt shift UUID>`. Empty lists,
failed reads, or an `only_open` list cannot prove a historic shift is closed.
The new shift starts with untracked till custody until the normal verified
handover workflow records a cashier. An opener is not a verified current cashier.

Terminals advertise `online_start_work_supported: true`. Missing capability is
false. The legacy `/pos/shifts/open` captured/offline path remains separate and
does not create attendance. A pending online request must never fall back to it.

Clients durably retain the exact credential-free action before sending. A lost
response keeps the original action and blocks workspace exit/new starts. Ordinary
resend requires the original authority binding. A new login may read
`GET /pos/shifts/start-work/<original UUID>` without resending an old mutation.

`POST /pos/shifts/start-work/recovery` provides exact Owner review without
switching the cashier session. Its strict body contains the original `reference`,
`mode` (`status` or `cancel`), fresh `owner_email` and `owner_password`, and a
12–500 character cancellation `reason`. The reference binds original action UUID,
actor, company, branch, terminal, platform, installation, opening float and
canonical normal-command request hash. Credentials are purpose limited, checked
against current Owner assignment, rate limited, and never retained in the action,
receipt, cancellation or audit. Target actor/terminal may be inactive; target
company/branch ownership must still match.

Review returns `unconfirmed`, `committed`, or `cancelled`. Committed receipts
always win and preserve the real shift/attendance. Cancellation serializes with
the original action and durably fences only a positively identified uncommitted
action. Delayed online, legacy or direct SQL shift creation with that exact key is
then refused. Existing ordinary-key/shift evidence without its matching domain
receipt requires review and cannot be declared uncommitted. A lost cancellation
reply is recovered by status lookup. Only positive cancellation or a positively
matched exact closed shift may settle without open-shift hydration.

Migration `0099` keeps both outcome tables append only, guards their final
provenance and mutually exclusive outcomes, and refuses downgrade while evidence
exists. No broad queue clearing, cache fabrication, or business-row deletion is a
recovery mechanism.

Build73 / v3.1.66 requires schema0105. Android performs the exact shift read,
provenance/lease validation and transactional cache publication under the shared
SyncEngine shift refresh serialiser, preventing concurrent snapshots from
interleaving with recovery hydration.

A local PostgreSQL16 rehearsal populated immutable Build71 code at0095,
performed a real dump/restore, upgraded through0096–0099, round-tripped empty
outcome ledgers to0095 and back, then exercised
legacy captured replay without attendance creation and current Start work
preservation/historic replay. This is synthetic API and preservation evidence.
Production installer admission remains closed pending actual runtime, Redis
repair/recreation-hold and fleet provenance; it does not establish physical
tablet or production compatibility.
