# D Company ERP 3.1.12 (code 23) release candidate

Code 23 supersedes the unsigned Code 22 candidate. It retains the complete
Gaming Centre tariff and billing work from Code 22 and adds accountable,
permission-based shared-shift closing plus clearer recovery feedback on web and
Android.

## Shared-shift behaviour

- One active Hybrid workspace remains the authority for Gaming, POS and Shift.
- `pos.shift.close` is the close authority. The employee who opened the shift
  does not have to be present at closing time.
- The original `opened_by` identity remains unchanged. Every new close records
  an immutable `closed_by` identity, and Shift history shows both names.
- Role, company, branch and workspace checks remain enforced. Read-only staff
  cannot close a shift merely because they can view it.
- Closing still fails safely while an order is unfinished, a gaming session is
  active or paused, a stopped session is awaiting POS, a kitchen cancellation
  is unacknowledged, or a payment/refund recovery is unresolved.
- Each refusal names the shift opener, opening time and workspace and gives the
  next operational action. Repeated taps are blocked while an action is being
  saved.
- An interrupted open/close response is treated as unknown rather than failed.
  The web app refreshes the authoritative shift first and only permits the same
  closing count to be retried if the shift still shows open.
- A prepared POS bill cancelled on the server is not discarded locally unless
  its owned recovery draft is also cleared. A storage/other-tab conflict locks
  the checkout and tells staff how to reconcile it instead of showing a false
  success.
- Historical shifts closed before migration `0066` deliberately keep a null
  closer; the migration does not invent audit evidence.

## Inherited Gaming Centre money contract

- Standard single: 30 minutes ₹80; 1 hour ₹120; extensions ₹60/30 minutes and
  ₹100/1 hour.
- Standard dual: 30 minutes ₹100; 1 hour ₹150; extensions ₹70/30 minutes and
  ₹130/1 hour.
- Simdrive: 15 minutes ₹70; 30 minutes ₹100; 1 hour ₹180.
- Premium single: 1 hour ₹150; extensions ₹70/30 minutes and ₹120/1 hour.
- Premium dual: 1 hour ₹190; extensions ₹90/30 minutes and ₹150/1 hour.
- A third or fourth PS5 player uses the dual tariff plus ₹30 per additional
  controller per started hour, with the printed ₹30 minimum.

Money remains integer paise and the backend remains authoritative for tariff,
discount, bill, payment, shift and finance totals.

## Release boundary

Code 22 tag `v3.1.11` and its green unsigned evidence remain immutable history,
but its waiting signing job must be cancelled rather than approved. Only the
exact signed APK and release manifest produced together by the green immutable
`v3.1.12` workflow may be staged for server delivery.

Before activation, verify migrations through `0066`, complete backend/web/
Android gates, package identity `cloud.dcompany.erp`, code `23`, version
`3.1.12`, signing lineage, hosted byte hash and an in-place upgrade from the
partner's signed code-21 installation. Do not uninstall or clear app data.
Android still requires the partner to accept the installer prompt.

GST remains deliberately outside this release's acceptance scope.
