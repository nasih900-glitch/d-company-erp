# Code30.4 control-deck visual patch

This patch applies the approved black, navy and brass design direction to the
existing Android ERP. It does not replace the operational screens with the
illustrative mockup: every amount, status and action still comes from the real
POS, Gaming, Shift and Customers state.

- The existing 68 dp workspace header now carries a gold destination icon and
  title on Gaming, POS, Shift and Customers. Keeping the same header height
  protects the station board, checkout and shift-close viewport on smaller
  landscape tablets.
- Gaming's station and command panels use restrained gold focus and active
  borders. The start-session touch dial selects only published duration/package
  choices and their stored prices; it cannot invent a price.
- POS product cards highlight on press. Once a valid split tender is entered,
  a read-only allocation bar shows the selected methods and amounts. Existing
  fields, validation and atomic payment submission remain authoritative.
- Shift collection figures and the close panel have stronger visual grouping.
  Close eligibility, drawer entry and server reconciliation are unchanged.
- Customers shows a clearer saved-customer list and a top-three playtime
  leaderboard. Reward estimates and WhatsApp messages remain disabled.

The visual design uses static color and borders, without ongoing glow effects
or background animation. It does not add API calls, database migrations,
permissions or new financial calculations. Production acceptance still requires
the signed update's normal release gates and a real-tablet check; emulator frame
measurements alone do not prove performance on the Redmi Pad.

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
