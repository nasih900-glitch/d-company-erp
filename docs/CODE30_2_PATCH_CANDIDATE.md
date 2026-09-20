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

## Production image security correction

The first hosted image scan of commit `efb19f45ec27c5c3a4d63698c73bee3a5d9f54b1`
passed the backend, Web and Android jobs but correctly blocked release on two
runtime-image findings: Python 3.13.15 `CVE-2026-82049` and Alpine zlib 1.3.2-r0
`CVE-2026-85091`. The candidate therefore moves the backend runtime and CI to
exact Python 3.14.7. Redis is now a fifth locally built and attested release
image rather than an unmodified upstream runtime.

All five Alpine runtime images build zlib 1.3.2 from the official source archive
SHA-256 `bb329a0a2cd0274d05519d61c667c062e06990d72e125ee2dfa8de64f0119d16`
and apply the complete upstream chain
`e3dc0a85b7032e98380dec011bc8f2c2ee0d8fca`,
`bbc2ccf3d0de267576b524b875c769a724a513b0`,
`df84af25dc1942490e1d1c899a07619152a46148`, and
`7235b0a581227c56a79a43ff828f8ef6794194c8`. Their vendored patch SHA-256
values are respectively
`183bc8b9dd078a41a62de5c2d905d9b0196b45bc46f100d7e4147ec228207c74`,
`7d00ee29be5e636d30da2890961e83e35cee0b152333ecd97a46ae2e71eb5d47`,
`110ff14375733173d8aa54574473424fbd7dfe4b81f1ca34a759c6fe14b15b14`,
and `96040ee84d0d187905283912dbd3f7b66ac2033976a2ceefe9b8cca63143d9c2`.
The patched `gzwrite.c` must be byte-identical to the final upstream commit.
Each build runs the upstream zlib tests and a final-image runtime probe that
proves the mapped shared object and its hash.

Alpine package metadata will continue to identify those corrected bytes as
zlib 1.3.2-r0 until the distribution publishes a replacement package. For that
single scanner mismatch, the release gate generates one image-specific OpenVEX
document per exact local image tag and immutable image ID. The disposition is
limited to `CVE-2026-85091` on the zlib APK PURL. The verifier requires exactly
one matching ignored result and rejects any other ignored, unfiltered, wrong
image, wrong tag or unresolved result. Every other High or Critical result
continues to fail the release.

These source controls are not release proof by themselves. The final commit
must still build all five images and pass both hosted Docker-store scanner lanes
with retained SBOM, Grype, VEX, image-identity and runtime evidence before the
release tag can be created.

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

The production cutover also includes the separately documented one-time trial
cleanup. It may run only after the verified backup/restore and migration to
`0078`, while every writer is stopped. The default path performs the complete
transaction and rolls it back. Apply mode must match the exact audited
allowlists and fingerprints, a fresh dry-run state fingerprint, a backup file
that it independently hashes and restores, the canonical tracked 18-AVD
quarantine evidence JSON that it independently hashes, the stopped backend
image revision, same-project `postgres` and `backend` Compose service labels,
no running backend service in that project, and a clean checkout at the exact
deployed source. The one durable receipt permanently fences the deleted
idempotency and shift-opening identities against delayed offline replay. See
[`CODE30_2_PRODUCTION_TRIAL_CLEANUP.md`](CODE30_2_PRODUCTION_TRIAL_CLEANUP.md).

The normal installer rule still requires every tablet outbox to be empty. The
only exception is the exact retired Code30.1 test-installation heartbeat
already present in this production database: one pending report on the pinned
row, from database head `0073`, while installing version `3.1.29`. The installer
accepts it only after verifying the immutable 18-AVD quarantine evidence at
SHA-256
`379c6368936d03223e19482cc840c2a9d2483dc9a96909fba22cd9f59911eec8`.
It does not change that row before the quiesced backup. The separately guarded
post-migration cleanup leaves that stale count and every other installation
field unchanged because the server UUID cannot be tied to an AVD. All 18 local
AVDs are wiped, and the installation heartbeat plus all 29 expired
remote-assistance keys remain immutable historical evidence. The cleanup
receipt records the unchanged snapshot with null sync and offline markers and
permanently fences the 13 exact known deleted actions. The archived clear Room
snapshot is ancillary and is not attributed to that server installation
identity.
