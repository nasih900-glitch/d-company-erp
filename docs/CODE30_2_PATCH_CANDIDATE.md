# Code30.2 finance evidence and backup patch candidate

Code30.2 is an additive patch to the existing Code30 application. Its intended
identity is `v3.1.29`, Android installation build `37`, Room schema `51`, and
Alembic head `0078`. It is based on immutable Code30.1 `v3.1.28` / build `36`;
that tag, its release evidence, and any signed APK bytes must not be rebuilt or
overwritten. The higher Android build is required for a normal in-place update.

The patch adds manual finance capture to the supported Web and native Android
clients. An authorized user can record an expense or manual collection with
the branch, date, category, amount, payment method, vendor or payer reference,
invoice reference, and note supported by the relevant entry type. Cash expenses
must resolve the exact open branch and terminal shift and produce one durable
drawer movement; retry, acknowledgement loss, another authorized user, and
reconnect must not duplicate that movement. Existing refund, void, permission,
tenant, audit, and accounting controls remain in force.

Cash manual collections, tip payouts and supplier payments follow the same
explicit-drawer rule. An authorized user may use an eligible same-branch drawer
even when another authorized user opened it; both actors remain attributable.
While the source drawer is open, the normal void/reversal path updates that
drawer once. After it closes, the saved closing cash is immutable. Web ERP then
offers a reasoned closed-shift correction: the original source remains in
history, and one full append-only reversal is dated now, posted in the current
reporting period and applied to a selected current same-branch drawer. Android
displays that corrected state and excludes it from active totals.

Expense entries can retain up to five private receipt files, each no larger than
10 MiB. Supported evidence is JPEG, PNG, WebP, or PDF. Android can take a photo
with the system camera or choose an existing file; Web can choose a camera photo
or file through the browser. The backend verifies the bytes rather than trusting
the filename or declared content type, stores immutable evidence, and records
append-only review history. Evidence is visible only through authenticated ERP
authorization. Google Sheets receives receipt status and safe metadata, never
the receipt bytes, private review notes, or customer personal information.

The patch also introduces the optional **ERP Mirror v1** Google Sheets backup.
PostgreSQL remains authoritative. Committed POS payments and refunds, membership
payments and refunds, manual expenses and their reversals, and manual
collections and voids produce append-only delivery events. The server signs
each request, retries transient failures from a durable outbox, quarantines
permanent failures for review, neutralizes spreadsheet formulas, and gives each
event a stable deduplication key. Exact cash, card, UPI, QR, and wallet amounts
are preserved where the source transaction provides them.

Every delivery event is permanently bound to the configuration generation that
existed when the transaction committed. Events recorded before verification are
held in PostgreSQL until a connection-test row from that exact generation is
delivered. A corrected unverified URL retains its held work and needs a fresh
test. After verification, URL/secret rotation and disconnect are refused while
that generation has pending, leased or quarantined business rows. Operators
must drain pending work and retry quarantined rows first. A superseded event is
never silently retargeted, and an idempotent replay returns its original event
instead of duplicating it in a later generation.

An unconfigured Sheet integration is disabled. Saving the deployed Apps Script
endpoint and secret in **Settings → Google Sheets** enables delivery and shows
**Configured · verification pending**; it is operationally accepted as
**Connected** only after a controlled connection-test event is delivered. It
writes only to the separate `ERP Mirror v1` tab. It does not edit the user's
existing accounting tabs, import Sheet edits into the ERP, backfill old records
automatically, or replace database backup and restore.

Code30.2 preserves Code30.1 gaming, POS, shared authorized-user operations,
pricing, customer lookup, playtime, deletion-replay protection, and updater
behavior. Rewards and WhatsApp messaging remain inactive. The archived
Capacitor clients and native iOS distribution remain outside the supported
release scope.

## Evidence required before tagging

The exact final source must pass a fresh migration from the released database
state through `0078`, the complete backend unit and integration suites, Web
lint/typecheck/tests/build, Android JVM tests for every release variant, release
lint/build, API-35 instrumentation, and the repository release contracts. The
frozen-source hash ledgers must then be generated from those exact reviewed
bytes; they are deliberately not populated while functional changes are still
in progress.

The business trial must cover manual cash and non-cash entries, camera and file
receipt upload, failed-upload retry, app restart, offline/reconnect behavior,
cross-user operation on the same authorized terminal, reversal/void behavior,
closed-shift correction in a current drawer/reporting period, configuration
verification and rotation/drain rules, and exact Sheet event reconciliation
without duplicate rows or personal data. It must also repeat the existing
shift, Gaming-to-POS, billing, split-payment,
refund, cash reconciliation, and cleanup checks so this patch cannot regress
Code30.1.

After source acceptance, only the protected `v3.1.29` GitHub workflow may
produce the distributable direct APK. Verify its package, build, version,
SHA-256, size, release manifest, and independently preserved signer, then prove
an in-place same-signer upgrade from signed `v3.1.28` / build `36` without
clearing app data. A local APK or emulator build is test evidence only.

Backend/Web migration, inactive APK staging, owner activation, and the Google
Apps Script deployment are separate coordinated production steps. None is
implied by a green source suite or signed APK. Physical Redmi Pad 2 acceptance,
printer behavior, vendor battery management, and a real shop-day run remain
separate acceptance evidence whenever the tablet is unavailable.
