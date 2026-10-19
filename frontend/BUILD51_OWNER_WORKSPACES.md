# Build 51 Web owner workspaces

The Web keeps its existing sidebar, routes, brand artwork and Day/Night palette.
New Audit and receipt detail are inline; opening the existing Shift workspace
inside Gaming/POS does not unmount their station/customer/cart controls. Financial
Open/Close still uses the shared command, permission checks and confirmations.

## Protected state and correction recovery

Audit unlock tokens, password inputs and loaded protected history remain in memory.
Audit and receipt state is scoped to company/user/branch, with a session generation
fence so late callbacks/unmount cleanup cannot restore it after logout. Applied
Audit filters, visible-ID snapshot, loaded older rows, selection and scroll survive
source navigation. New activity uses the server's activity cursor; it never
replaces loaded rows automatically. Expired snapshots require an explicit restart.

Correction recovery uses one localStorage journal per company/user/branch. It
contains only version, scope, operation, exact nonsecret commit body and action
key. Passwords and purpose-bound unlock tokens are never stored. Changing accounts
or signing in never executes the journal. A fresh owner confirmation is required,
then the owner must explicitly recover the saved result. One password check returns
a purpose-bound action token plus a separate history-only token, avoiding a second
authentication attempt solely to load retained receipts. The body and key remain
unchanged after network loss, token expiry, in-progress requests or mismatched
receipts. A newly discovered other-tab request is displayed for explicit recovery;
it is never silently sent by a different bill's Confirm button.

The Web Locks API serializes reservations and submissions across tabs. Supported
current browsers provide it over HTTPS/localhost. Without this capability or
working recovery storage, correction submission is blocked with an explanation;
ordinary ERP reads and existing workflows continue. Server-side source guards,
immutable receipts, permission ceiling and durable idempotency remain authoritative.
Only an explicit review of a recognized final domain rejection permits a fresh
preview; unknown conflicts, 401/403, in-progress or missing replies keep the journal.

## Financial and history presentation

Receipt method filters use effective payment method. The receipt preserves original
Cash/tender/change separately from effective UPI and the correction timestamp.
Configured fractional tax rates are formatted exactly (0.18 = 18%); actual charged
tax and stored totals are unchanged. Bill investigation includes retained player
intervals/one charge per identity, confirmed/voided add-ons, payments, correction
links, refunds and provenance limits. Missing legacy proof is labelled, not inferred
from today's mutable catalogue.

Shift retains original recorded collections, expected cash, count and variance.
Identified-bill reconstructed Cash/UPI/expected amounts are labelled separately.
Reports show unallocated daily classification and unresolved actuals separately
from gross payments and refunds. Mixed-rail daily attestation covers Cash/UPI only;
other recorded rails are unchanged, not claimed to have been independently counted.

Device cards say last reported app build and historical queue when stale. Incident
detail separates unverified device occurrence from server receipt in explicit UTC.
No received incidents does not establish that no crashes occurred.

## Verification scope

Component regressions cover StrictMode, lost-response replay, expiry, logout,
network-stale retained Audit filters/history, cross-tab pending identity, exact
receipt matching, tax fractions, server filter scope and honest telemetry labels.
Actual synthetic API/browser evidence lives outside the repository under the task
runtime. It is separate from final coordinated release, signed upgrades, crash/
resume checks and physical-tablet acceptance; this Web slice is not a release claim.
