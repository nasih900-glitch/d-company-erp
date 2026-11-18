# Official Alpine zlib runtime proof

The five candidate Dockerfiles require exact signed `zlib=1.4-r0`. The shared
verifier and probe retain their original Caddy paths; a closed service selector
binds every other image to its own reviewed profile. No caller-supplied distro,
architecture or port is accepted. Image bases, Python, Caddy/Go, nginx,
PostgreSQL, Redis and unrelated package pins stay unchanged.

| Service | Alpine | Architecture | Official port commit |
| --- | --- | --- | --- |
| Backend | 3.24.3 | x86_64 | `bdf501c870a92a05c097857401f69c48c4810384` |
| Frontend | 3.24.1 | x86_64 | `bdf501c870a92a05c097857401f69c48c4810384` |
| PostgreSQL | 3.24.1 | x86_64 | `bdf501c870a92a05c097857401f69c48c4810384` |
| Redis | 3.21.8 | x86_64 | `d3332621d44c2c4cf52e52217b16674db190212c` |
| Caddy | 3.23.5 | x86_64 | `0436c6d655a04cbfd7987202a970f5aa7870a901` |

The four former custom 1.3.2 overlays are removed from current image construction.
Their source/patch files and released historical reconstruction remain retained.
No old fixed VEX is generated or applied to the new bytes. Residual
CVE-2026-4738 or CVE-2026-85091 findings fail for every service at any severity;
all other High/Critical findings and all ignored findings still block acceptance.
Upstream 4738 concerns `contrib/infback9`; this does not assert every core 1.3.2
consumer was exposed.

The four apk3 Dockerfiles fetch the exact recursive version constraint into
private temporary storage. Redis's apk2 client uses a fresh precreated private
cache, explicitly updates its index, then runs native `cache download
'zlib=1.4-r0'`. Its fetch applet does not parse that dependency constraint.

Redis requires exactly one regular non-symlink cached archive named
`zlib-1.4-r0.<8 lowercase hex>.apk`. Those eight characters are the native cache
checksum fragment, not a SHA256 pin or authentication proof. Missing, duplicate,
malformed or wrong-version candidates fail before retention, including the
apk2 solver's possible successful exit with no downloaded file. A count failure
prints at most 30 lines of no-network local package policy before retaining its
fatal outcome. Diagnostic failure cannot admit an archive.

Every selected archive is verified with the base's existing trusted keys before
canonical 0444 retention; private index/dependency/cache files are discarded.
Redis also cleans its private cache on command/signature failure without
changing that failure's exit status. Installation keeps its exact package pins
and the ordinary trusted package manager. No repository/key, TLS client or
untrusted-package switch is introduced.

`verify-official-zlib.sh` explicitly verifies the retained archive's signature,
derives its control identity from that archive, and requires exact service,
version/origin, port and architecture. One valid authenticated manifest checksum
must match the installed library. Redis's apk2 profile requires exactly one
regular `usr/lib/libz.so.1.4` tar member, rejects both verbose link formats and
reads only that member to stdout into private temporary storage.

The four closed non-Redis profiles retain apk3's format-aware `apk extract`
with the existing network/repository/chown restrictions and a fresh private
destination. Extraction failure stops the proof without switching extractors.
This preserves Caddy's observed extraction API; no signed archive-format
failure has been established. In both branches the exact payload must be a
regular non-symlink, its canonical path must equal its expected lexical path
(including all parents), and its link count must be one. Its manifest checksum
and SHA256 must match the authenticated manifest and actual mapped runtime.
Manifest success alone is insufficient authentication.

The shared probe checks canonical realpath, `zlibVersion()`, ELF SONAME and
`/proc/self/maps`, then performs genuine compress→uncompress with exact byte
comparison. It keeps its four-line identity output and fails before output if
the roundtrip fails. Probe compilation uses discarded per-base stages; no
compiler or new business dependency enters the runtime image. Every image runs
the complete proof after its runtime changes.

The hardened scanner repeats the proof against the immutable final image,
offline/read-only as UID 65532 with no capabilities and bounded temporary storage.
The closed raw proof includes its service and is bound to the same config-image
ID/tag consumed by Syft/Grype. This receipt is retained before Grype, so rejection
of another vulnerability does not discard authenticated zlib evidence. Fixed
SBOM/report acceptance remains after successful scanning. Both action handoff
lists name all ten owned raw/bound proof files explicitly; there is no broad
private-directory copy or deletion.

Hosted validation observed matching Caddy build-time bytes in both stores.
APK SHA256
`22ff5ae6b704ac1786d6565dcb3f5dde489e44a5038297a565382b76f656ee08`
and installed/extracted runtime SHA256
`abab3792b17d2bed4502f1e3383e8e41bfde68077d0b555097256968b15cd0c2`.
Those are observed bytes for that snapshot, not new policy pins or completed
final image acceptance. No authenticated local 1.4 APK/library is available.
Local tests exercise genuine host-library compression/corruption detection and
synthetic tar/JSON/command failures; they do not establish vendor 1.4 acceptance.

That hosted run also reported CVE-2026-119170 in Python 3.14.10. The backend
build applies the upstream `hashlib` fix to the pinned source and verifies the
exact installed module and its OpenSSL behavior. The scanner repeats the
runtime proof on the same immutable image, retains the unfiltered Grype report,
and permits a `fixed` statement only for the verified backend Python component.
Both scanner paths retain the high-severity threshold. [Backport and evidence](../cpython/README.md)
