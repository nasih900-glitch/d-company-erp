# v3.1.39 / Android build 47 release candidate

This document records a prepared candidate. It is not evidence of a release,
signed artifact, update offer, installation, or production deployment.

## Identity and predecessor

The production predecessor observed during preparation is commit
`ee025b5b78dff7397e16612cad80aebd1a4904d3`, application version `3.1.37`,
Android build 45, Room schema 53, and Alembic head `0086`.

The signed `3.1.38` / build 46 source at
`b743d7715d22c5ce92f79edc9a371df28930a6a1` is immutable, unoffered history.
It was not deployed and is not treated as this candidate's production
predecessor.

This candidate coordinates:

- tag and application version `v3.1.39` / `3.1.39`;
- Android build 47 and Room schema 55; and
- Alembic head `0088`.

The candidate release commit does not exist yet. Git commit, tag, signing,
publishing, update-offer activation, and deployment remain separate release
actions.

## Reviewed product scope

The candidate contains the user-approved Gaming, POS, Shift, Customers, and
shared-shell correction. The Gaming duration dial remains one stable control
surface through draft, starting, running, paused, and stopped states. Routine
player, drink, cart, shift-count, and customer actions stay in their working
context, while final money and destructive confirmations retain retry and
one-shot guards. Web keeps its existing sidebar and workspace layout.

Anonymous Gaming participants have an immutable participant identity and a
nullable customer link. Anonymous play earns no customer points. Saved-customer
identity remains canonical, leave/rejoin billing remains cumulative by stable
identity, and the station limit remains four players including booked extras.

## Database and offline upgrade boundary

Production upgrades through `0086 -> 0087 -> 0088`. Migration `0088` backfills
customer-backed participant identities, permits anonymous participants without
weakening company/customer scope checks, and refuses downgrade because removing
stored anonymous identity is unsafe. Android upgrades through Room `53 -> 54 ->
55`; Room 55 makes the customer link nullable and stores stable participant
identity and guest display data without rewriting existing queued actions.

The production compatibility path is exactly build 45 / `3.1.37` / `0086` to
build 47 / `3.1.39` / `0088`. Reported tablet queues remain opaque and carried
unresolved. The verifier does not inspect queue contents, clear counters, retire
installations, or convert telemetry into completion evidence. Revisionless
loyalty requests remain accepted only while production's loyalty program is
revision 0; customization makes fresh revisionless requests fail closed while
previously stored idempotent responses remain replayable.

Any drift in production revision, image identity, source/archive digest,
database head, fleet projection, pending counters, installation-guard
definition, runtime environment, loyalty contract, or anonymous-participant
contract refuses the compatibility path.

## Freeze controls

The historical build-46 allow map is checked against immutable commit
`b743d7715d22c5ce92f79edc9a371df28930a6a1` and is not edited. The build-47
release delta has a separate current-tree hash overlay. Together they preserve
the signed historical source while making every new candidate byte explicit.

Android compatibility policy defaults remain unchanged:
`ANDROID_MIN_SUPPORTED_VERSION_CODE=8`,
`ANDROID_LATEST_VERSION_CODE=8`, and
`CLIENT_COMPATIBILITY_POLICY_REVISION=1`. Preparing build 47 does not advertise
or activate an update.

## Evidence and remaining gates

The corrected debug build 47 was approved in the emulator after Android and Web
workflow validation. That evidence supports the product behavior only; it does
not prove the future production-signed artifact or deployment.

Separate promotion gates remain for the exact frozen candidate:

1. independent source and compatibility review;
2. clean coordinated release checks and database migration checks;
3. a release artifact built from the final reviewed commit with production
   endpoints and the production signing identity;
4. artifact-manifest and signature verification;
5. an authenticated in-place build-45-to-build-47 tablet upgrade with Room,
   offline queue, sync, and staff-workflow preservation evidence; and
6. explicit approval before tag, publish, update offer, or production deploy.

Rollback must use the existing database restore and application rollback
controls with the `0088` boundary applied explicitly. Migration `0088` always
refuses downgrade. Before any post-migration ingress, recovery may restore the
verified quiesced backup together with its matching old images. After any new
ingress, retain the current database and recover forward; an owner-directed
snapshot recovery is acceptable only after every post-snapshot event is
reconciled. Never start the build-45 or build-46 backend against an `0088`
database by rolling application binaries back.

Android clients older than build 47 cannot consume anonymous-participant state
and receive HTTP 426 before that state or response is committed to them. The
rollout must coordinate every tablet used for guest-player flows before staff
resume those flows; this does not widen the global minimum-version policy or
permit an incompatible client to bypass the fence.
