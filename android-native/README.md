# D Company ERP — native Android staff app

This is the supported native Android client for D Company ERP. It is a Kotlin,
Jetpack Compose and Room application; it does not embed the web ERP in a
WebView.

## Application identity

| Field | Value |
| --- | --- |
| Package / application ID | `cloud.dcompany.erp` |
| Version name | `3.1.3` |
| Version code | `14` |
| Minimum compatible client code | `8` |
| Minimum Android version | Android 8 (`minSdk 26`) |
| Target Android version | Android 15 (`targetSdk 35`) |
| Production API | `https://dcompany.duckdns.org/api/v1/` |

Release builds are pinned to the production HTTPS API. A debug build can point
to `localhost`, `127.0.0.1`, or the Android emulator host `10.0.2.2` by passing
`-Pdcompany.debugApiBaseUrl=...`; that override cannot redirect a release
build.

## Release status

Version `3.1.3` (`14`) is the manual-install partner candidate for this rollout.
It advances Room from 36 through 40 to protect the employee-owned Support
outbox, immutable offline Gaming item actions, and the canonical receipt-history
cache. It also establishes authenticated installation heartbeat/update-event
reporting and recoverable verified-APK preparation for later server-delivered
updates. It still requires exactly one server-confirmed Hybrid Gaming + POS
workspace for the active shop. The signed code-`12` to code-`13` in-place
upgrade evidence preserves the predecessor path, but code `14` is not a release
until its own signed same-lineage upgrade and API-35 gates pass. Those gates do
not replace the full authenticated workflow or physical Redmi Pad 2 acceptance.
Nothing from Android release preparation deploys the backend or web ERP. Before
the APK is manually handed to the partner, the coordinated production
deployment must migrate the server database through Alembic revision `0057`
and any later release-head migration, then pass the production smoke test.

Android client code `8` remains the minimum-compatible floor. Preserve the
signed `3.1.1` (`12`) and `3.1.2` (`13`) predecessors as immutable artifacts.
Code `14` is installed manually and must not advertise itself as an update to
code `14`. The first server-driven successor is `3.1.4` (code `15`) and must be
a newly signed immutable APK with a verified HTTPS
URL, SHA-256, byte size, package, version, and expected signer. The server may
offer that future artifact only after the stage-only operator checks and a
protected owner activation re-verifies its public bytes. Android will still
require the employee to approve installation. Do not raise the minimum merely
because the manual baseline or a future optional release exists.

Do not give a build to café staff until all automated gates are green, a signed
artifact has been verified, and the staff workflow in
[`docs/ANDROID_STAFF_GUIDE.md`](../docs/ANDROID_STAFF_GUIDE.md) has passed on the
actual target tablet.

GST validation is intentionally outside the current Android acceptance scope.
The app displays server-authored invoice totals and tax fields, but the current
release check must not be described as GST or tax-compliance certification.

## Implemented operations

The native app now supports the operational day rather than POS browsing only:

- authenticated login, token refresh, password recovery, role-based navigation,
  protected-owner step-up, and automatic one-shop workspace selection (the
  backend still enforces its branch/terminal scope without exposing unnecessary
  terminal controls when only one workspace is active);
- shift open and close, opening float, staff opener identity, cash-denomination
  counting, collection/refund breakdown, variance, and shift history;
- direct POS sales, quantities, authoritative checkout totals, cash/UPI/card,
  cash tendered/change, receipts, held-order settlement, and retry protection;
- table rounds with quantities and special requests, release to KDS, required
  cancellation reasons, Kitchen acknowledgement, and `Send to POS`;
- KDS ticket progression (`New` → `Preparing` → `Ready` → `Served`), cancellation
  acknowledgement, refresh/recovery controls, keep-screen-on, and `Exit KDS`;
- PS5, VR, simulator, streaming, and shisha session timers, stop/cancel actions,
  alarm reminders, and unpaid `Send to POS` hand-off;
- durable Room caches and outboxes, offline status/feedback, automatic retry
  while the app is running or when it is next reopened, reconnect
  reconciliation, and duplicate-payment guards;
- inventory, menu/pricing, customers, memberships, events, refunds, finance,
  reports, analytics, staff, settings, access control, and protected audit-log
  screens, subject to the signed-in role's permissions; and
- contextual **Help** from authenticated screens, with user-entered issue
  details, allowlisted screen/action/error context, an explicitly selected and
  privacy-reviewed screenshot, durable offline retry, and visible owner replies.

Roles intentionally do not all receive the same navigation. A missing screen can
be correct least-privilege behaviour; do not broaden a role merely to make its
menu resemble an owner's menu.

## Operational flow

1. Sign in, confirm the single shop workspace, then open a shift with the
   physical opening float. Internal branch/terminal ownership remains automatic
   and is shown only when an operator needs to resolve a configuration problem.
2. For seated service, take and customise rounds in **Tables**, send each round
   to **Kitchen**, progress it in **KDS**, then use **Send to POS** when the bill
   is ready.
3. For gaming/lounge service, start and stop the session in **Gaming**, verify
   the calculated duration/amount, then use **Send to POS**.
4. In **POS**, select the matching held order, verify the final total, and take
   cash, UPI, or card. Direct counter sales can be billed in POS immediately.
5. Watch offline/sync notices and alarms throughout the shift. Never collect a
   payment twice because confirmation is delayed.
6. Resolve active sessions, unpaid held orders, pending cancellations, and
   rejected offline work. Count the physical drawer by denomination, close the
   shift, wait for server confirmation, and only then sign out.

The full staff procedure and recovery rules are in
[`docs/ANDROID_STAFF_GUIDE.md`](../docs/ANDROID_STAFF_GUIDE.md).

## Architecture and safety boundaries

- Room is the UI's durable local source of truth. The sync engine fills caches
  and drains typed outboxes; it does not erase captured money actions on a
  routine network failure.
- Order and payment retries use stable identities and idempotency protection.
  A pending or ambiguous payment must be reconciled, not recreated.
- Branch, terminal, user and company still form the internal local-cache scope,
  even when the one-workspace UI hides redundant terminal controls. Workspace
  reassignment and sign-out are guarded when unresolved work could be stranded.
- Shift resolution is terminal-specific. POS and gaming must not reuse an open
  shift from another branch or till.
- Operational alarms use private lock-screen notifications and, when available,
  exact allow-while-idle scheduling with a distinct inexact backup for later
  permission revocation. A boot/package receiver reactivates only the exact
  cached user/company/branch/terminal scope before rebuilding reminders;
  sign-out cancels every scheduled and visible operational alert.
- Money is represented as integer paise end-to-end. Final prices, discounts,
  taxes, rounding, and membership effects are confirmed by the backend.

## Build and verification

Use JDK 17 and the checked-in Gradle wrapper:

```bash
cd android-native

./gradlew \
  testDebugUnitTest \
  compileDebugKotlin \
  compileDebugAndroidTestKotlin \
  lintDebug \
  assembleDebug

# Requires a running Android emulator or connected test device.
./gradlew connectedDebugAndroidTest
```

For an isolated backend on the host machine, build the emulator APK with:

```bash
./gradlew \
  -Pdcompany.debugApiBaseUrl=http://10.0.2.2:8788/api/v1/ \
  assembleDebug
```

That property is for test builds only. Never distribute a debug APK or use a
debug API override as evidence that the production endpoint works.

## Release signing and distribution

Android accepts an update only when its application ID and signing lineage
match the installed app. This module deliberately retains
`cloud.dcompany.erp`; a previously installed supported native build must be
updated with that same signing lineage. The archived Capacitor wrapper uses a
different application ID and is not an upgrade source or release target.

`keystore.properties` and the keystore are gitignored. Keep them outside source
control and back them up securely. A local `assembleRelease` without the
properties can produce an unsigned artifact; it is not distributable. The
repository release workflow is the supported path because it runs release
tests, lint, emulator instrumentation and signature verification before
staging the APK/AAB, checksums, and release manifest in a draft. Publication is
a separate post-migration approval step.

See [`docs/DISTRIBUTION.md`](../docs/DISTRIBUTION.md) for the signed GitHub
release process and [`docs/PLAY_INTERNAL_TESTING.md`](../docs/PLAY_INTERNAL_TESTING.md)
for the controlled Google Play internal-test rollout.
