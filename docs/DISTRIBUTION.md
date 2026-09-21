# Web & Android Distribution — D Company ERP

D Company currently supports the hosted web ERP and the native Android app.
iOS and desktop installers are outside the release scope.

## Release paths

The web ERP at `https://dcompany.duckdns.org` is deployed through the existing
VPS/Docker Compose procedure in `docs/DEPLOY_LIVE.md`. An Android GitHub Release
does not deploy the web application.

The early direct-release identities and their failure or supersession records are
immutable history. Do not rebuild, move, reuse, relabel, stage, or activate any
of those tags or APKs. Their candidate ledgers remain the authoritative record
for the release attempt they describe.

Code30.2 `v3.1.29` / Android build `37` at commit
`3ea84be4718a794d5a2e8efc7ac9bcacbc0cee01` is the immutable predecessor for
this patch. Preserve its exact source, manifest, checksums, signer and APK
bytes. The current Code30.3 candidate is `v3.1.30` / build `38`, Room schema
`52`, and Alembic head `0079`:

```
freeze and verify exact 3.1.30/build-38 source
        │
        ▼
backend + Web + Android suites and full business trial
        │
        ▼
tag, protected CI build/sign, independent artifact verification
        │
        ▼ same-signer in-place upgrade from exact v3.1.29/build 37
coordinated backend/Web migration and authenticated production smoke
        │
        ▼
update/reconnect tablet; owner reviews the exact stale-session candidate
        │
        ▼
stage the same APK inactive; owner activation remains a separate action
```

A source version, local APK, green emulator run, signed GitHub draft, production
deployment, staged registry row, active offer, and physical-tablet acceptance
are separate gates. None may be inferred from another. Code30.3 remains a source
candidate until every preceding gate has recorded evidence. Its scope is in
[`CODE30_3_PATCH_CANDIDATE.md`](CODE30_3_PATCH_CANDIDATE.md). Code30.2 evidence
remains unchanged in
[`CODE30_2_PATCH_CANDIDATE.md`](CODE30_2_PATCH_CANDIDATE.md).

The patch preserves Code30.2 manual finance, private receipt evidence and
generation-bound Sheet outbox behavior. It adds exact, owner-reviewed recovery
for a stale Android Gaming overlay. The build-38 tablet must reconnect, apply
the matching directive and acknowledge it. There is no broad clear-all or
remote edit of an offline Room database.

The Tauri desktop and iOS projects are not built or published by the supported
release workflow.

Code30.3 also treats backend, frontend, Caddy, PostgreSQL and Redis as five
locally built release images. The protected CI and release workflows build the
exact source twice, attest the immutable image IDs, exercise runtime health,
retain SBOMs and scan each image. The narrowly scoped zlib OpenVEX correction
described in [`CODE30_2_PATCH_CANDIDATE.md`](CODE30_2_PATCH_CANDIDATE.md) may
filter only the one exact fixed zlib finding for the exact scanned image. It
does not relax the High/Critical failure gate for any other package or CVE.
Production repeats the same five-image identity and scanner checks before it
stops writers or opens ingress.

Earlier release ledgers remain historical provenance. The signed but superseded
Code 29 ledger is in [`CODE29_RELEASE_CANDIDATE.md`](CODE29_RELEASE_CANDIDATE.md),
and the signed but superseded Code 28 ledger is in
[`CODE28_RELEASE_CANDIDATE.md`](CODE28_RELEASE_CANDIDATE.md). The signed but
superseded Code 27 ledger remains historical in
[`CODE27_RELEASE_CANDIDATE.md`](CODE27_RELEASE_CANDIDATE.md), and the signed but
superseded Code 26 ledger remains historical in
[`CODE26_RELEASE_CANDIDATE.md`](CODE26_RELEASE_CANDIDATE.md). The rejected Code
25 ledger remains historical in
[`CODE25_RELEASE_CANDIDATE.md`](CODE25_RELEASE_CANDIDATE.md). The inherited
Code 24 scope and audit evidence remain historical in
[`CODE24_RELEASE_CANDIDATE.md`](CODE24_RELEASE_CANDIDATE.md) and
[`CODE24_PRODUCTION_AUDIT.md`](CODE24_PRODUCTION_AUDIT.md); none is proof for a
Code30.3 artifact or rollout.

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
CURRENT_RELEASE_VERSION=3.1.30

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

That command validates the coordinated Code30.3 build-`38` identity; it does
not authorise tagging, publishing, advertising, registering, staging, or
activating the artifact. Every earlier tag and signed artifact remains
immutable. In particular,
Code30.2 `v3.1.29` / build `37` is the exact same-channel predecessor for this
release. Tag `v3.1.30` may be created only after every product-version field,
Room schema, migration head, final test result and source-freeze record agree.
Code30.3 uses build `38`; never reuse build `37` or rebuild `v3.1.29` with new
bytes. Isolated debug installations are test evidence, not distribution.
Never use a blanket version replacement: dependency versions and Android
compatibility policy intentionally differ from the product version.

The workflow rejects a release unless all of these are true:

- it runs from a Git tag using the `v<version>` format;
- the tag without `v` exactly matches Android `versionName`;
- `versionCode` is a direct positive integer; and
- generated APK metadata matches the source version.

Play Console additionally requires every uploaded `versionCode` to be greater
than the last published one; the repository cannot verify Play's remote history,
so increment it for every release. A manual workflow dispatch must target an
existing tag. Dispatches from branches are rejected.

## Version-code-8 floor, immutable predecessors, and Code30.3 candidate

Version `3.0.7` with version code `8` introduced the terminal and Gaming-to-POS
contract that older clients do not understand, so code `8` remains the
minimum-supported compatibility floor. An optional Code30.3 offer does not
authorize changing that floor.

Code30.2 `v3.1.29` / build `37` is the current immutable direct-channel release
predecessor. Code30.3 is `v3.1.30` / build `38`, Room `52`, and migration `0079`.
It is not signed, deployed, staged, active, offered, installed, or approved by
the version bump alone.

### Current Code30.3 rollout sequence

1. Finish the exact source review, clean full suites, migration proof and
   business trial described in `CODE30_3_PATCH_CANDIDATE.md`.
2. Freeze the reviewed source and create `v3.1.30` only after all local gates
   pass. Build and sign only through the protected tagged workflow.
3. Verify the exact CI APK and manifest: package, build `38`, version `3.1.30`,
   byte size, SHA-256, source revision and independently preserved signer.
4. On an isolated emulator, install exact signed `v3.1.29` / build `37`, retain
   representative Room/outbox state, and install the signed build `38` with
   normal update semantics. Do not uninstall or clear data.
5. Deploy the matching backend and Web source through the guarded production
   installer with a quiesced backup, restore proof, migration through `0079`,
   rollback readiness and authenticated smoke checks. The old one-time
   Code30.2 cleanup bridge is predecessor history and is not a substitute for
   this fresh preflight.
6. Stage the same verified APK inactive. Staging must not advertise an offer.
7. The bound owner release-controller may activate the exact staged candidate
   only after the preceding evidence passes. Android still requires the user to
   accept the installer; physical-tablet acceptance remains separate.
8. After the tablet user accepts build `38`, reconnect the affected tablet.
   In Web Gaming, review the exact station/session/amount/duration/hash
   candidate, approve it with a reason, wait for tablet acknowledgement, and
   confirm the station becomes available. There is no broad clear-all and Web
   cannot edit an offline Room database.

### Historical Code 14 rollout record (do not execute for current releases)

The following sequence is retained to explain prior provenance. It is not the
current Code30.3 rollout procedure; use the current sequence above.

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
install or offer. Original signed Code `29` (`3.1.19`) is immutable after its
installer lock gate failed. The `v3.1.20` run was cancelled before build or
signing. Code30.1 `v3.1.28` / build `36` is immutable signed history.
Code30.2 `v3.1.29` / build `37` is immutable predecessor evidence. Code30.3
`v3.1.30` / build `38` requires its own complete gates and protected
workflow output. After final-source trials, signed in-place upgrade continuity,
and production verification, only the bound owner release-controller may
activate the staged offer. The offer reaches all eligible direct-channel
clients; there is no per-device targeting. Further staff installations require
owner coordination after supervised target-device acceptance passes.
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
must not be staged or activated. Code30.1 build `36` and Code30.2 build `37`
are immutable predecessor history. Code30.3 build `38` must first pass the
complete backend migration/test,
Web lint/typecheck/test/build, Android release lint/JVM/instrumentation/build,
and signature-verification gates. The resulting
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
