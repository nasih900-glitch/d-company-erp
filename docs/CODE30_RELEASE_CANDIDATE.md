# D Company ERP Code30 release candidate

Code30 is the cumulative public candidate containing the reviewed Code29.1
pricing correction, the Code29.2 customer-playtime draft, and saved-customer
lookup at gaming start. Its coordinated technical identity is tag `v3.1.24`,
Android `versionName=3.1.24`, Android `versionCode=32`, Room schema `47`, and
server migration head `0072`. Backend and Web package versions are `3.1.24`.

All earlier source tags, packages, manifests, and artifacts remain immutable.
At the last verified preparation snapshot, Code29.2 backend/Web version `3.1.23`
was deployed and Android build 31 was staged pending the user's offer action.
No Code30 record was active. This source ledger does not update that external
state.

## Customer selection contract

Web and native staff can search the full company customer directory while
online by saved name or phone. Native also searches its locally cached customer
directory while offline. Selecting a result sends its stable `customer_id` with
the gaming Start, so later edits to a name or phone do not reassign the session.

“Add new” creates the saved customer and starts the linked session atomically on
the server. A failed customer create or failed session start leaves no partial
link. Blank selection remains valid and starts an unlinked session. Existing
Code29.2 play totals and leaderboard behavior remain cumulative.

Code30 must not be handed out before its matching backend is deployed and its
`customer_id` contract is verified. An older backend may ignore the new field,
which avoids a hard protocol failure but would silently start an unlinked
session. Coordinated backend deployment is therefore a release prerequisite,
not an optional compatibility check.

The normal optional-update flow is inherited unchanged. Eligible staff can
choose **Later**, and installation requires a separate explicit Android install
action. `ANDROID_MIN_SUPPORTED_VERSION_CODE=8`,
`ANDROID_LATEST_VERSION_CODE=8`, and compatibility policy revision `1` remain
unchanged until the verified Code30 artifact is deliberately activated.

Reward estimates remain draft-only. `rewards_enabled=false` and
`messaging_enabled=false`; there is no complimentary-hour grant, redemption,
WhatsApp send, provider credential, or external messaging activation.

## Release gates

1. Preserve the 21-file feature freeze and independently approve the exact
   source and metadata delta.
2. Run focused identity, regression-freeze, backend contract, frontend label,
   migration, Android, tagged-release, installer-lock, and unchanged workflow
   image/dependency checks. Do not retry the historical blocked scanner-source
   route.
3. Verify online full-directory search, cached offline native search, stable-ID
   selection, atomic Add-new-and-Start, blank selection, and cumulative
   Code29.1/29.2 behavior against an isolated backend.
4. Deploy the matching backend and migration before any APK distribution. Then
   verify authenticated runtime identity and the selected `customer_id` path.
5. Tag the exact approved commit as `v3.1.24`. Produce the APK, AAB, checksums,
   and manifest together through the protected workflow and independently
   verify source, package, build 32, version name, bytes, size, and signer.
6. Prove signed in-place upgrade and local-data continuity without uninstalling,
   clearing app data, or dropping queued work. Stage the verified inactive
   release record, then require the user's separate offer action.
7. Record target-tablet installation acceptance and the realistic whole-day
   shop gate separately from build, signing, deployment, staging, and offer.

This document does not claim final CI, backend deployment, a signed artifact,
upgrade continuity, inactive staging, activation, offer, installation, physical
device acceptance, or the whole-day shop gate has passed.
