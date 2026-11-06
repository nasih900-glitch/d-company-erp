# Build 68 / 3.1.61 candidate

Build 68 carries forward the application behavior in the
[Build 67 candidate](V3_1_60_RELEASE_CANDIDATE.md), including the guarded
startup of a retained cashier session and the separate Owner review for
voiding a held Gaming bill. Build 67 was withheld before signing.

This successor corrects three Android source-contract tests and advances the
release identity. The tests follow the exact held checkout closure, guarded
fresh login publication, and cleanup of a rejected or finished payment retry.
The checkout, login, and retry implementations remain at the reviewed Build 67
source.

The installer accepts the exact released Build 65 / 3.1.58 predecessor with
Alembic 0094 and requires a candidate attestation for 3.1.61. The existing
[guarded transition](BUILD66_GUARDED_DEPLOYMENT.md) still proves the 0094 to
0095 database migration and exact candidate runtime before opening ingress.
The tablet remains on Room 58. A separate restore of the actual production
backup remains a gate before maintenance.

Canonical CI, signed retained upgrade, physical Redmi and production
acceptance remain release gates. Coordinate the optional offer with the staff
attendance privacy change. The compatibility floor and rollout approval
remain separate decisions.

The coordinated release also updates Caddy's embedded `golang.org/x/net`
module to v0.62.0 and its required dependency versions. The official Go
advisory identifies v0.62.0 as the fix for GO-2026-6860 (CVE-2026-78661).
Both prior release image scans blocked the older Caddy binary. Two isolated
Linux amd64 builds reproduced the new pinned binary; the exact binary passed
the same Grype database with zero High or Critical findings. The complete
hardened image scans remain required for both Docker stores.
