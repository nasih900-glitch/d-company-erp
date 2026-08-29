# D Company ERP Android 3.1.2 design QA

## Scope and evidence boundary

This is a visual and interaction-hierarchy review plus local current-source
release verification of the Android `3.1.2` (`versionCode 13`) directRelease
artifact on an API-35 emulator. It is not evidence that the artifact is
deployed to production, connected to production data, accepted on a physical
tablet, or ready for unrestricted cafe use.

The selected image is an art-direction and workspace-layout target. Its sample
station counts and billing states are illustrative; the implementation keeps
server-authoritative data instead of manufacturing matching values. The two
screens can therefore be compared for composition, hierarchy, density, colour,
typography and action design, but not for pixel-identical business content.

## Comparison target

- Source visual truth:
  `/Users/mohammednasih/.codex/generated_images/019f6149-4483-7750-9466-b04063d78502/exec-e912a2ac-7753-475e-a6fa-9251ad67e4c0.png`
- Available-state implementation:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-gaming-shift-open.png`
- Active-state implementation:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-gaming-active.png`
- Login implementation:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-login-local.png`
- Full-view comparison:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/reference-vs-android-3.1.2.png`
- Active-session comparison:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/reference-vs-android-3.1.2-active.png`
- Payment-due Gaming state:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-gaming-payment-due.png`
- Cash and UPI payment receipts:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-gaming-cash-receipt.png`
  and
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-gaming-upi-receipt.png`
- Canonical receipt history and server detail:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-canonical-receipt-history.png`
  and
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-canonical-gaming-receipt-detail.png`
- Drawer-count defect and corrected flow:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-before-close.png`,
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-latest-before-count.png`,
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-cash-count-dialog.png`,
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-cash-count-balanced-dialog.png`
  and
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-balanced-before-close.png`
- Shift-close confirmation, success and closed history:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-close-confirmation.png`,
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-closed.png`
  and
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-3.1.2-shift-closed-history.png`
- Final signed in-place upgrade, before and after:
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-final-upgrade-before-3.1.1.png`
  and
  `/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-final-upgrade-after-3.1.2.png`

## Viewport and normalization

- Source pixels: `1586 x 992`.
- Android capture pixels: `2560 x 1600`, API 35, 320 dpi.
- Android logical viewport: `1280 x 800 dp` in tablet landscape.
- Comparison images: the Android capture was proportionally normalized to
  `1586 x 992`; the source and normalized implementation were then placed
  together in a `3172 x 992` composite. There is no device bezel in either
  comparison half.
- This normalization preserves the shared 16:10 aspect ratio. Differences
  caused only by the source's generated-image raster and Android's 2x density
  are not treated as product defects.

## States inspected

- Logged-out login screen with empty fields and disabled submit action.
- Authenticated Gaming board with eight available stations and an open shift.
- Authenticated Gaming board with one active timed session and an open shift.
- Shift-required state with the start action disabled and a next-step label.
- Alarm-permission warning state.
- Offline state before and after an in-place app update, including one visible
  item waiting to sync.
- Payment-due station with a saved drink, clear combined amount, action-centre
  summary, item-level Void and dominant Send to POS action.
- Cash and UPI payment-success receipts, including line items, round-off, paid
  total and cash tender/change where applicable.
- Canonical receipt list and server receipt detail with Gaming provenance and
  employee attribution.
- Shift reconciliation before count, drawer-count dialog, balanced count,
  irreversible-close confirmation, closed success and closed-shift history.
- Fresh signed in-place upgrade from `3.1.1` (`versionCode 12`) to final
  `3.1.2` (`versionCode 13`), including private-data persistence and successful
  post-update process launch.

## Full-view comparison findings

No actionable P0, P1 or P2 visual defect is visible in the captured login,
available-session or active-session states.

- **Information architecture and layout:** the implementation carries the
  reference's compact left navigation, slim page header, station workspace and
  persistent command pane into a native tablet composition. The five-metric
  strip and resource-type filters are intentional operational additions. The
  selected station remains visually connected to its command pane through the
  gold outline and matching title. No overlap, clipping or unintended
  horizontal overflow is visible at `1280 x 800 dp`.
- **Density and rhythm:** the station floor remains information-dense without
  reducing touch targets. Three station columns plus the persistent command
  pane give the selected task more space than the earlier four-column card
  grid. The alarm notice can push the lower station row below the fold, but the
  command action remains reachable and the board is visibly scrollable. The
  payment-due state preserves this density while making the amount, saved item,
  review banner and Send to POS path immediately scannable.
- **Typography:** Android uses the native system family instead of copying the
  concept's display face. Weight, size and line-height establish a clear order:
  page title, section title, station identity, timer or `Ready`, then rate and
  explanatory copy. Money and timers use tabular numerals locally rather than
  applying them to every label. Text remains sharp and legible in the
  `2560 x 1600` raster capture.
- **Colour and tokens:** graphite/navy surfaces create depth through restrained
  borders rather than gradients. Gold is limited to brand, selection and
  primary emphasis. Green, amber and red are reserved for operational state,
  attention and destructive action. Statuses pair colour with an icon and text
  label inside the workspace; the compact online indicator also has an
  accessibility description and expands into a persistent labelled banner when
  connectivity fails.
- **Image and icon quality:** the real D Company logo is used on login and in
  the shell. Material icons are consistent in weight and scale. No placeholder
  illustration, emoji, CSS drawing or recreated logo is visible. The source
  does not require product photography or other custom raster assets.
- **Copy and content:** `Gaming Centre`, `Station floor`, `Station command`,
  `Open POS shift to start`, `Add drinks & snacks` and `Stop & calculate` state
  the operational consequence of each action. The server-authority note beside
  the live estimate correctly distinguishes a running estimate from final
  billing.
- **Action hierarchy:** available, active and shift-blocked states expose only
  relevant controls. The active command pane gives add-ons the main neutral
  action, extension and transfer secondary actions, and stop a labelled red
  destructive action. The timer and current estimate remain above the actions.
  In the payment-due state, Send to POS is the dominant gold action while a
  blocked whole-session Void explains that the saved item must be handled
  first.
- **Receipts and history:** cash and UPI success dialogs distinguish confirmed
  tender, sold lines, exact subtotal, round-off and final paid amount. The cash
  receipt also exposes cash received and change. Canonical history keeps amount,
  payment method, station and paid state aligned in scan-friendly rows; the
  detail view separates Sale, Items, Gaming provenance and Payments rather than
  compressing audit information into one card.
- **Shift reconciliation:** expected drawer, gross/net collections and
  cash/card/UPI/other rails remain visible before counting. The corrected
  drawer-count flow moves all nine denominations into a focused dialog, keeps
  expected, counted and difference pinned above them, and keeps Cancel/Use
  drawer count reachable below the scrollable denomination list. Balanced state
  is shown both before confirmation and in closed history.

The original-sized comparison is sufficiently legible to inspect navigation,
tiles, typography, status pills and command actions, so no separate cropped
comparison was required. The active-session composite provides the additional
focused state comparison for the timer and command pane. The individually
opened payment, receipt and Shift captures provide focused evidence for the
workflow states that are not present in the art-direction image.

## Accessibility and interaction observations

- Header actions and the target-tablet compact shell use `48 dp` controls; the
  station tiles and command actions are also comfortably touchable in the
  capture.
- Selected, available, active, blocked and destructive states use labels and
  icons as well as colour. The selected tile also has a gold outline.
- Login fields retain visible labels, large input surfaces and an explicit
  password-visibility control. The disabled sign-in state is visually distinct.
- The drawer dialog exposes large increment/decrement controls, editable count
  fields, live expected/counted/difference feedback and anchored actions. Its
  denomination content is scrollable instead of being compressed by the main
  Shift card's remaining height.
- The current-source Android JVM matrix passed `715/715` tests independently
  for debug, release and directRelease. The focused API-35 device run passed
  `48/48` tests. These checks support, but do not replace, human accessibility
  testing.
- No TalkBack exploration, large-font/text-scaling run, switch-access run or
  colour-vision user test was captured for this candidate. No current keyboard-
  open login/dialog screenshot is part of this visual evidence set.

## Comparison history

### Baseline: Android 3.1.1

Evidence:
`/tmp/d-company-erp-release-3.0.1/design-qa-assets/dcompany-before-3.1.1.png`

- **Earlier P1 - weak station-to-action focus:** the grid repeated full actions
  across cards and did not keep a selected station command area visible.
- **Earlier P2 - dense shell competing with operations:** the wide identity,
  search and status clusters reduced the station workspace at this tablet
  width.
- **Earlier P2 - warning hierarchy was too aggressive:** the timer-permission
  warning used a large danger-red surface even though billing remained safe.

### 3.1.2 fixes and post-fix evidence

- Replaced the repeated full-card workflow with selectable station tiles and a
  persistent command pane.
- Changed the target-width shell to a labelled compact rail and icon actions
  while keeping accessible descriptions and full-size targets.
- Rebalanced typography so the station name, state, timer and estimate scan in
  operational order.
- Changed the alarm warning to a restrained bordered attention surface and kept
  the two recovery actions explicit.
- Added a stable summary strip and clear type filters without introducing fake
  metrics.

Post-fix evidence:
`reference-vs-android-3.1.2.png` and
`reference-vs-android-3.1.2-active.png`. Reinspection found no remaining P0,
P1 or P2 issue in the states those composites actually cover.

### Operational E2E finding and correction

- **P1 found - drawer denomination controls collapsed inside the Shift card.**
  In `dcompany-3.1.2-shift-before-close.png`, the inline denomination region
  inherited only the main card's remaining height. At the target landscape
  size this compressed the count workflow, exposed only part of the
  denomination set and made accurate close-of-shift entry unreliable.
- **Root fix:** the main Shift card now presents one explicit `Count cash` or
  `Edit cash count` action. It opens a dedicated, bounded dialog whose
  denomination grid scrolls independently. Expected, counted and difference
  remain visible above the grid; Cancel and Use drawer count stay anchored
  below it. Counts are applied to the main screen only when the operator
  confirms the dialog.
- **Post-fix visual evidence:**
  `dcompany-3.1.2-shift-latest-before-count.png`,
  `dcompany-3.1.2-shift-cash-count-dialog.png`,
  `dcompany-3.1.2-shift-cash-count-balanced-dialog.png` and
  `dcompany-3.1.2-shift-balanced-before-close.png` show all denominations,
  the exact balanced total and the applied main-screen state without clipping.
- **Close retest:** `dcompany-3.1.2-shift-close-confirmation.png` shows the
  counted amount and irreversible-action warning;
  `dcompany-3.1.2-shift-closed.png` gives explicit balanced success; and
  `dcompany-3.1.2-shift-closed-history.png` records the opener, count,
  collection total and payment split.

## Isolated Gaming-to-close E2E result

The evidence was generated against the isolated emulator/backend environment,
not production.

1. PS5 Station 1 produced a `₹23.34` Gaming charge plus one `₹60.00` Cola Can.
   The `₹83.34` subtotal rounded by `-₹0.34` to a `₹83.00` cash receipt with
   `₹83.00` tendered and `₹0.00` change.
2. PS5 Station 2 produced a `₹3.34` Gaming charge. It rounded by `-₹0.34` to a
   `₹3.00` UPI receipt.
3. Shift reconciliation showed `₹86.00` gross, `₹0.00` refunds and `₹86.00`
   net: `₹83.00` cash, `₹0.00` card, `₹3.00` UPI and `₹0.00` other.
4. The drawer was counted to the expected cash-only `₹83.00`, producing a zero
   difference and `balanced` state. The shift then closed through an explicit
   confirmation and success dialog.
5. Post-close isolated assertions reported zero active Gaming sessions, zero
   pending Gaming payments, zero unpaid orders and zero open shifts. The closed
   history retained `₹86.00` net collections with the `₹83.00` cash and `₹3.00`
   UPI split.

The cash and UPI success dialogs plus the canonical history/detail captures
show that the two sales remained individually itemised and retrievable rather
than being merged into one receipt.

## Current-source release and upgrade verification

### Source and automated gates

- The application source was clean at commit
  `f4bd7f1ceb2e` before the final build.
- Backend: `1121` tests passed.
- Web: `249` tests passed; typecheck, lint and production build also passed.
- Android JVM: `715/715` tests passed independently for debug, release and
  directRelease.
- Android device: `48/48` focused tests passed on API 35.
- `lintDebug`, `lintRelease` and `lintDirectRelease` passed. Android builds and
  the app bundle passed. A clean directRelease unit-test, lint and build run
  also passed.

### Final directRelease artifact

- Package: `cloud.dcompany.erp`.
- Version: `3.1.2` (`versionCode 13`).
- SHA-256:
  `355f24edb78e20fdc9cda635a87473d85a427d59d3eae8d0e51bfc4839e558eb`.
- Size: `16,081,876` bytes.
- Built: `2026-08-29T14:31:28+0100`.
- Signing-certificate SHA-256:
  `553081141804d5f71b2b04afb5ba9107e65df86147fa457c19bac948b7e78fe1`.
- Zip alignment verification passed.

### Fresh signed in-place upgrade

- A fresh `emulator-5554` installation of signed `3.1.1`
  (`versionCode 12`) was upgraded directly to the final signed `3.1.2`
  (`versionCode 13`) APK without uninstalling the app.
- `firstInstallTime` remained `2026-08-29 14:31:43`; `lastUpdateTime` became
  `2026-08-29 14:32:15`.
- A private app marker written before the upgrade remained present afterwards.
- The upgraded app launched, Android reported a running process ID, and no
  fatal exception was observed.
- Visual evidence:
  `dcompany-final-upgrade-before-3.1.1.png` and
  `dcompany-final-upgrade-after-3.1.2.png`.

This proves the final APK's local build identity, signing, alignment and
code-12-to-code-13 upgrade path on the disposable API-35 emulator. It does not
prove production deployment or migration, server-advertised update delivery,
live-backend recovery, or successful upgrade on a partner-owned tablet.

## Open questions and residual evidence gaps

- Production deployment and migration were not performed or verified in this
  pass.
- The server-advertised update flow, including authenticated discovery,
  download, integrity validation, user-approved installation and post-update
  recovery, remains unproven end to end.
- A future polish pass could make the compact online indicator discoverable to
  sighted users without relying on a tap or accessibility service, while
  keeping the header uncluttered.
- Recent station activity is present in the visual target but absent from the
  current active command pane. It is a P3 information-density enhancement, not
  a blocker for starting, extending, transferring, adding products or stopping
  a session.

## Physical Redmi Pad 2 limitation

No physical Redmi Pad 2 was available. The emulator used the same
`2560 x 1600` pixel raster at 320 dpi, but that does not certify the tablet's
actual font rasterization, panel scaling, refresh rate, frame pacing, touch
latency, software-keyboard behaviour, TalkBack behaviour, lock-screen alarms,
reboot recovery, notification denial or OEM battery-optimisation behaviour.
Those remain physical-device acceptance work.

## Result

The captured `3.1.2` login, available-station, shift-blocked, alarm-warning,
offline, active-session, payment-due, cash receipt, UPI receipt, canonical
receipt history/detail, cash-count, balanced-close and closed-history states
pass the scoped emulator visual and interaction-hierarchy review. The isolated
Gaming-to-close evidence reconciles `₹83.00` cash plus `₹3.00` UPI to `₹86.00`
net and finishes with zero active Gaming sessions, pending Gaming payments,
unpaid orders and open shifts.

The current source also passes the recorded backend, web and Android test/build
gates; the final directRelease APK identity, signer, alignment and fresh signed
emulator upgrade are verified above. Physical Redmi Pad 2 acceptance,
production deployment/migration and server-advertised update delivery remain
outside this pass and are not represented as proven.

final result: passed
