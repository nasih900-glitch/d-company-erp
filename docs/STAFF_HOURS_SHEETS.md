# Staff-hours mirror v1

**Deferred by the Owner on 3 November 2026.** The new live salary tab, generated
cost block and protections were reversed. The original workbook's tabs, 1,002
cost rows, formulas, totals and sharing were independently restored. No staff
export capability, receiver deployment or production staff policy was activated.
The following is retained implementation guidance for a later Sheets phase;
it is not an Android release activation requirement.

This capability is additive to the existing Google Sheets mirror. It uses the
same destination, HMAC secret and configuration generation, with independent
staff verification. Attendance exports are restricted to people with at least
one Owner-written `StaffWorkPolicy` in this company. A draft policy explicitly
enrolls that person; it does not approve pay. A company capability check alone
does not enroll an Owner, an unconfigured staff member, or a future new user.
Their attendance still works without creating a staff Sheets fact. Configured
departed-person history remains retained and revisable. No financial export mode, cursor, queue, manual revenue
range, cost, payroll entry or payment is changed.

## Owner setup and verification

Use the reviewed receiver in a private Owner-only standalone project. Set the
mandatory `D_COMPANY_ERP_SPREADSHEET_ID` property to the intended native workbook
ID; no ambient active-spreadsheet fallback exists. Preserve the existing
daily-collections settings and durable financial generation. If the existing
project is public/shared, follow the guarded private-project/key migration in
`GOOGLE_SHEETS.md`; do not copy its signing key into another shared project or
silently retarget its queued facts.

Keep the admitted spreadsheet-ID property fixed for that verified generation.
A workbook change requires the drained Owner cutover and new proofs described
in `GOOGLE_SHEETS.md`; health/ACK responses alone cannot establish the target ID.

Within an already admitted private receiver, add the staff-specific property
`D_COMPANY_ERP_STAFF_HOURS_CONFIG`, with the existing company ID and a bounded
staff projection capacity:

```json
{"company_id":"existing-company-UUID","projection_end_row":5000}
```

Run `setupStaffHoursProjection()` as the spreadsheet Owner. It creates only:

- `ERP Staff Hours v1`: hidden, Owner-protected, append-only signed fact log;
- `STAFF HOURS ERP`: protected latest-revision projection, with hours and base
  amounts converted for display while preserving empty unknown values;
- `STAFF HOURS MANUAL OVERRIDES`: Owner-editable reviewed-work overrides, keyed
  by Attendance ID + current ERP source revision + current ERP payload hash.
  They change only this sheet's effective work view, never ERP attendance, costs,
  payable amounts or payments. All prior override rows remain retained.

The manual tab columns are Attendance ID, ERP Source Revision, ERP Payload
SHA256, Reviewed Work Minutes, Reason, and Notes. Copy ID/revision/hash from the
current attendance row. Source proof cells are formatted as text to preserve
all digits. Work minutes must be an integer from 0 through 10,080, with a
nonblank text reason of at most 500 characters; zero is a real correction.

The projection adds **Effective Work Minutes** and **Correction Status**. One
valid current override produces `Manual correction`; otherwise effective work
falls back to the recorded ERP net seconds / 60, or stays blank if unknown.
An ERP revision/hash change makes an old override `Stale correction` and stops
applying it automatically. A new current override can coexist with old retained
history. Duplicate current proofs are `Ambiguous correction`; malformed values,
proof or reason are `Invalid correction`. Neither applies. Policy rows remain
`Draft policy` with no effective attendance minutes. Manual corrections never
fill the draft payable/OT fields. The live formula recalculates when either the
ERP log or manual inputs change; no new edit trigger or financial onEdit handler
is installed.

Hidden/protected tabs enforce write ownership, not confidentiality from other
workbook viewers or editors. Before enabling staff exports, the Owner must
verify that every workbook recipient is authorized to see attendance and draft
salary data.

The setup refuses header, formula, protection and projection collisions. It
never clears an existing tab. Existing daily setup and manual financial ranges
are outside this capability's write paths.

An authenticated Owner with `staff.payroll.write` calls:

- `POST /api/v1/settings/google-sheets/staff-hours/test` to opt in and queue a
  generation-bound capability check;
- `GET /api/v1/settings/google-sheets/staff-hours` to distinguish requested from
  verified. Queued is not verified.

The signed handshake is `staff_hours.connection_test` from
`staff_hours_configuration`, carrying exactly `required_capability`, `scope`
(`company_id` only), and `status: "test"`. Its source revision is the current
configuration ID. Only an exact event/hash/schema/`staff_hours.v1`/scope ACK can
mark it delivered. A legacy generic ACK, health document or daily capability
does not verify staff exports. Every staff data ACK also proves the capability,
so reverting a deployment to an old generic receiver cannot silently consume
staff facts into the financial log.

Verification is independent in both directions. Facts may wait durably while
an explicitly requested staff test is pending. Financial-only installations
create no hidden staff queue. Changing a destination/secret still follows the
existing guarded financial-generation drain policy; it is not a staff setup
step. There is no implicit backfill or opt-out-by-deleting-history operation.

## Attendance and policy facts

The unchanged `erp-mirror.v1` envelope carries `staff_hours.fact`, with source
type `staff_hours.attendance` or `staff_hours.policy`. Payload schema is exactly
`staff_hours.v1`; unknown versions, namespaces, missing/extra fields,
inconsistent durations and non-draft pay are refused before append.

Attendance facts preserve exact UTC timestamps, integer gross/work seconds and
an explicitly confirmed actual break. Open intervals are provisional. Legacy
default zero breaks remain unknown unless an audited, explicit input confirms
them. Planned breaks never substitute for actual breaks.

Policy facts contain immutable draft terms. An earlier draft illustration was monthly
base 1,200,000 minor units (12,000), daily presence 720 minutes, planned break 60
minutes, planned work 660 minutes, weekday mask 127 (every day). The helper
exports recorded policy values; it does not silently create these defaults.
These planning fields do not deduct breaks from the fixed monthly salary.

`pay_status` is always `draft`. `payable_minor` and `overtime_pay_minor` are
always JSON null, displayed as blank rather than zero. Monthly base metadata is
not accrued earnings or an actual payment. No overtime rate, salary divisor,
proration, overtime hours or payment is inferred from one attendance interval.

Source revisions are fixed-width text: the exact persisted attendance
`updated_at` UTC microseconds, or the immutable policy revision. Log cells are
read back before ACK; the projection chooses the greatest source revision per
record key, rather than network arrival order. Conflicting replay identities/revisions, a mismatching newly-written row
read-back, a corrupt matched replay row and exhausted projection/log capacity
fail closed with retained evidence. Unrelated historical log rows are not all
cryptographically revalidated before every append. Existing outbox retries, redirect allowlist and quarantine behavior
remain in force. Destination/secret generation lifecycle treats a successful
financial OR staff capability test as prior verification, while their
permission-to-dispatch checks stay independent. Once either proof exists,
material changes refuse outstanding business rows and rotate the generation
only after it is drained; staff-first verification cannot retarget held rows.

## Integration at the business transaction boundary

The Owner/self attendance and policy handlers call these helpers within their
existing transaction, before storing the idempotent success receipt:

```python
await session.flush()
await session.refresh(attendance)
await enqueue_staff_attendance_if_enabled(
    session, attendance=attendance, actual_break_confirmed=explicit_actual_break,
)
```

Call on Owner/self clock-in (confirmation false), explicit-break clock-out
(true), and audited attendance correction (true only when the actual break is
explicitly known). Keep the call before the caller-owned transaction commits or
stores its success receipt. Do not enqueue on an idempotent early replay.

After a new immutable draft policy is flushed/refreshed, call
`enqueue_staff_policy_if_enabled(session, policy=policy)` in that transaction.
The confirmation argument is the same explicit proof used by the staff API,
not a new Attendance database column. A recorded positive legacy actual break
can be confirmed by the caller under that API policy; an unproven legacy zero
must stay unknown. Neither helper commits. A rollback removes the fact and its delivery together.
Do not invent a new source revision merely to retry delivery or retarget an
already-enqueued fact after generation rotation.

Attendance `updated_at` must be persisted strictly monotonically per record by
every hooked update writer under the existing per-user lock. PostgreSQL
`func.now()` alone is transaction-start time and is insufficient: an older
transaction can obtain the lock later. The authoritative handler integration
must enforce a value greater than the prior persisted timestamp (with a
deterministic inverted-transaction-order regression) before this latest-row
projection can be accepted. This patch does not change global TimestampMixin.

The focused tests use a local disposable PostgreSQL database and a VM workbook
model. They do not prove deployment, real Apps Script formula evaluation or
delivery to the live workbook. Those remain separate acceptance steps.

## Fixed monthly salary register

The separate `Staff Salary` tab records the Owner's fixed agreement: 12,000 per
person per month, an optional manually entered overtime amount, and a Paid
checkbox. Attendance, planned breaks and draft overtime multipliers do not
change that fixed amount. Paid is a bookkeeping status, not a payment command.

Explicit month/person rows generate one cost each in a reserved, protected
`Running Cost` block. The stable key is `salary:YYYY-MM:<staff UUID>`; duplicate
person/month rows and conflicts with an existing keyed cost fail visibly.
Existing profit formulas already sum Running Cost, so there is no additional
salary subtraction. Marking Paid changes neither the cost nor profit again.
Unconfigured rows stay blank. Invalid configured rows visibly block the cost
total instead of silently omitting an expense. No future month or historical
salary is inferred automatically.

The initial live register was tested with November 2026 only, two 12,000 rows,
blank overtime and Paid unchecked. Native Google calculation verified a single
24,000 cost increase and corresponding profit reduction. The Owner then asked
to reverse this work and defer Sheets until after the app release. The new tab,
cost block and protections were removed; full readback verified all original
rows, totals and formulas restored. No ERP Expense, PayrollEntry or bank payment
was created. A private Owner-only backup remains available for the audit trail.

Per the Owner's explicit decision, existing workbook sharing remains unchanged.
Anyone with its link can still view staff pay and financial records; protection
does not conceal them. New salary inputs, Paid cells and generated costs are
Owner-edit protected. Hour exports remain a separate, independently verified
capability and are not activated by creating this register.
