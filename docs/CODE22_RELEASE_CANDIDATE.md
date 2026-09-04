# D Company ERP 3.1.11 (code 22) release candidate

Code 22 is the Gaming Centre pricing and operational-reliability release. It
uses the exact owner-supplied tariff card dated 3 September 2026 and preserves
the server as the authority for sessions, bills, discounts, payments, shifts,
finance, audit history and cross-client synchronisation.

## Included tariff

- Standard single: 30 minutes ₹80; 1 hour ₹120; extensions ₹60/30 minutes and
  ₹100/1 hour.
- Standard dual: 30 minutes ₹100; 1 hour ₹150; extensions ₹70/30 minutes and
  ₹130/1 hour.
- Simdrive: 15 minutes ₹70; 30 minutes ₹100; 1 hour ₹180; no invented
  extension product.
- Premium single: 1 hour ₹150; extensions ₹70/30 minutes and ₹120/1 hour.
- Premium dual: 1 hour ₹190; extensions ₹90/30 minutes and ₹150/1 hour.
- A third or fourth PS5 player uses the dual tariff plus ₹30 per additional
  controller per started hour, with the printed ₹30 minimum.

Money remains integer paise. Each accepted package and extension is snapshotted
on the session so later catalog edits cannot rewrite a historical bill.

## Operational fixes

- Standard is selected by default; Premium is always explicit.
- Web and Android expose one-, two-, three- and four-player choices only where
  the selected tariff supports them.
- A 1-hour Standard single session plus its 1-hour extension is exactly ₹220.
- Held Gaming bills can be reviewed, discounted with permission, and settled by
  Cash or UPI without rebuilding the order.
- Shift opening rejects negative floats and concurrent duplicate open shifts at
  both API and PostgreSQL boundaries.
- Shift closure checks server and durable Android state for active/paused
  sessions, stopped sessions awaiting POS, unfinished bills and unresolved
  money work.
- Ending a web session requires an explicit confirmation and is protected
  against rapid duplicate taps.
- Package tier and price provenance survive Android offline storage, restart,
  sync and Room migration from code 21.
- Tariff application is an explicit, audited deployment action. An ordinary
  backend restart never changes prices, and unknown active PS5/Simdrive package
  rows fail the deployment closed for owner review.

## Release boundary

This source is a candidate until all local/CI gates, migration rehearsal,
signed-artifact verification, hosted-byte verification, production deployment
and physical-tablet acceptance pass. Only the exact signed APK and manifest
from the green immutable `v3.1.11` workflow may be staged for server delivery.
Code 21 must be upgraded in place; do not uninstall a tablet with pending
offline work.

GST remains deliberately outside this release's acceptance scope. Production
must stay explicitly `unregistered`; enabling GST requires a separate tested
discount/tax allocation release.
