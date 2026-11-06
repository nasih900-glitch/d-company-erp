# Build 70 / 3.1.63 candidate

The frontend, PostgreSQL, Caddy and Redis images update Alpine's musl-utils
to the same package revision as their installed musl libc. Each build uses
the exact branch-specific x86_64 package digest and verifies the Alpine
signature offline against the pinned key already present in the base image.
The existing base image digests and vulnerability gates still apply.

Caddy updates its linked compression module to klauspost/compress v1.20.2
for the vulnerabilities reported in the Build 69 image scans. The Go
toolchain and the existing x/net security pin are retained.

The guarded transition can wait up to 300 seconds for a fresh Owner
acceptance input after candidate image scans and capacity checks pass.
Public ingress and writers remain live during the wait. Timeout or failed
Owner verification stops the upgrade before maintenance. The installer
still checks the same credential input before and after migration.

The candidate retains Build 69's staff Home and backup-restore checks.
The supported deployed predecessor is the exact Build 65 / 3.1.58 source and
images on Alembic 0094. The candidate targets Alembic 0095 and Android Room
58. Canonical CI, signing, retained upgrade, device and production acceptance
remain release gates. [Build 69 candidate history](V3_1_62_RELEASE_CANDIDATE.md)
· [Guarded deployment procedure](BUILD66_GUARDED_DEPLOYMENT.md)
