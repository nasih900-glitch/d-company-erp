# Server-driven Android updates

D Company uses a direct, signed-APK channel for the current partner pilot. The
server can offer an update, but it cannot silently install one: Android always
shows its package-installer approval screen. The compatibility contract uses
`X-Client-Distribution-Channel`: only `direct` clients can receive self-hosted
APK metadata. `play` and `managed` clients fail closed without that metadata.
Do not combine delivery channels on the active tablet fleet until a separate
Play/managed update contract and both signing lineages have been proven.

## Trust and authority boundaries

- GitHub Actions builds and signs the APK. The signing key and passwords never
  belong on the VPS.
- `ANDROID_EXPECTED_SIGNER_SHA256` is an independently preserved certificate
  fingerprint. Do not derive it from the candidate being approved.
- The VPS hosts versioned APK bytes under `/downloads/android/`. Published
  filenames are never overwritten or reused.
- The staging tool verifies the exact CI manifest, local APK identity, public
  HTTPS bytes and cache headers, then creates an immutable **staged** registry
  record. It has no activation, withdrawal, or minimum-version authority.
- Only an explicitly bound release-controller account reviews and activates or
  withdraws an already staged record in the ERP. The account must have
  `admin.system` and audit access, and its exact immutable company/user UUID pair
  must appear in `ANDROID_RELEASE_CONTROLLER_BINDINGS`. Protected-owner status
  alone is deliberately insufficient for this global, pre-login registry.
  Activation re-fetches and re-hashes the public APK before changing the one
  active release under a database lock.
- `ANDROID_MIN_SUPPORTED_VERSION_CODE` remains the separate mandatory-update
  policy. Staging and activation never raise or lower it.
- `CLIENT_COMPATIBILITY_POLICY_REVISION` is the monotonic generation for that
  mandatory policy. Increment it for every minimum change, including an
  emergency rollback; never decrement or reuse a revision.
- `ANDROID_UPDATE_ALLOWED_ORIGIN=https://dcompany.duckdns.org` pins registry
  URLs to the production origin and controlled `/downloads/android/` path even
  while no optional release is active.

There is deliberately no HTTP upload, signing, metadata-edit, or minimum-policy
endpoint. A compromised owner browser therefore cannot replace APK bytes,
change their identity, or lower the compatibility floor.

## Current rollout boundary

`3.1.3` (version code `14`) is the manual, update-capable partner baseline. Send
that exact signed APK to the partner only after the coordinated production
deployment and smoke test pass. Code `14` must remain unhosted, unregistered and
unadvertised; it is the package from which the server-delivery upgrade is
tested.

Code `15` (`3.1.4`) is the first identity accepted by the server-release
registry. It remains a held audit build and must never be activated as a
shortcut. Codes `16` (`3.1.5`) and `17` (`3.1.6`) remain immutable upgrade-proof
predecessors.

The approved server-delivery candidate is `3.1.7` (version code `18`). Only the
exact signed artifact and release manifest produced together by the tagged
GitHub Actions workflow may be staged. A local Gradle build or local evidence
bundle is not release authority, even when its package and signer are correct.

Keep the minimum-compatible floor at code `8` during the initial rollout. A
new build, a green workflow, a hosted APK, or a staged registry row is not
authority to change that floor.

## Release record

The release workflow's `release-manifest.json` is the source attestation. The
operator tool accepts that exact manifest and APK, then derives the backend's
strict record containing only:

- `version_code`
- `version_name`
- `channel` (`direct`)
- `update_url`
- `release_notes`
- `apk_sha256`
- `apk_size_bytes`
- `apk_signing_cert_sha256`
- `source_git_sha` (full lowercase 40-hex commit)
- `source_release_ref` (exactly `v<version_name>`)
- `source_workflow_run_id` (positive GitHub Actions run identifier)
- `source_workflow_run_attempt` (positive attempt number)

These fields are immutable after registration. The database retains staged,
active and withdrawn rows; withdrawal does not delete release history. The APK
limit is 512 MiB, matching the Android downloader. The owner API serializes the
64-bit workflow run ID as a decimal string so browsers cannot round it; public
compatibility responses deliberately omit CI provenance.

The public compatibility endpoint is:

```text
GET /api/v1/public/client-compatibility?platform=android&version_code=<installed-code>
```

The Android networking layer sends `X-Client-Distribution-Channel` on this and
every other request. A missing header retains the legacy direct-client contract;
an explicit `play` or `managed` header never receives a direct APK URL, hash,
size, signer, or 426 recovery payload.

When no release is active, it may return the compatibility floor without APK
metadata. When a release is active, URL, version name, hash, size and signer are
an all-or-nothing atomic contract and the response uses `Cache-Control:
no-store`. Every compatibility JSON response and 426 carries the same positive
`policy_revision`; the response header
`X-Client-Compatibility-Policy-Revision` must match it. This lets a tablet clear
a persisted required-update block only after a strictly newer, definitive
`supported` policy explicitly includes its installed code. Equal/stale policy
responses and network uncertainty remain blocked. The APK itself must return:

- `Content-Type: application/vnd.android.package-archive`
- `X-Content-Type-Options: nosniff`
- an exact `Content-Length`
- `Cache-Control: public, immutable, no-transform, max-age=31536000` (or longer)
- no redirect from its same-origin versioned URL

## Code 18 staging procedure

1. Confirm the code-`14` partner installation is signed by the trusted
   certificate, can check for updates, and has no pending offline work.
2. Coordinate the application at `3.1.7` / code `18`. Run the complete
   release workflow and obtain its signed direct APK and
   `release-manifest.json` from the same workflow run.
3. Download both files without renaming or modifying either one. First run a
   verification-only plan:

   ```bash
   python3 ops/stage_android_release.py \
     --manifest /secure/release-3.1.7/release-manifest.json \
     --apk /secure/release-3.1.7/d-company-erp-v3.1.7-direct.apk \
     --expected-signer-sha256 <trusted-code-14-certificate-sha256> \
     --release-notes "Standard-premium Android refinement and reliability hardening"
   ```

   This checks the manifest, byte size, SHA-256, package
   `cloud.dcompany.erp`, version code/name, and signing certificate using
   `apkanalyzer` and `apksigner`. It does not contact or change production.

4. Review the printed plan. Then stage the exact same inputs:

   ```bash
   python3 ops/stage_android_release.py \
     --manifest /secure/release-3.1.7/release-manifest.json \
     --apk /secure/release-3.1.7/d-company-erp-v3.1.7-direct.apk \
     --expected-signer-sha256 <trusted-code-14-certificate-sha256> \
     --release-notes "Standard-premium Android refinement and reliability hardening" \
     --ssh-key ~/.ssh/dcompany_erp \
     --apply
   ```

   The tool uploads to a random temporary name, verifies it on the VPS, uses a
   no-replace atomic rename, downloads and verifies every public byte, registers
   only a staged row through the backend's internal CLI, and writes an
   append-only attestation. A failure before registration can leave safe,
   unadvertised immutable bytes; it must never cause a release offer.

5. In the owner ERP release screen, compare version, release notes, SHA-256,
   size, signer and source evidence. Activate only the staged code-`18` row.
   The backend performs a second no-redirect public byte verification before the
   atomic status transition and records the owner action in the Audit Log.
6. On one code-`14` tablet, refresh the update check, download, install and
   reopen code `18`. Verify sign-in, shift, Gaming, POS settlement, offline queue
   recovery and finance reconciliation before wider partner rollout.

Do not activate an intermediate held build as its own update. Do not stage from an arbitrary
local Gradle build, a renamed APK, a different workflow run, or a candidate
whose signer merely matches itself.

## Owner activation and emergency withdrawal

Normal activation and withdrawal happen in the protected owner ERP, but only for
an account whose exact company/user UUID pair is configured in
`ANDROID_RELEASE_CONTROLLER_BINDINGS` and which still has `admin.system` and
audit access. An empty binding list denies everyone. A protected owner who is
not explicitly bound, and every normal staff session, must not see or invoke
these controls. `/api/v1/auth/me.release_control_access` is the authoritative UI
capability; role names alone are not. The control surface can change only
`staged`, `active`, and `withdrawn` status; it cannot upload, sign, edit metadata,
delete history, or change the minimum.

If the owner UI is unavailable, use the same authenticated protected API with a
fresh session for that configured release-controller identity. If an internal
backend CLI is provided, it is safe only when it calls the same transition
service and produces the same audit record. Never use direct SQL, edit a row,
rewrite the APK, or invent a second environment-variable activation path.

Withdrawal stops new offers but cannot uninstall an APK already installed.
Android does not normally allow a lower code to replace a higher code. A bad
active code-`16` release therefore requires withdrawal followed by a newly
signed artifact with a strictly higher code, such as code `17`. Do not lower the
compatibility minimum as a substitute for a fixed binary, and do not ask staff
to uninstall while offline work is pending.

## Minimum-version rollout

Activation is optional-update authority only. Keep
`ANDROID_MIN_SUPPORTED_VERSION_CODE=8` until all known active tablets have:

1. uploaded their offline queues;
2. installed and opened the chosen release;
3. completed authenticated operational smoke tests; and
4. reported the new installation/version telemetry without conflicts.

Changing the minimum is a separately reviewed production configuration change
with its own backup, deploy, compatibility probes and rollback plan. Increase
`CLIENT_COMPATIBILITY_POLICY_REVISION` in the same atomic deployment for both a
raise and a rollback. It is never performed automatically by the staging tool
or owner activation.

## Monitoring, retention and recovery

- The VPS runtime monitor validates active compatibility metadata, the exact
  local hosted bytes and public immutable headers.
- The external production monitor checks compatibility and public APK headers
  every five minutes and re-downloads/re-hashes the full advertised APK every
  six hours and on manual dispatch. A dormant channel downloads no APK.
- Release monitoring must alert on partial metadata, wrong origin/path,
  redirects, unsafe content type/cache policy, size/hash mismatch, missing
  local bytes, or an invalid compatibility status.
- Retain every APK, CI manifest, workflow provenance, signer fingerprint,
  staging attestation and audit event needed to reconstruct a release. Keep at
  least the active APK, the manual code-`14` baseline, the held code-`15` audit
  artifact and previous known-good installers in encrypted backup storage;
  never expose the signing key there.
- Test restoration of the registry, hosted bytes and attestations. Restoring
  metadata without the exact APK must fail closed rather than advertise a
  broken update.

Authenticated Android heartbeat writes are separately abuse-bounded. Redis
limits each server-derived company/user principal to
`CLIENT_HEARTBEAT_USER_LIMIT_PER_MINUTE` (default 30); rotating the client's
random installation UUID does not change that key. Redis failure returns 503,
and an exceeded window returns 429 with `Retry-After`. PostgreSQL admission
locks and triggers preserve hard immutable-ledger ceilings of 8 installations
per registering user, 32 per company, 1,000 update events per installation,
2,000 per actor and 10,000 per company. Exact installation and event retries
remain idempotent at capacity; new evidence receives a clear 409 and no history
is deleted. Alert on sustained heartbeat 429/409/503 responses and investigate
the protected owner device list rather than clearing rows.

Heartbeat platform, version code, and distribution channel must exactly match
the native request headers. An `upgrade_confirmed` event must exactly match the
installed version in that heartbeat. These checks detect ordinary client/server
drift; they are consistency evidence, not cryptographic device attestation.

TLS terminates at the existing production HTTPS edge. A CDN may be added later
only if it preserves exact bytes, HTTPS, `Content-Length`, immutable headers and
same-origin/no-redirect behavior expected by the app and monitors. It is not
needed for the initial one-tablet rollout.
