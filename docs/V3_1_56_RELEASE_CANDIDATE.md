# 3.1.56 / Android Build 63 candidate

This is an unsigned, unoffered successor to immutable 3.1.55 / Build 62. Room 58 and Alembic 0094 remain unchanged. Application UI/business source, the dial, attendance/pay estimates, permissions, billing, the lossless public BUNDLE transform and ProfileInstaller 1.4.1 are inherited unchanged. Release identity changes necessarily change compiled artifacts; byte-identical APKs are not claimed.

## Bounded pipeline correction

Build 62's canonical coordinated-release-gates job exceeded its 35-minute job budget while backend tests had reached 37%. The retained failure is a job deadline, not evidence that the remaining tests passed or that an assertion failed. Build 63 increases only that coordinated job's bounded budget to 65 minutes. The full backend migrations/tests, repository contracts, dependency/security checks, web checks and downstream image/UI/reproducibility/signing dependencies remain required and unchanged. Other CI job budgets are unchanged.

This corrects available execution time, not application behavior or test acceptance. The complete new branch and canonical tagged runs must finish successfully. Historical failed runs, tags, maps and evidence remain preserved; never retag or replace Build 62.

## Release boundaries

The Build 62 packaging correction and its seven-file byte-exact independent reproduction gate remain intact; see `V3_1_55_RELEASE_CANDIDATE.md`. Both builders consume the final public BUNDLE and verify its hash against the retained transform audit. Original signer isolation, no Gradle after key admission, signed-upgrade tests, guarded installer, normal Owner-session acceptance, maintenance/backup/restore and rollback requirements remain in force. Backend wire manifest and protected-source inventory must be independently recomputed from exact new committed source; their formats and hashes are distinct.

No new features, schema changes, staff policies, Sheet operations, signing admission, deployment or update offer are authorized by this source change. Source preparation, tests, unsigned builds, signed artifacts, upgrade installation and an observed update offer are separate states. The physical Redmi Pad2 remains unavailable; emulator evidence cannot claim physical-device validation or an application free of all bugs/crashes.
