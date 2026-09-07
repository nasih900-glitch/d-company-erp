# Isolated whole-application device audit

This test-only application runs UiAutomator in a **different process** from ERP.
It operates the installed `cloud.dcompany.erp.physicalaudit` APK through visible
Android accessibility controls. It does not call ViewModels, inject successful
responses, replace the repository, or mock financial mutations.

Use only independently seeded synthetic tenants. Supply a JSON workflow and
synthetic credential JSON to `/sdcard/Download/` on a disposable emulator/cloud
tablet. Neither file is packaged or checked in. The instrumentation entry point
is `BusinessWorkflowDeviceTest#completeBusinessWorkflow`; optional arguments
`auditPlan` and `auditCredentials` select those files. Credentials are removed
in `finally`. The driver never targets the production application ID.

The `physicalAudit` variant accepts a test-only HTTPS tunnel via
`-Pdcompany.physicalAuditApiBaseUrl=https://<temporary>.trycloudflare.com/api/v1/`.
The underlying server must use an isolated synthetic database. Release and
directRelease remain pinned to production and are not affected by this value.
The audit APK is debug-signed, has a distinct application ID, and cannot be
mistaken for the signed partner update.

For Firebase Test Lab, install the driver with `--app`, its Android test APK
with `--test`, and the real physicalAudit APK with `--additional-apks`. Pull
`/sdcard/Android/data/cloud.dcompany.erp.auditdriver/files/business-audit` for
per-step screenshots, accessibility trees, timings, and frame statistics.

Each plan step has `name`, `action`, and a visible selector where applicable:
`text`, `textContains`, `textRegex`, `description`, `descriptionContains`, or
`class`; `index` disambiguates matching controls. `fill` uses a literal test
value or `valueKey` such as `users.0.email`. `click` can wait for a `then`
selector. Other supported actions are `wait`, `absent`, `back`, `home`,
`scroll`, `launch`, `restart`, `offline`, `online`, `capture`, and `idleFrames`.
`idleStability` records the same start/mid/end visual and hierarchy evidence on
a deliberately static screen without pretending that a zero-frame renderer is
useful frame-timing evidence.
The release plan also contains one `alarmConstraints` action. It verifies a
real active-session AlarmManager entry, notification permission denial/regrant,
screen lock/wake, forced Doze entry/exit and battery-saver entry/exit, then
requires the alarm to remain registered. The following `restart` requires the
app to rebuild that alarm after force-stop/relaunch. These checks do **not**
prove that an alert was visibly/audibly delivered while the screen was locked
or while notification permission was denied.

`idleFrames` is reserved for live Gaming timers and writes correctly step-named
Android frame statistics plus start/mid/end screenshots and accessibility
hierarchies. Static Finance, Reports and settled-shift screens use
`idleStability`. The analyzer validates every normal screenshot/XML name
against the copied plan, rejects malformed or zero-sample frame windows,
rejects any frame over 250 ms, requires p95 frame completion at or below 50 ms
and at most five percent over 50 ms, and compares stable semantic bounds across
each idle window. Numbers are normalized for the layout comparison so timer
digits may change without hiding a banner/card jump.

Construct each plan from freshly inspected UI trees, not guessed positions.
Assertions fail at the first missing/disabled control and preserve evidence.
Offline state is restored even on failure. `restart` genuinely force-stops ERP
and launches it again while this separate test process survives.

## Evidence limits

A green driver run proves only the exact executed steps on the named device.
Independently reconcile resulting session/order/payment/shift IDs and amounts
against the database and web before accepting the business workflow. A cloud
Lenovo or Samsung tablet does not prove behavior on the partner's Redmi Pad2.
The runner also compares authenticated `/finance/pnl`, daily Reports and monthly
Reports responses to the independently verified fixture totals. The analyzer
then requires the visible Finance P&L amounts (revenue, COGS, gross and operating
profit) and the visible Reports KPIs (revenue, order count and net profit) to
match those dynamic results in the named UI hierarchies. Reports cash, UPI and
COGS are below the initial tablet viewport, so their exact proof remains the
authenticated API reconciliation rather than a false rendered-UI claim.

Accessibility text insertion does not by itself prove the hardware keyboard or
IME layout. A Firebase instrumentation process cannot survive a true device
reboot and cannot reproduce Redmi/HyperOS OEM battery policy, actual
lock-screen alarm delivery, or the user recovery experience after notification
permission denial; its `physicalAudit` APK is not the production-signed upgrade.
Those five target-device checks are written to `external-device-gates.json` and
`run-summary.json` as still required; never label them passed automatically.
