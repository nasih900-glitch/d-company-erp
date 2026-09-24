# Code30.4 release runbook

Code30.4 is `v3.1.31`, Android build `39`, Room `53` and Alembic `0085`, layered
on released Code30.3 (`v3.1.30`). Each gate below is a separate claim. A green
lower gate is not proof of a higher one, and no step may be skipped because an
earlier one passed. Stop at the first failure and record it exactly.

## 1. Source and CI

1. Review the exact head commit of the Code30.4 pull request.
2. Protected CI must pass every job on that exact commit: backend (including
   the repository contract suite and the Code30.4 freeze layer), frontend,
   Android native with emulator instrumentation, and both Docker lanes.
3. Run `scripts/verify_android_release_version.py --tag v3.1.31` on the commit
   to be tagged.

## 2. Signed Android artifact

1. Tag the reviewed commit `v3.1.31`. The tagged release workflow runs the tests,
   scans, instrumentation and reproducible builds, then signs through the
   protected `android-release-signing` environment, which requires a reviewer.
   Never weaken that environment or substitute a debug APK.
2. Verify the draft release manifest and APK: source commit, package
   `cloud.dcompany.erp`, versionCode `39`, versionName `3.1.31`, size, SHA-256,
   and a signing certificate identical to build `38`.

## 3. Same-signer upgrade trial (isolated emulator)

1. Install the released signed build `38` on a fresh AVD. Create local data:
   an open shift, a running PS5 session, and at least one queued offline action.
2. Install the signed build `39` **over it** without uninstalling or clearing
   data. Confirm Room migrates 52 → 53, the local data and queued action are
   intact, and the queue replays exactly once after reconnecting.
3. Offline trial on build `39` against a disposable local backend: 60 → 30
   amendment, friend Join and Leave, reconnect, Stop, Send to POS, split cash +
   UPI payment and receipt; then check reports and that the station is free.

A debug APK installed over a release-signed build is not valid evidence for
this gate.

## 4. Production cutover

1. The owner confirms that no business is being entered. A fresh read-only
   check shows no open shift, open order or active Gaming session, all Sheets
   deliveries delivered, and every current tablet freshly synced with nothing
   pending.
2. Take a backup and prove it restores.
3. Deploy the reviewed commit with the guarded installer
   (`infra/scripts/install-on-vm.sh <domain> --maintenance-confirmed` from a
   clean detached checkout of that commit). It must finish at migration `0085`
   with the backend image labelled with the same commit.
4. Smoke test Web and the API: authenticated login, Gaming catalogue including
   the paid non-PS5 extensions, reports and receipts.
5. Stage the signed APK **inactive** with `ops/stage_android_release.py`, as
   described in `docs/SERVER_DRIVEN_ANDROID_UPDATES.md`. Registration alone does
   not offer the update.
6. The owner reviews the staged release in Settings → Devices & updates and
   presses **Offer update**. Tablet users still approve the installation.

Do not offer build `39` while the shop is active or before the same-signer
upgrade trial has passed. After participant rows, settlements or amendment
receipts exist, do not roll back below migration `0085` or run the Code30.3
application; restore the pre-deployment backup only as an owner decision.

## 5. Acceptance limits

Physical Redmi tablet responsiveness, printer and SMTP delivery are separate
acceptance gates. Emulator and local evidence do not prove them.
