# Web & Android Distribution — D Company ERP

D Company currently supports the hosted web ERP and the native Android app.
iOS and desktop installers are outside the release scope.

## Release paths

The web ERP at `https://dcompany.duckdns.org` is deployed through the existing
VPS/Docker Compose procedure in `docs/DEPLOY_LIVE.md`. An Android GitHub Release
does not deploy the web application.

The `3.1.3` (`14`) partner rollout is deliberately manual:

```
verified signed 3.1.3/code-14 directRelease APK
        │
        ▼ after release-head migrations + production smoke
owner sends that exact APK directly to the partner
        │
        ▼
partner approves the normal Android installer prompt
```

Do not tag, publish, host, register, or server-advertise code `14` as part of this
rollout. In particular, do not copy its APK into `releases/android`, publish a
GitHub/Play release, or create an Android release-registry row for it.

Code `15` (`3.1.4`) is the first identity allowed by the server-release registry,
but it is a held audit build. Preserve any signed artifact and manifest exactly;
do not rebuild, overwrite, or activate it. Code `16` (`3.1.5`) is the immutable
predecessor for upgrade proof. The current server-delivery rollout starts with a
distinct immutable code-`17` artifact:

```
bump to 3.1.6/code 17, then tag v3.1.6
        │
        ▼
GitHub Actions: backend + web + Android gates
        │
        ▼
newly signed native Android APK + verified release manifest
        │
        ▼
immutable controlled HTTPS release URL
        │
        ▼ guarded stage-only tool verifies bytes and registers staged row
explicitly bound release controller activates after a second public-byte verification
        │
        ▼
employee approves Android's installer prompt
```

The Tauri desktop and iOS projects are not built or published by the supported
release workflow.

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

The release workflow runs both third-party emulator automation and the Gradle
build without signing secrets. The build emits an exact checksummed unsigned
handoff, then a separate fresh runner verifies that handoff before it receives
the keystore. No Gradle process or third-party action runs in that signing job.
It uses the pinned Android 35.0.0 signing tools, verifies every APK/AAB against
the expected fingerprint, and removes the keystore before inspecting or
uploading signed artifacts. GitHub Releases are created with the runner's `gh`
CLI so a third-party publishing action cannot rewrite verified artifacts.

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
CURRENT_RELEASE_VERSION=3.1.6

# Update the coordinated product version in:
# - android-native/app/build.gradle.kts (versionName and a new versionCode)
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

That command validates the coordinated code-`17` identity; it does not authorise
publishing or activating the artifact. Commit and tag `v3.1.6` only after every
product-version field is coordinated and the full release gates pass. Keep the
manual code-`14` baseline untagged and unadvertised, and keep the code-`15` audit
identity immutable and held. Never use a blanket version replacement: many
dependency versions and the Android rollout policy intentionally differ from
the product version.

The workflow rejects a release unless all of these are true:

- it runs from a Git tag using the `v<version>` format;
- the tag without `v` exactly matches Android `versionName`;
- `versionCode` is a direct positive integer; and
- generated APK metadata matches the source version.

Play Console additionally requires every uploaded `versionCode` to be greater
than the last published one; the repository cannot verify Play's remote history,
so increment it for every release. A manual workflow dispatch must target an
existing tag. Dispatches from branches are rejected.

## Version-code-8 floor, code-14 baseline, code-15 audit, and code-17 candidate

Version `3.0.7` with version code `8` introduced authoritative terminal
purposes (`cafe_pos`, `gaming`, and `hybrid`) and the explicit Gaming-to-POS
handoff. Older Android clients do not understand that contract and can select
the wrong local shift or attempt an invalid local handoff, so code `8` remains
the minimum-supported compatibility floor.

The signed `3.1.2` APK with version code `13` is the preserved predecessor used
to prove the supported in-place upgrade. Keep its exact bytes and signing
lineage; do not rebuild it under the same identity or advertise it through the
server update API.

The signed `3.1.3` direct-release APK with version code `14` is the manual
partner baseline for this rollout. It keeps the internal tenant/branch/terminal
safety model while adding the refined Gaming command workspace, canonical
receipt history, reliable real-time refresh, and Room schema 40. It may be sent
directly to the partner only after the production backend reaches the
repository's release-head migration and the production smoke test passes. It is
not a hosted or server-delivered release: do not publish it to GitHub or Play,
copy it into the server release directory, or register it through the update channel. Physical
Redmi Pad 2 acceptance remains a separate post-install gate.

Code `11` first introduced the verified in-app direct updater. The manually
installed code-`14` app is now the update-capable baseline. Code `15` is the
first version accepted by the immutable server-release registry, but `3.1.4`
code `15` is a held audit build and is not the current activation target. The
signed `3.1.5` code-`16` artifact is the immutable predecessor. The current
server-delivery candidate is a newly built and signed `3.1.6` APK with version
code `17`. An optional offer exists only after that exact artifact is staged and
explicitly activated.

Treat the app and backend as one coordinated release:

1. Preserve the exact signed version-code-13 predecessor and version-code-14
   partner APKs, verify their signer, and never replace either immutable
   identity with changed bytes.
2. While the old backend is still active, bring any previously installed app
   online and confirm its offline queue is empty. Do not uninstall an app with
   pending work.
3. Keep the verified version-code-14 APK private and unadvertised. Do not host
   it on the VPS, GitHub, or Play while preparing the production migration.
4. Back up the database and follow the `0056` maintenance sequence below:
   stop writers, upgrade only through `0055`, review and apply the explicit
   terminal consolidation, and only then upgrade through `0056` to the
   repository's release head. Verify the database crosses revision `0056`
   safely and reaches the current head before starting the backend. Deploy with
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
6. For the current server-driven update, create `3.1.6` with version code `17`,
   sign it with the trusted lineage, verify an in-place upgrade from code `16`,
   and use `ops/stage_android_release.py` to publish it once at an
   immutable HTTPS URL and register a staged row. Only the exact company/user
   identity configured in `ANDROID_RELEASE_CONTROLLER_BINDINGS`, with
   `admin.system` and audit access, may activate it after review and the
   backend's second public-byte verification;
   Android still requires the employee to approve installation.

### Required production order for migration 0056

Migration `0056` intentionally refuses legacy split-terminal data; it never
chooses a keeper or rewrites production history. Do not run the generic
`alembic upgrade head` sequence against split data. Use this maintenance-window
order instead:

> **Do not use `docker compose -f docker-compose.prod.yml up -d --build` as
> the first release command.** The backend image's normal entrypoint
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

9. Only now may the normal auto-migrating startup run:

    ```bash
    docker compose -f docker-compose.prod.yml up -d --build
    ```

    Verify health, then smoke-test login, shift open, Gaming start/stop, add-on,
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
history; code `16` is the immutable predecessor and the current activation
target is `3.1.6` (`17`). Its immutable row may
be staged only after the distinct signed artifact and same-lineage upgrade proof
exist. Protected-owner status alone grants no global release authority: only
the exact company/user identity configured in
`ANDROID_RELEASE_CONTROLLER_BINDINGS`, with `admin.system` and audit access, may
activate it.

If an erroneous minimum must be rolled back, deploy the lower minimum together
with a strictly higher `CLIENT_COMPATIBILITY_POLICY_REVISION`. A code-`14`
tablet clears its persisted required-update block only after the public endpoint
returns a newer, non-cacheable `supported` policy that explicitly includes code
`14`; an equal/stale revision or an unreachable endpoint remains blocked.

## Android artifacts

Server-release registration begins at code `15`. For the current tagged
`3.1.6` (`17`) candidate, the Android job first reruns the complete backend
migration/test and web
lint/typecheck/test/build gates. It then runs Android release lint, JVM tests,
emulator instrumentation, and signature verification. It stages a draft
release containing:

- a signed `.apk` for controlled direct installation;
- a signed `.aab` for Play Console;
- `SHA256SUMS` for artifact integrity; and
- `release-manifest.json` with source revision, version, API base URL, and
  signing-certificate fingerprint.

Do not distribute an artifact for live café operation until the release
workflow is green and the device acceptance checklist has passed on the target
café tablet. Emulator proof supports candidate review but is not physical-device
acceptance.

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
