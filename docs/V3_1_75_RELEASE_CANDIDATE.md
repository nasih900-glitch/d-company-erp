# Build 82 / 3.1.75 release candidate

This is the narrow, unshipped successor to immutable Build 81 / v3.1.74 at
`0eaff836e8b0411ab21ccace04468f3d1cca613f`. Build 81's hosted contract lane
rejected twelve stale assertions after the reviewed OpenSSL package producer
was added. The release implementation and package evidence were not the cause.

Build 82 corrects those contracts without changing Android product behavior,
database schemas, permissions, billing, payment, role or Gaming workflows. Its
pipeline contract distinguishes the one local-output OpenSSL producer build
from the five loaded runtime-image builds. Historical Caddy evidence is read
from the immutable pre-backport source; historical frontend and PostgreSQL
evidence remains bound to Build 77. The historical vendor-r1 recipe comparison
is bound to immutable Build 80, while current Dockerfiles remain byte-identical
to Build 81 and retain the exact custom-package installation assertions.

Version 3.1.75 uses Android build 82, Room 58 and Alembic 0105. The Build 81
literal map and all earlier maps remain unchanged; Build 82 has a separate
exact-byte overlay.

This note does not establish a Build 82 tag, successful hosted run, signed APK,
production deployment or tablet update offer. Full exact-source CI, image-store
security checks, signing, upgrade preservation, guarded production admission
and physical Redmi acceptance remain separate mandatory gates.
