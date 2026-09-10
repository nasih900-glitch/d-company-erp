# Web & Android Distribution — D Company ERP

D Company currently supports the hosted web ERP and the native Android app.
iOS and desktop installers are outside the release scope.

## Release paths

The web ERP at `https://dcompany.duckdns.org` is deployed through the existing
VPS/Docker Compose procedure in `docs/DEPLOY_LIVE.md`. An Android GitHub Release
does not deploy the web application.

The historical `3.1.3` (`14`) partner rollout was deliberately manual. This is
retained as provenance, not as the current Code 29 delivery procedure:

```
verified signed 3.1.3/code-14 directRelease APK
        │
        ▼ after release-head migrations + production smoke
owner sends that exact APK directly to the partner
        │
        ▼
partner approves the normal Android installer prompt
```

Do not tag, publish, host, register, or server-advertise code `14`. In
particular, do not copy its APK into `releases/android`, publish a
GitHub/Play release, or create an Android release-registry row for it.

Code `15` (`3.1.4`) is the first identity allowed by the server-release registry,
but it is a held audit build. Preserve any signed artifact and manifest exactly;
do not rebuild, overwrite, or activate it. Codes `16` (`3.1.5`) and `17`
(`3.1.6`) are immutable predecessors. Tags `v3.1.7` / code `18`, `v3.1.8` /
code `19`, and `v3.1.9` / code `20` failed before signing and must not be reused.
Code `21` (`3.1.10`) is immutable signed predecessor history. Code `22`
(`3.1.11`) was superseded before signing and must not be approved or activated.
Code `23` (`3.1.12`) was also superseded without an authorised signed artifact.
Code `24` (`3.1.13`) failed before signing; its tag remains immutable history.
Code `25` (`3.1.14`) was rejected by its physical-tablet audit; its tag and any
artifact remain immutable history and must not be staged or activated. The
`v3.1.15` attempt failed before build/signing and produced no authorised or
distributed Code 26 artifact; its tag and evidence remain immutable. Code `26`
(`3.1.16`) was signed but its production install stopped before builds or
cutover, so that identity is now immutable. Code `27` (`3.1.17`) was signed but
its scanner gate stopped on a Syft scratch-mount permission error before
production deployment or cutover; its source, tag, and artifacts are immutable.
Code `28` (`3.1.18`) was signed, received owner maintenance authorization and an
isolated upgrade test, then failed its exact production-image identity gate
before the installer entered maintenance/cutover and before production or
partner installation, staging, or offer.
Its source, tag, manifest, and artifacts are immutable superseded history. Code
`29` (`3.1.19`) is the current, separately gated **unsigned** candidate:

```
coordinate source at 3.1.19/code 29 through migration 0071
        │
        ▼
local/CI backend + web + Android candidate gates
        │
        ▼
same-lineage Code 21 to Code 29 upgrade proof
        │
        ▼ stage exact CI artifact inactive for owner review
owner may offer to target tablet; supervised live trial gates wider rollout
```

No signed Code 29 artifact exists merely because local or CI candidate checks
pass. New protected signing approval, exact-artifact verification, production
deployment,
inactive staging, the owner's controlled target offer, and target-device
acceptance remain separate gates. None may be inferred from another.

The Tauri desktop and iOS projects are not built or published by the supported
release workflow.

The current scope and release gates are recorded in
[`CODE29_RELEASE_CANDIDATE.md`](CODE29_RELEASE_CANDIDATE.md). The signed but
superseded Code 28 ledger remains historical in
[`CODE28_RELEASE_CANDIDATE.md`](CODE28_RELEASE_CANDIDATE.md). The signed but
superseded Code 27 ledger remains historical in
[`CODE27_RELEASE_CANDIDATE.md`](CODE27_RELEASE_CANDIDATE.md), and the signed but
superseded Code 26 ledger remains historical in
[`CODE26_RELEASE_CANDIDATE.md`](CODE26_RELEASE_CANDIDATE.md). The rejected Code
25 ledger remains historical in
[`CODE25_RELEASE_CANDIDATE.md`](CODE25_RELEASE_CANDIDATE.md). The inherited
Code 24 scope and audit evidence remain historical in
[`CODE24_RELEASE_CANDIDATE.md`](CODE24_RELEASE_CANDIDATE.md) and
[`CODE24_PRODUCTION_AUDIT.md`](CODE24_PRODUCTION_AUDIT.md); they are not proof
that a Code 29 artifact has been signed or accepted.

## Android signing and Play Store setup

Generate the release keystore once and preserve it securely. Losing the key
prevents updates to the same Play Store application.

```bash
keytool -genkey -v -keystore release.keystore -alias dcompany \
        -keyalg RSA -keysize 2048 -validity 10000
```

Set these GitHub Actions secrets:

- `ANDROID_KEYSTORE_BASE64` — base64-encoded keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS` — normally `dcompany`
- `ANDROID_KEY_PASSWORD`

Also set the repository Actions variable
`ANDROID_EXPECTED_SIGNER_SHA256` to the 64-hex SHA-256 certificate
fingerprint from a previously trusted, installed direct APK (or from the
preserved release certificate). The fingerprint is public and is deliberately
stored independently from the keystore secrets. Do not populate it from an
artifact produced by the same workflow run: the check exists to reject a wrong
or substituted keystore and a first-install APK signed by an unexpected key.

The release workflow runs emulator automation and the Gradle build without
signing secrets. The build emits an exact checksummed unsigned handoff, then a
separate fresh runner verifies that handoff before it receives the keystore.
Only pinned GitHub-owned checkout, Java-setup and artifact-download actions run
before the key is decoded; no Gradle process or unsigned-build dependency runs
with signing material. The shell signer uses the pinned Android 35.0.0 tools,
verifies every APK/AAB against the expected fingerprint, and removes the
keystore before inspecting or uploading signed artifacts. GitHub Releases are
created with the runner's `gh` CLI so a publishing action cannot rewrite the
verified artifacts.

The workflow fails closed before producing signed artifacts when any signing
secret is absent.
Create the Play Console app with package name `cloud.dcompany.erp`, matching
`android-native/app/build.gradle.kts`. Play Store requires a Google Play Console
account; direct APK sideloading does not.

Building both artifacts does not mean they are interchangeable on an installed
tablet. The server currently advertises one Android update URL, and Google Play
App Signing may sign the delivered app with a certificate different from the
direct APK. Use one delivery channel for the active fleet, verify an in-place
upgrade from an app installed through that same channel, and do not raise the
minimum supported version until that proof passes. See
[`SERVER_DRIVEN_ANDROID_UPDATES.md`](SERVER_DRIVEN_ANDROID_UPDATES.md).

## Versioning each release

Choose one version and apply it consistently. For the current candidate:

```bash
CURRENT_RELEASE_VERSION=3.1.19

# Update the coordinated product version in:
# - android-native/app/build.gradle.kts (versionName; normally also a new
#   versionCode; the never-issued 3.1.15/code-26 exception is historical only)
# - backend/pyproject.toml
# - backend/app/__init__.py
# - frontend/package.json and frontend/package-lock.json
# - frontend/.env.example
# - .env.production.example (APP_VERSION only)
# - docker-compose.prod.yml (APP_VERSION fallbacks only)

# Keep mandatory compatibility policy separate. Staging or activating an
# optional release never authorises raising ANDROID_MIN_SUPPORTED_VERSION_CODE.
# Increment CLIENT_COMPATIBILITY_POLICY_REVISION for every reviewed minimum
# change, including rollback; never decrement or reuse it.

python3 scripts/verify_android_release_version.py --tag "v$CURRENT_RELEASE_VERSION"
```

That command validates the coordinated code-`29` identity; it does not authorise
tagging, publishing, advertising, registering, staging, or activating the
artifact. Tag `v3.1.7` is immutable rejected history and must never be moved or
reused. Tags `v3.1.8` and `v3.1.9` are also immutable rejected history. Tag
`v3.1.10` is immutable signed predecessor history. Tag `v3.1.11` is immutable
superseded history and its waiting signing job must not be approved. Tag
`v3.1.12` is immutable superseded unsigned history. Tag `v3.1.13` is immutable
failed-before-signing history. Tag `v3.1.14` is immutable rejected history. Tag
`v3.1.15` is immutable failed-before-build/signing history and must never be
moved or reused. Tag `v3.1.16` and its signed Code 26 artifact are immutable
history. Tag `v3.1.17` and its signed Code 27 artifacts are immutable after the
Syft scanner-gate failure. Tag `v3.1.18` and its signed Code 28 artifact are
immutable after the production-image identity failure. Tag `v3.1.19` may be
created only after every product-version field is coordinated and the local
release gates pass. Version code `26` was retained
only because 3.1.15 produced no signed, distributed, registered, staged,
offered, or production-installed direct artifact; every signed successor now
requires a strictly monotonic identity. Isolated debug installations are test
evidence, not distribution. Keep codes `14` through `28` immutable.
Never use a blanket
version replacement: dependency versions and
Android rollout policy intentionally differ from the product version.

The workflow rejects a release unless all of these are true:

- it runs from a Git tag using the `v<version>` format;
- the tag without `v` exactly matches Android `versionName`;
- `versionCode` is a direct positive integer; and
- generated APK metadata matches the source version.

Play Console additionally requires every uploaded `versionCode` to be greater
than the last published one; the repository cannot verify Play's remote history,
so increment it for every release. A manual workflow dispatch must target an
existing tag. Dispatches from branches are rejected.

## Version-code-8 floor, immutable predecessors, and Code 29 candidate

Version `3.0.7` with version code `8` introduced authoritative terminal
purposes (`cafe_pos`, `gaming`, and `hybrid`) and the explicit Gaming-to-POS
handoff. Older Android clients do not understand that contract and can select
the wrong local shift or attempt an invalid local handoff, so code `8` remains
the minimum-supported compatibility floor.

The signed `3.1.2` APK with version code `13` remains immutable historical
signing-lineage evidence. It must not be rebuilt under the same identity or
advertised through the server update API. Signed Code `21` (`3.1.10`) is the
actual installed predecessor that Code 29 must upgrade in place.

The signed `3.1.3` direct-release APK with version code `14` is historical manual
partner-baseline evidence. Its rollout policy required a coordinated backend
and physical-tablet smoke before manual delivery. It is not a current hosted or
server-delivered release: do not publish it to GitHub or Play, copy it into the
server release directory, or register it through the update channel.

Code `11` first introduced the verified in-app direct updater. Code `14` remains
historical update-capable baseline evidence, while signed Code `21` is the
current predecessor for Code 29. Code `15` is the
first version accepted by the immutable server-release registry, but `3.1.4`
code `15` is a held audit build and is not the current activation target. The
signed `3.1.5` code-`16` and `3.1.6` code-`17` artifacts are immutable
predecessors. Codes `18`, `19`, and `20` have no authorised artifact because
their tagged releases failed before signing. Code `21` (`3.1.10`) is immutable
signed predecessor history and is the required same-lineage upgrade baseline.
Codes `22` and `23` were superseded without authorised signed artifacts. Code
`24` failed before signing and its tag must not be moved or reused. Code `25`
was rejected by its physical-tablet audit and must not be staged or activated.
Code `26` (`3.1.16`) is immutable signed history after its production installer
stopped before builds or cutover. Code `27` (`3.1.17`) is immutable signed
history after its Syft scanner gate failed before production deployment or
cutover. Code `28` (`3.1.18`) is immutable signed history after its exact
production-image identity gate failed before the authorized installer entered
maintenance/cutover or any production/partner install or offer. The current
candidate is the unsigned `3.1.19` with version code `29`; its
database migration head is `0071`. It may
be staged only after the exact green tagged
workflow produces a signed artifact and becomes an optional server offer only
after authenticated owner activation. It is not currently signed, deployed,
staged, active, approved or partner-installable.

### Current Code 29 rollout sequence

1. Bring the signed Code 21 installation online and reconcile its exact pending
   outbox without clearing app data.
2. Build and sign `3.1.19` / code `29` only through the protected tagged
   workflow. Verify its package, version, exact bytes, SHA-256, manifest and
   independent expected signer.
3. Prove a same-key in-place Code 21 to Code 29 upgrade with Room data and
   offline work preserved. Do not uninstall or clear storage.
4. Rehearse the production-shaped migration through `0071`, then deploy the
   coordinated backend and web source with a fresh quiesced backup, restoration
   proof, rollback readiness and authenticated smoke tests.
5. Stage the exact verified CI artifact inactive and review it in the owner ERP.
   Staging does not advertise or activate an offer.
6. After all four preparation phases in `CODE29_RELEASE_CANDIDATE.md`, the owner
   may activate the exact staged candidate only after explicitly accepting
   channel-wide exposure. Every eligible direct-channel client can see that
   offer; there is no per-device allowlist. Coordinate the intended pilot
   tablet with staff. Android still requires employee approval to install it. Run the
   supervised real-live operational acceptance trial, authenticated
   physical-target smoke, offline/restart, alarm, performance and financial
   reconciliation after installation. The owner may ask other staff to install
   only after that trial passes; this restriction is operational, not enforced
   by the update endpoint.

### Historical Code 14 rollout record (do not execute for Code 29)

The following sequence is retained to explain prior provenance. It is not the
current Code 29 rollout procedure; use the current sequence above.

1. Preserve the exact signed version-code-13 predecessor and version-code-14
   partner APKs, verify their signer, and never replace either immutable
   identity with changed bytes.
2. While the old backend is still active, bring any previously installed app
   online and confirm its offline queue is empty. Do not uninstall an app with
   pending work.
3. Keep the verified version-code-14 APK private and unadvertised. Do not host
   it on the VPS, GitHub, or Play while preparing the production migration.
4. Rehearse the current database upgrade and downgrade path against a restored
   production dump. During the scheduled write outage, use the hardened
   installer—not the historical `0056` sequence below—and select the exact
   one-time Code14 provenance bridge observed in production:

   ```bash
   sudo bash infra/scripts/install-on-vm.sh dcompany.duckdns.org \
     --maintenance-confirmed \
     --legacy-code14-revision e5e90df5781e93681b8e9dcdd1ae9a6a5fb6a0b9
   ```

   The installer must prove the prior Code14 image/source/database identity,
   stop writers, verify the offline outbox is empty, create and restore-check a
   backup, preserve rollback images and configuration, apply migrations, and
   pass readiness before reopening ingress. Deploy with
   `ANDROID_MIN_SUPPORTED_VERSION_CODE=8`,
   `REQUIRE_NATIVE_VERSION_HEADERS=true`,
   `ANDROID_UPDATE_ALLOWED_ORIGIN=https://dcompany.duckdns.org`, and the last
   already-published Android compatibility policy. Do not advertise code `14`.
5. After the production smoke passes, install the exact private version-code-14
   package through Android's normal installer and run shift open/close, Gaming
   start/add item/stop/Send-to-POS, cash and UPI settlement, offline retry,
   finance reconciliation, and Support submission. Verify version `7` and
   older receive HTTP 426 before a write handler and version `8` remains
   compatible. The owner may then send that same APK manually to the partner;
   no optional release is active.
### Historical migration 0056 record (not a current deployment path)

Migration `0056` intentionally refuses legacy split-terminal data; it never
chooses a keeper or rewrites production history. Do not run the generic
`alembic upgrade head` sequence against split data. Use this maintenance-window
order instead. This records the already-completed Code14 maintenance decision;
do not replay it as a Code17 deployment procedure:

> **Do not start a production release with a direct Compose rebuild.** The backend image's normal entrypoint
> automatically runs `alembic upgrade head`, so it would reach `0056` before
> the reviewed terminal consolidation. The maintenance commands below override
> that entrypoint deliberately.

1. Confirm the running database is already at revision `0055` by querying its
   revision table directly. If it is older, stop this release and complete the
   earlier release's supported migration to `0055` first; do not mix that work
   into the one-Hybrid conversion.

   ```bash
   docker compose -f docker-compose.prod.yml exec -T postgres \
     psql -U erp -d erp -Atc "SELECT version_num FROM alembic_version"
   ```

2. While the current API remains available, build the new backend image
   without starting it. Inspect the company, branch, terminal, and operator
   user IDs and select the existing terminal whose identity will be retained:

   ```bash
   docker compose -f docker-compose.prod.yml build backend
   docker compose -f docker-compose.prod.yml exec -T postgres \
     psql -U erp -d erp -c \
     "SELECT c.id AS company_id, c.deleted_at AS company_deleted_at,
             b.id AS branch_id, b.deleted_at AS branch_deleted_at,
             t.id AS terminal_id, t.name, t.purpose, t.is_active
        FROM companies c
        JOIN branches b ON b.company_id = c.id
        LEFT JOIN terminals t ON t.branch_id = b.id
       ORDER BY c.id, b.id, t.is_active DESC, t.id"
   docker compose -f docker-compose.prod.yml exec -T postgres \
     psql -U erp -d erp -c \
     "SELECT id AS actor_user_id, name, email, status
        FROM users
       WHERE company_id = '<company-uuid>'
       ORDER BY name, id"
   ```

   Review active and archived branches. Revision `0056` requires exactly one
   active Hybrid terminal for each active branch, permits zero or one for an
   archived branch, and rejects every active non-Hybrid terminal.
3. Run the new image's consolidation command without `--apply` while the old
   app/API still serves staff. Save and inspect its JSON manifest:

   ```bash
   set -euo pipefail
   STAMP=$(date -u +%Y%m%dT%H%M%SZ)
   mkdir -p /root/backups
   COMPANY_ID=<company-uuid>
   BRANCH_ID=<branch-uuid>
   KEEPER_TERMINAL_ID=<keeper-terminal-uuid>
   ACTOR_USER_ID=<protected-owner-user-uuid>
   PREFLIGHT="/root/backups/hybrid-preflight-${STAMP}.json"
   set +e
   docker compose -f docker-compose.prod.yml run --rm --no-deps \
     --entrypoint python backend -m scripts.merge_terminals_to_one \
     --company-id "$COMPANY_ID" \
     --branch-id "$BRANCH_ID" \
     --keep-terminal-id "$KEEPER_TERMINAL_ID" \
     --keep-name "Main Workspace" > "$PREFLIGHT"
   PREFLIGHT_STATUS=$?
   set -e
   if [ "$PREFLIGHT_STATUS" -ne 0 ] && [ "$PREFLIGHT_STATUS" -ne 2 ]; then
     exit "$PREFLIGHT_STATUS"
   fi
   python3 -m json.tool "$PREFLIGHT"
   ```

   A refused dry run intentionally exits with status `2`. Resolve every
   reported open shift, unfinished order, running/unbilled Gaming session,
   refund, kitchen cancellation, or membership blocker through the normal
   audited app/API workflow, then repeat the dry run. Do this for every branch
   reported by the preflight, including archived branches retained for
   history. Never clear blockers with direct SQL.
4. Only after every preflight is clean, bring every installed app online and
   wait for every offline/sync queue to reach zero. Record that evidence, close
   the apps, and stop every public writer. The already-running database, Redis,
   and object store remain available to the one-shot maintenance containers:

   ```bash
   docker compose -f docker-compose.prod.yml stop caddy frontend backend
   ```

5. Rerun the dry run after writers are stopped and save a new final manifest.
   It must exit zero with no errors or blockers. If it refuses, make no changes:
   restart the stopped old containers with `docker compose ... start backend
   frontend caddy`, resolve the blocker normally, and repeat from step 3.

   ```bash
   FINAL_DRY_RUN="/root/backups/hybrid-final-${STAMP}.json"
   docker compose -f docker-compose.prod.yml run --rm --no-deps \
     --entrypoint python backend -m scripts.merge_terminals_to_one \
     --company-id "$COMPANY_ID" \
     --branch-id "$BRANCH_ID" \
     --keep-terminal-id "$KEEPER_TERMINAL_ID" \
     --keep-name "Main Workspace" > "$FINAL_DRY_RUN"
   python3 -m json.tool "$FINAL_DRY_RUN"
   python3 - "$FINAL_DRY_RUN" <<'PY'
   import json
   import sys

   manifest = json.load(open(sys.argv[1], encoding="utf-8"))
   if manifest["errors"] or manifest["result"] not in {"planned", "no_change"}:
       raise SystemExit("final consolidation manifest is not clean")
   PY
   ```

6. Take a custom-format database backup and prove it restores into an isolated
   database. Preserve the backup file and use its exact path as the audit
   reference during apply:

   ```bash
   set -euo pipefail
   STAMP=$(date -u +%Y%m%dT%H%M%SZ)
   BACKUP="/root/backups/pre-0056-${STAMP}.dump"
   VERIFY_DB="erp_restore_verify_${STAMP//[^0-9]/}"
   mkdir -p /root/backups
   docker compose -f docker-compose.prod.yml exec -T postgres \
     pg_dump -U erp -d erp -Fc > "$BACKUP"
   test -s "$BACKUP"
   docker compose -f docker-compose.prod.yml exec -T postgres \
     createdb -U erp "$VERIFY_DB"
   docker compose -f docker-compose.prod.yml exec -T postgres \
     pg_restore -U erp -d "$VERIFY_DB" --exit-on-error < "$BACKUP"
   docker compose -f docker-compose.prod.yml exec -T postgres \
     dropdb -U erp "$VERIFY_DB"
   ```

7. Extract the fingerprint from the final clean manifest. Apply only that exact
   reviewed state, with the operator UUID, reason, and verified backup path.
   The script writes this evidence transactionally into Audit Log:

   ```bash
   FINGERPRINT=$(python3 -c \
     'import json,sys; print(json.load(open(sys.argv[1]))["state_fingerprint"])' \
     "$FINAL_DRY_RUN")
   docker compose -f docker-compose.prod.yml run --rm --no-deps \
     --entrypoint python backend -m scripts.merge_terminals_to_one \
     --company-id "$COMPANY_ID" \
     --branch-id "$BRANCH_ID" \
     --keep-terminal-id "$KEEPER_TERMINAL_ID" \
     --keep-name "Main Workspace" \
     --apply \
     --actor-user-id "$ACTOR_USER_ID" \
     --reason "Approved one-Hybrid-workspace rollout" \
     --backup-reference "$BACKUP" \
     --expected-state-fingerprint "$FINGERPRINT"
   ```

8. Cross `0056` only through the overridden Alembic entrypoint, then verify the
   revision. Confirm every active branch has exactly one active terminal, every
   active terminal has purpose `hybrid`, the consolidation Audit Log row exists,
   and all retired terminal rows and historical shift/order/audit references
   remain present:

   ```bash
   docker compose -f docker-compose.prod.yml run --rm --no-deps \
     --entrypoint alembic backend upgrade head
   docker compose -f docker-compose.prod.yml run --rm --no-deps \
     --entrypoint alembic backend current
   ```

9. The historical Code14 release then used the old direct startup flow. Do not
    reuse that flow: all current production upgrades must run the hardened
    `infra/scripts/install-on-vm.sh DOMAIN --maintenance-confirmed` procedure
    from an exact reviewed commit, as documented in
    [`FREE_DEPLOY.md`](FREE_DEPLOY.md). Verify health, then smoke-test login,
    shift open, Gaming start/stop, add-on,
    Send to POS, cash and UPI settlement, receipt, Finance, sync recovery, and
    shift close on the retained Hybrid workspace. Install the exact private
    code-`14` APK through Android's normal installer for its authenticated app
    smoke; if code `13` is present, update it in place rather than uninstalling.
    Only after the production smoke succeeds may the owner send that same APK
    manually to the partner.

    Do not publish, host, or register code `14`. Confirm
    `/api/v1/public/client-compatibility?platform=android&version_code=14`
    reports no optional APK release. If the production smoke fails, do not send
    the APK.

Do not lower the compatibility minimum to keep an older APK operating against
this backend. Do not raise the minimum merely because an optional update exists.
Code `15` remains the server-registry admission floor and immutable held audit
history; codes `16` and `17` are immutable predecessors. Codes `18`, `19`, and
`20` have no authorised artifact or activation target. Code `21` is immutable
signed predecessor history. Codes `22` and `23` are unsigned, superseded
candidates and must never be activated. Code `24` failed before signing and
its tag remains immutable. Code `25` failed its physical-tablet audit and is
immutable rejected history; it must never be activated. Code `26` is the
immutable signed candidate whose production installer stopped before image
builds or cutover; it is superseded and must not be activated.
Code `27` (`3.1.17`) is immutable signed history after its Syft scanner gate
failed before production deployment or cutover. Code `28` (`3.1.18`) is
immutable signed history after the image-identity gate failed before the
authorized installer entered maintenance/cutover or any production/partner
install or offer. Code `29` (`3.1.19`) is the unsigned corrective
candidate and requires its own complete gates and new signing approval. After
its four
preparation phases, only the owner may activate an offer after accepting
exposure to all eligible direct-channel clients. There is no per-device
targeting. Further staff
installations require owner coordination after supervised target-device
acceptance passes; the public update endpoint does not enforce that sequence.
Protected-owner status alone
grants no global release authority: only the exact company/user identity
configured in `ANDROID_RELEASE_CONTROLLER_BINDINGS`, with `admin.system` and
audit access, may activate a separately approved future release.

If an erroneous minimum must be rolled back, deploy the lower minimum together
with a strictly higher `CLIENT_COMPATIBILITY_POLICY_REVISION`. A code-`14`
tablet clears its persisted required-update block only after the public endpoint
returns a newer, non-cacheable `supported` policy that explicitly includes code
`14`; an equal/stale revision or an unreachable endpoint remains blocked.

## Android artifacts

Server-release registration begins at code `15`. Codes `18`, `19`, and `20`
failed before signing and are never registered; Code `24` also failed before
signing and is immutable history. Code `25` is rejected and must not be
registered or offered. Signed Code `26` is immutable superseded history after
its deployment-only installer failure and must not be relabelled or reused.
Code `27` is immutable signed history and must not be staged or activated.
Code `28` is immutable signed history after its image-identity gate failure and
must not be staged or activated. Code `29` must first pass the complete backend
migration/test and web
lint/typecheck/test/build gates. It then runs Android release lint, JVM
tests, emulator instrumentation, and signature verification. The resulting
draft release contains:

- a signed `.apk` for controlled direct installation;
- a signed `.aab` for Play Console;
- `SHA256SUMS` for artifact integrity; and
- `release-manifest.json` with source revision, version, API base URL, and
  signing-certificate fingerprint.

Do not distribute an artifact beyond the controlled target-tablet offer until
the release workflow is green and supervised device acceptance has passed on
that tablet. Emulator proof supports candidate review but is not
physical-device acceptance.

## Download page

`download/index.html` checks the official repository's latest public GitHub
Release at runtime. It exposes an APK only when an asset matches the signed
workflow filename. If the check fails, the page links to the official Releases
page instead of guessing an installer URL.

When hosting the page with a Content Security Policy, allow
`https://api.github.com` in `connect-src`. Keep the bundled logo and favicon next
to `index.html`; do not enter release versions or download URLs by hand.

## If a release has problems

1. Mark the affected GitHub Release as a draft so it is no longer advertised.
2. Fix and verify the defect.
3. Increment `versionCode`, choose a new version/tag, and publish a replacement.
4. Notify affected staff to update.

There is no automatic rollback inside the installed Android app. Preserve the
previous signed APK until the replacement passes acceptance testing.

## Privacy and store requirements

The web ERP and Android app should link to the published privacy policy and
terms. Play Store also requires a data-safety form describing collected staff
and customer data.
