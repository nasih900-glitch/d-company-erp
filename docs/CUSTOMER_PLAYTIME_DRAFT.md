# Customer playtime draft contract

Code29.2 release preparation assigns this source technical version `3.1.23`,
Android installation build `31`, and migration head `0072`. That coordinated
identity supersedes Code29.1 for this candidate only; it does not activate,
advertise, deploy, or offer the draft.

This Code29.2 draft candidate records one optional booking contact per gaming
session. A common Indian 10-digit, `91`, or `+91` phone representation can link
a new session to one live company customer. Blank, invalid, or ambiguous phone
identity never blocks billing and remains unlinked. Existing session identity
is stored by customer ID, so later phone edits do not move recorded play.
Every new Start also stores whether customer resolution linked or remained
unlinked. Only sessions migrated as historical may fall back to a finalized
order's stable customer ID; later exact-phone checkout cannot reattribute a
new invalid or ambiguous booking.

Once a POS order has a customer ID, that ID also controls membership pricing,
free-benefit reservations, points redemption, loyalty settlement, and refund
reconciliation. Its phone stays a receipt snapshot. Exact-phone lookup remains
only for older orders that have no stable customer link.

The Customers workspace shows completed `billable_minutes` as recorded play.
Draft qualifying paid play is deliberately narrower: the session must be ended,
financially paid, positively priced, linked to the same stable customer at
checkout, unrefunded, undiscounted, unvoided, outside membership or points
benefits, and outside unverified legacy billing. Package time is capped by its
captured package and extension duration. Historical sessions are included only
when an existing order already has a trustworthy customer ID; phones are never
used to backfill history.

Company settings default to 600 qualifying paid minutes for a draft estimate of
60 minutes. The API and database force `status=draft`, `rewards_enabled=false`,
and `messaging_enabled=false`. Screens label every result as a draft estimate.
There is no reward balance, grant, redemption, worker, provider credential,
send endpoint, or WhatsApp network call in this candidate.

Partner approval and a separate authorised implementation are required before
activation. That later design must define financial ownership, refund and
expiry policy, an auditable grant/redemption ledger, abuse controls, consent and
opt-out handling, provider credentials, delivery/retry receipts, privacy and
retention rules, monitoring, and a reversible staged rollout. Draft estimates
must not be converted into entitlements without an explicit reviewed migration.
