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

Construct each plan from freshly inspected UI trees, not guessed positions.
Assertions fail at the first missing/disabled control and preserve evidence.
Offline state is restored even on failure. `restart` genuinely force-stops ERP
and launches it again while this separate test process survives.

## Evidence limits

A green driver run proves only the exact executed steps on the named device.
Independently reconcile resulting session/order/payment/shift IDs and amounts
against the database and web before accepting the business workflow. A cloud
Lenovo or Samsung tablet does not prove behavior on the partner's Redmi Pad2.
Accessibility text insertion does not by itself prove the hardware keyboard,
IME layout, lock-screen alarms, reboot recovery, or in-place signed upgrade.
Those require separate explicit checks; never label them passed automatically.
