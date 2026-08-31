# Code 18 Android design QA

## Comparison target

- Source visual truth: `release-artifacts/d-company-erp-3.1.7-code18/evidence/source-code17-final-after-code12-upgrade-2560x1600.png`
- Selected visual specification: `docs/CODE18_STANDARD_PREMIUM.md`
- Rendered implementation: `release-artifacts/d-company-erp-3.1.7-code18/evidence/code18-login-idle-2560x1600.png`
- Full-view comparison: `release-artifacts/d-company-erp-3.1.7-code18/evidence/code17-vs-code18-login-comparison-5120x1600.png`
- Focused comparison: `release-artifacts/d-company-erp-3.1.7-code18/evidence/code17-vs-code18-login-focused-2048x1200.png`

The source and implementation are native Android app captures of the same logged-out,
idle login state. Both use a 2560 x 1600 pixel landscape viewport at 320 dpi. No
density normalization was needed. The side-by-side composites preserve each source
at its native pixel density before the viewer scales them for display.

The comparison target is intentionally evolutionary: preserve Code 17's proven
tablet composition and registered logo while replacing its gaming-centre-wide
identity with a calmer standard business identity and a more legible surface ladder.
The implementation is not intended to copy the web ERP or introduce a separate
layout language.

## Full-view comparison

The card position, form width, logo scale, content order, input heights, button
placement and overall whitespace remain aligned with the Code 17 source. Code 18
uses the approved navy, raised-surface and brass tokens without changing the
operational density or touch geometry. `BUSINESS OPERATIONS` correctly replaces
the global `GAMING CENTRE` subtitle while Gaming remains a module after login.

No P0, P1 or P2 composition, hierarchy, crop, overflow or density mismatch was
visible in the combined full-view comparison.

## Focused comparison

The focused login-card comparison was required because borders, text weights and
surface separation are too small to judge in the 5120-pixel-wide full view. It
confirms:

- **Fonts and typography:** the existing Android sans-serif family and scale are
  preserved; headings remain optically dominant without heavier entertainment
  styling; the new subtitle has restrained tracking and does not wrap.
- **Spacing and layout rhythm:** logo, heading, divider, fields, recovery action and
  CTA retain the source alignment and touch spacing. The slightly calmer corner
  treatment is consistent across the card and fields.
- **Colours and visual tokens:** Code 18 has a clearer background/surface/raised
  surface ladder, restrained brass emphasis and more visible control boundaries.
  There are no decorative gradients, neon effects or gaming-themed global colours.
- **Image quality and asset fidelity:** the registered raster logo is the same sharp,
  correctly scaled asset in both captures. It was not replaced by an approximation,
  generated asset, emoji, CSS drawing or handcrafted SVG.
- **Copy and content:** the operational login copy is unchanged except for the
  intentional global identity correction from `GAMING CENTRE` to
  `BUSINESS OPERATIONS`.
- **Icons and controls:** the email, lock and visibility icons remain from one
  consistent library, are aligned, and retain practical touch targets.

No actionable P0, P1 or P2 issue was found in the focused comparison.

## Additional rendered-state checks

- Keyboard/focus: `code18-login-keyboard-2560x1600.png`
- Authenticated shell: `code18-dashboard-authenticated-2560x1600.png`
- Gaming active state: `code18-gaming-active-2560x1600.png`
- Gaming payment-due state: `code18-gaming-payment-due-2560x1600.png`
- POS populated cart: `code18-pos-populated-cart-2560x1600.png`
- Cash payment dialog: `code18-payment-cash-dialog-2560x1600.png`
- UPI payment and receipt: `code18-gaming-upi-payment-2560x1600.png`,
  `code18-gaming-upi-receipt-2560x1600.png`
- Finance: `code18-finance-reconciled-2560x1600.png`
- Server failure/reconnect with stable content geometry:
  `code18-server-issue-stable-layout-2560x1600.png`,
  `code18-reconnected-stable-layout-2560x1600.png`
- Offline queue and post-sync state: `code18-offline-queued-session-2560x1600.png`,
  `code18-offline-session-synced-once-2560x1600.png`
- Text scaling at 1.3x: `code18-font-scale-1.3-2560x1600.png`,
  `code18-gaming-font-scale-1.3-2560x1600.png`
- Smaller 1920 x 1200 stress viewport and scrollable navigation recovery:
  `code18-stress-1920x1200.png`, `code18-stress-nav-scroll-1920x1200.png`

The target 2560 x 1600 viewport showed no clipped persistent controls. At the
smaller 1920 x 1200 stress viewport, the navigation intentionally becomes
vertically scrollable; a swipe exposed Finance, Reports, Staff, Audit Log and
Support Inbox with full-size targets. At 1.3x text scale the primary Gaming and
Dashboard content remained readable, scrollable and free of overlaps.

## Interaction and accessibility evidence

The rendered flow exercised login keyboard focus, navigation, shift state, Gaming
start/stop, session add-on, payment-due review, held-order selection, Cash and UPI
payment controls, receipt totals, Finance reporting, failure/recovery status,
offline queueing, rapid repeated start taps and reconnect synchronization. Status
changes use labels and icons in addition to colour. Important controls remain at
least 48 dp. The connection state changes inside a fixed header slot; the `Today`
anchor remained at `[256,248][332,281]` through Server issue, Restoring and Online
states, so the page did not jump when connectivity changed.

## Findings

- No actionable P0, P1 or P2 findings remain.
- P3 follow-up: validate OEM-specific font rendering, animation cadence and colour
  calibration on the partner's physical Redmi Pad 2 / HyperOS device. Emulator
  captures cannot prove panel calibration or OEM compositor behaviour.

## Comparison history

### Pass 1

- Source and implementation were opened in one full-view comparison.
- The full view showed no layout regression and confirmed the intended global-copy
  and surface-token changes.
- Important control details were too small to judge confidently, so the pass was
  not accepted from the full view alone.

### Pass 2

- A native-density focused crop of both login cards was created and opened as one
  comparison input.
- Typography, spacing, colours, asset quality, copy, icons and control boundaries
  were inspected at readable size.
- No P0, P1 or P2 mismatch was found. Additional authenticated, payment, Finance,
  offline, text-scale and smaller-viewport captures confirmed the shared system in
  operational states.

final result: passed
