# Build 51 native client implementation

## Contract and retained state

The API contract is `backend/docs/TENDER_CORRECTIONS_API.md`. Financial writes
are online, protected-owner actions; ordinary staff never gain correction
permission through a client flag. Prepare only reads sources. Commit sends the
server fingerprint, confirmed actuals where applicable, reason and evidence.
Posting deltas are derived by the server. Unknown daily differences remain
unresolved evidence without changing revenue, bills or drawer counts.

`TenderJournal` uses dedicated private JSON files, lazily opened on IO.
A shared process mutex serializes every instance. It persists only the company,
owner, branch, operation, exact canonical body, idempotency key and preparation
time. File force, atomic rename, directory fsync and readback must succeed before a request is sent. Android SharedPreferences is deliberately avoided because its XML reader silently replaces corrupt data with an empty map. Complete interrupted preparations retain their exact identity too; incomplete files fail closed. Malformed,
unknown-version, unreadable or mismatched saved work is never treated as absent
and is never automatically deleted. Cleanup compares the exact captured record.
A missing entry that another view recovered does not authorize a new command:
explicit recovery re-reserves the retained exact body/key. Tokens/passwords stay
in memory. There is no financial outbox/background replay or automatic send on
login/account switch. HTTP failures do not silently retire uncertain actions.

Room 55 → 56 adds exactly five nullable columns to `shift_history_cache`:
`collectionBasis`, `tenderCorrectionMinor`, `reconstructedCashCollectionsMinor`,
`reconstructedUpiCollectionsMinor`, `reconstructedExpectedMinor`.
They are server projection metadata, not replacements for recorded expected
cash, count, variance or original tender totals. Old rows stay NULL until a
successful authoritative pull. No other Room entity changes in this slice.
The existing immutable `PosReceiptEntity` remains untouched. Correction-aware
`CanonicalReceipt` JSON is replaceable shared history, with original and
effective methods preserved separately through serialization.

Audit reads use the backend-owned catalogue and materialized visible-ID page.
`activity_cursor` performs a nonallocating check even for an empty snapshot;
checks must not replace rows, filters, selection or the paging cursor. Snapshot
expiry requires explicit refresh while keeping the existing loaded evidence
labelled stale. Scope and token lifetime remain server-authoritative.

Receipt filters are server predicates bound to the cursor: reference, inclusive
invoice-issued shop date range, retained station, exact original invoice amount,
effective method and retained payment recorder. They are not current-page-only
search. Investigation uses retained participant intervals, one charge per
identity, drink addition/void facts, receipt/refund legs and protected Audit
references; current mutable catalogue names are not historical evidence.

## Acceptance boundaries

Contract/unit tests and migration component tests do not establish signed
artifact identity, retained installed-build upgrades, actual staff workflows,
physical tablet smoothness or production acceptance. Version identity and
release freeze are coordinated after all client slices settle. Final app review
must include actual Gaming, POS and Shift screens in the isolated emulator
before an update is offered through the tablet's optional banner.

## Contract slice evidence

The local focused host run passed 34 tests: journal concurrency/restart/scope,
real filesystem reopen/corruption and sync/move failures, Retrofit exact stored
and replayed request bytes/keys, purpose-separated unlock/history, retained HTTP
failure recovery, full receipt filter tuple, effective/original receipt JSON,
and nullable Shift mapping/merge. Debug and Android-test Kotlin compilation
passed with the isolated API override `http://10.0.2.2:8022/api/v1/`.
This slice is still the development build identity; it is not a signed release
or a claim that all native UI work or final device acceptance has completed.

On the isolated API35 emulator `emulator-5620`, two real Android instrumentation
tests passed: the 55→56 migration kept original recorded close totals and an
unknown pending payment unchanged while all five reconstruction fields stayed
NULL; the actual Android file adapter completed fsync/atomic rename, reopened
its exact intent, and refused truncated underlying data without replacing it.
The debug app was installed in place with `-r`; no uninstall/storage clearing.


## Native Audit and retained bill investigation

The Audit workspace consumes the shared server catalogue and `/admin/audit/events` snapshot contract. Every page and activity check carries the complete applied filter tuple. Checks use the retained `activity_cursor`, including the signed empty-snapshot cursor, and never allocate or replace a loaded snapshot. New activity is announced for explicit refresh. Expired snapshots and network failures retain loaded evidence; a changed search cannot relabel old rows as its results.

Protected grants, loaded rows, filters, selection and viewport are scoped in memory to company, current user and branch. Lock or grant expiry cancels the request generation and resets busy state immediately. A later completion cannot unlock or replace a newer account's data. Re-verification by the same account can restore its private return state; logout, account/branch change or authorization refusal clears it. Passwords are used only for the unlock request and are not saved. Current staff labels are explicitly current labels, while OTP-approved requesters and unverified legacy attribution remain distinct from authenticated actors.

Activity expands inline into business facts, Before → After, recorded timestamps and an optional read-only bill investigation. Raw IDs, source fingerprints and recursively redacted JSON remain behind Technical details. Bill investigation uses the server's retained receipt, participant intervals, one-charge identity, add-on confirmation/void facts and related Audit references. Missing historical names and absent timing evidence stay unknown; current catalogue names are not presented as historical snapshots. When shop timezone is unavailable, UTC is explicitly a display fallback.

This slice does not yet implement the protected tender workspace or finish receipt-history filter controls, Shift/Reports explanations, device-status wording or final palette validation. Those remain tracked work; this is not release or physical-tablet acceptance.

The focused Audit slice run passed 24 host tests (12 request-generation/scope/snapshot lifecycle, five existing DTO/presentation, five semantic presentation and two real Retrofit contract cases) and compiled Android-test Kotlin against the isolated 8022 API. The lifecycle tests include lock and expiry during deferred work, access revocation, old-account callback refusal, stable empty-snapshot checks, full cursor-bound filters, explicit refresh, linked-reference queuing and same-owner return state. This is component/contract evidence; the actual Audit screen still requires emulator rendering and interaction checks.

Audit verification also keeps an `elapsedRealtime` deadline based conservatively on the unlock request start. Protected rendering, foreground/resume, actions and accepted completions all check it. A coroutine delay is only a wake-up hint because Android Handler delays pause during deep sleep. The focused follow-up passed 26 host tests and Android-test Kotlin compilation, including two cases that advance the injected elapsed clock without advancing the timer: resume hides loaded/selected data and late metadata/pages cannot reopen it.

## Native protected correction workspace

Receipt payment legs now open the protected Cash → UPI workflow inline. Finance → Collections has an inline branch/date review for owner-confirmed Cash and UPI actuals. The same receipt workspace also opens the consolidated retained bill investigation and a separately scoped, protected linked Audit view. Neither the Web sidebar nor the native Gaming/POS/Shift layout was replaced.

The dedicated ViewModel rechecks current company/user/branch authority before sends and accepted completions, and uses an elapsed-time verification deadline. New Confirm and explicit Recover are separate commands. A saved command from another source is shown for review; Confirm never adopts it. An already-retained exact command wins even if another view recovered and cleared its file. Every send reserves/fsyncs/readbacks that command again, and matching returned receipts are required before exact cleanup. Authentication errors, retryable conflicts, transport loss, expiry, account switching and storage failure preserve recovery identity. Only a positively rejected exact command can be explicitly acknowledged; a failed acknowledgement remains inline and preserves evidence.

Daily previews compare owner actuals against the server's Cash + UPI subtotal, not the all-method total. Other methods are explicitly outside the attestation. Zero-sum supported transfers and unresolved total differences have different final receipt wording; unresolved history continues to show actuals and the total difference with no posting. Original bill amounts and physical drawer counts are not edited.

The focused workflow run passed 37 host tests (10 new VM/lifecycle cases plus 27 journal, real-file, Retrofit and projection cases) and Android-test Kotlin compilation. Real emulator correction/receipt interactions, accepted-commit/lost-reply transport acceptance, global saved-work readiness, advanced receipt-filter controls, Shift/Reports explanations and the remaining visual/status refinements are still open. This development slice is not a signed update or a complete Build51 acceptance claim.
