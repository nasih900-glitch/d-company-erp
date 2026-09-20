# D Company ERP Code29.2 release candidate

Code29.2 is the coordinated successor candidate to immutable Code29.1. Its
technical identity is tag `v3.1.23`, Android `versionName=3.1.23`, Android
`versionCode=31`, and database migration head `0072`. The Web and backend
package version is `3.1.23` across source, build, and runtime contracts.

Code29.1 (`v3.1.22`, installation build 30) and every earlier tag, package,
manifest, and artifact remain immutable history. Code29.2 must be built once
from one independently approved clean commit; no previous release identity or
artifact may be rebuilt, overwritten, retagged, or relabelled.

The candidate adds the reviewed customer gaming-playtime record, stable customer
identity links, completed-play totals, and customer leaderboard described in
[`CUSTOMER_PLAYTIME_DRAFT.md`](CUSTOMER_PLAYTIME_DRAFT.md). The complimentary-hour
calculation remains a labelled draft estimate. Database and API contracts force
`rewards_enabled=false` and `messaging_enabled=false`. There is no reward grant,
redemption, WhatsApp send, provider credential, worker, or external messaging
activation in this candidate.

## Evidence boundary

The 2026-09-13 read-only production preflight identified deployed predecessor
source `d7d7460aef0c1509483860ac737cbbc00b280ec4`, schema head `0071`, and
Android active build 30. Operational records and capacity evidence remain in the
private release output. This predecessor identity does not prove the Code29.2
source, migration, artifact, deployment, offer, or installation.

The reviewed feature checks and Android Customers-display synthetic evidence are
source/candidate evidence. A named offline Start driven through the real Android
`SyncEngine` to an isolated local backend is required release evidence and must
be preserved outside the public repository before promotion.

## Release gates

1. Freeze the reviewed feature files, apply only the coordinated release
   identity and contract updates, and independently approve the exact clean
   source commit.
2. Run the exact-source backend, Web, Android, migration, regression-freeze,
   installer-lock, tagged-release, and unchanged workflow image/dependency
   gates without skips or weakened checks. Do not retry the historical blocked
   scanner-source route.
3. Tag that exact commit `v3.1.23`. Produce the APK, AAB, checksums, and manifest
   together through the protected workflow, then independently verify source
   commit, package, version code/name, bytes, size, and preserved signing
   certificate.
4. Prove same-lineage in-place upgrade continuity using the exact signed bytes,
   without uninstalling, clearing app data, losing Room records, or discarding
   queued work.
5. Revalidate production quiescence, backup/restore, migration `0071 -> 0072`,
   immutable runtime image parity, authentication, compatibility, and public
   networking before cutover.
6. Stage only the verified inactive Code29.2 record. An authorised controller
   must review the exact manifest and public bytes before activation. Keep
   `ANDROID_MIN_SUPPORTED_VERSION_CODE=8`, `ANDROID_LATEST_VERSION_CODE=8`, and
   compatibility policy revision `1` unchanged until that controlled offer.
7. After activation, Android still requires the employee to accept installation.
   Record target-tablet upgrade, data continuity, offline/reconnect behavior,
   and a realistic whole-day shop run as separate acceptance evidence.

This source record does not claim final CI, a signed artifact, continuity,
production deployment, inactive staging, activation, an update offer, tablet
installation, physical-device acceptance, or the whole-day shop gate has passed.
