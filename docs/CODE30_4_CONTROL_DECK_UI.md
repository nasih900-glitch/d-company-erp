# Code30.4 control-deck visual patch

This patch applies the approved black, navy and brass references to the
existing Android ERP. The original references are
`docs/design/code30-4-gaming-eight-stations.png`,
`docs/design/code30-4-session-payment.png`, and
`docs/design/code30-4-pos-shift-customers.png`. The approved Gaming
layout adapts the original gold duration dial so the station board and dial are
visible together at the target tablet width. Every amount, status, customer
and action comes from the ERP; illustrative mockup data and product imagery are
not inserted into live screens.

- Gaming, POS, Shift and Customers use a full-width content area with a
  five-position bottom bar: four daily modules and a permission-filtered More
  menu. Other ERP modules keep the existing sidebar. Connection, saved-work,
  Help and account controls remain in the top bar.
- Gaming keeps the eight real stations and the station control pane on the
  same screen. At tablet width the compact station board sits beside the large
  luminous gold duration dial; selecting a station updates the pane without
  opening a new page. Available stations show their published duration and
  price, mode, customer and player controls and Start action. Active and
  payment-due stations show their applicable stop, extend, participant,
  transfer, billing and recovery actions in that pane. At narrower widths the
  two regions stack in one scrollable screen. The dial selects only published
  package durations and stored prices; it never derives a tariff from dial
  position. Simultaneous permission, cleanup and action warnings have their own
  bounded scroll area so they cannot push the board and dial off a compact
  tablet.
- POS uses a product catalogue and current-order pane. Product names and
  prices come from the synced menu; Android does not currently receive product
  photos, so it uses an icon fallback. Split-tender entry remains in the
  verified payment flow. Existing validation and atomic payment submission
  remain authoritative.
- Shift shows actual cash, UPI, net collection, drawer expectation and close
  readiness in a single summary. The guarded close and recovery steps remain
  authoritative; the example figures in the mockup are not seeded into ERP.
- Customers keeps the searchable saved directory available offline and shows a
  separate online leaderboard ordered and paginated by the server's completed
  playtime ranks. Its `Recorded visits` column comes from settled purchase
  visits, not a guessed count of gaming sessions; older servers leave it blank.
  Unknown or unavailable ranks are labeled rather than inferred from the local
  directory. Rewards and WhatsApp messaging remain disabled.

The visual design uses static color and gold light trails, restrained focus
borders and local press feedback rather than continuous background animation.
The leaderboard makes paginated calls to the existing read-only endpoint, whose
response gains an additive recorded-visits field. No new backend route,
database migration, permission or financial calculation is introduced.
Production acceptance still requires the signed update's normal release gates
and a real-tablet check; emulator frame measurements alone do not prove
performance on the Redmi Pad.

## Build 41 acceptance

This patch targets `v3.1.33` / Android build `41`. The build-40 runbook is
historical evidence, not an instruction to offer build 40 in its place.

1. Require green CI for the exact reviewed commit, including full Android
   instrumentation, backend/Web contracts, source freeze and release version.
2. Tag that commit and obtain the protected, established-signing-key direct
   APK. Verify package ID, version code/name, APK hash and signing certificate
   against the release manifest. A local debug or physical-audit APK is not an
   in-place business update.
3. On an isolated emulator, install the signed build actually used by the
   tablet fleet, save offline test data, then install build 41 over it without
   uninstalling. Confirm Room and pending work survive, sync once, and verify
   Gaming, POS, Shift and Customers controls at 1280×800 and 960×600.
4. Before offering it to the partner, check the current live server and tablet
   versions, clear real operational blockers through normal workflows, confirm
   the tablet has no pending work, and perform the same in-place upgrade and
   screen/shift/session smoke test on the Redmi Pad. Only then activate the
   optional update in Web Settings. Signing, staging and offering are separate
   actions.
