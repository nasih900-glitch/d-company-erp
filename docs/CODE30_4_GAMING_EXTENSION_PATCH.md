# Code30.4 non-PS5 Gaming extension patch

This local additive patch extends the canonical D Company Gaming tariff with
owner-approved paid extensions for Racing Sim, VR Games, and VR Racing Sim.
Each extension has the same duration and published price as the corresponding
base session. The existing 17 package identities and every historical session,
extension receipt, order, payment, and audit row remain unchanged.

## Approved catalog additions

| Mode | Stable package code | Duration | Price |
|---|---|---:|---:|
| Racing Sim | `standard-simdrive-extension-15m` | 15 min | ₹70 |
| Racing Sim | `standard-simdrive-extension-30m` | 30 min | ₹100 |
| Racing Sim | `standard-simdrive-extension-60m` | 60 min | ₹180 |
| VR Games | `vr-games-extension-15m` | 15 min | ₹80 |
| VR Games | `vr-games-extension-30m` | 30 min | ₹120 |
| VR Games | `vr-games-extension-60m` | 60 min | ₹200 |
| VR Racing Sim | `vr-racing-extension-15m` | 15 min | ₹100 |
| VR Racing Sim | `vr-racing-extension-30m` | 30 min | ₹140 |
| VR Racing Sim | `vr-racing-extension-60m` | 60 min | ₹250 |

The four existing PS5 extension prices are unchanged. The catalog now contains
26 active canonical rows: 13 base packages and 13 extension packages. Money is
stored in integer paise.

## Runtime behavior

No client-specific package list is added. Web and Android already load active
packages from the backend and select extensions by station type, the session's
locked variant, and pricing tier. The new `kind=extension` rows therefore
appear through the existing guarded paid-extension flow after the catalog is
applied and clients refresh.

The backend continues to compare the selected package and session snapshots,
append one immutable `gaming_session_extensions` receipt, preserve idempotent
replay, add the exact extension price to the session, and send the final locked
session amount to POS. There is no database migration because the existing
catalog and receipt schemas already represent this behavior.

## Application boundary

An ordinary backend restart does not change the catalog. During a reviewed
production cutover, the guarded installer already runs a catalog dry run and
then applies the catalog after migrations and readiness checks, while public
ingress remains closed. Each invocation covers every active branch of the
resolved D Company tenant and commits them in one transaction; a conflict in
any branch aborts the whole catalog update.

Operators must not add an unscoped manual catalog invocation to that cutover.
For a separately reviewed branch-specific diagnosis or recovery, both tenant
and branch must be explicit:

```bash
cd backend
python -m scripts.ensure_gaming_tariff \
  --company-id <D_COMPANY_UUID> --branch-id <BRANCH_UUID> --dry-run
python -m scripts.ensure_gaming_tariff \
  --company-id <D_COMPANY_UUID> --branch-id <BRANCH_UUID>
```

The audit source is `script/ensure_gaming_tariff-v3`. Its reason identifies the
original 2026-09-13 card and the separately approved 2026-09-24 non-PS5
extensions. Unknown active fixed-tariff rows still block the operation instead
of being silently retired.

## Rollback boundary

If cutover fails before public ingress reopens and before business resumes, use
the installer's verified rollback path. It restores the quiesced pre-upgrade
database snapshot together with the predecessor runtime, so the nine new
catalog rows are rolled back with the rest of the failed cutover. Do not delete
catalog rows manually.

After ingress reopens or business resumes, restoring the pre-upgrade database
would discard newer business activity and is not a safe rollback. The
predecessor catalog command also cannot run while these nine rows remain
active: it correctly treats them as unexpected fixed-tariff products and
rejects the transaction. A post-resumption rollback therefore requires a
separately reviewed, audited retirement that marks exactly these nine package
rows inactive without deleting or rewriting their identities or any linked
sessions, immutable extension receipts, orders, payments, or audit history.
Only after that retirement and its reconciliation may the predecessor
installer/catalog command be considered.

This document records local source scope only. It is not deployment, signed
Android artifact, physical-device, or production acceptance evidence.
