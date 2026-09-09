# D Company ERP 3.1.18 (code 28) release candidate

Code 28 is the unsigned scanner-hardening successor to signed Code 27. Its
coordinated identity is tag `v3.1.18`, Android `versionName=3.1.18`, Android
`versionCode=28`, and database migration head `0071`.

Code 27 (`3.1.17`) completed signing and is immutable. Its release gate then
stopped on a Syft scratch-mount `EACCES` before production deployment, staging,
activation, or cutover; Grype did not run. Subsequent source and capacity review
showed that both non-root scanner scratch contracts were unsafe. The original
`128m` Syft and `512m` Grype RAM-backed limits were too small: preserved
candidate layers already exceed `128 MiB`, while the reviewed current Grype
database expands beyond `1.5 GiB`. Code 27 must not be rebuilt, relabelled,
reused, staged, or activated.

The Code 28 correction is limited to the production installer, the shared
hardened scanner helper, and the Linux image-scan action. Both scanners receive
a writable `mode=1777`, `noexec,nosuid,nodev` 512 MiB temporary filesystem for
transient files. Grype's much larger database uses a unique root-private
disk-backed bind mount, explicitly remounted `rw,noexec,nosuid,nodev`, verified
on the host and from an owned non-root container, and removed only after all
owned containers stop and the mount is safely unmounted. Scanner containers
remain non-root, read-only, capability-free, `no-new-privileges`, PID/CPU
bounded, and limited to 768 MiB memory with 1536 MiB combined memory plus swap.
Syft and the mount probe have no network. Grype retains network access for
public vulnerability-database updates; the scanner image is digest-pinned, and
the fetched database build, schema, and validity are recorded and checked.

The disk preflight reserves the candidate archive bytes plus 3 GiB of free
workspace and requires at least 1 GiB to remain after each scan. This is a
fail-closed operational reserve, not a filesystem quota. The helper records the
database identity and sampled cache maximum, preserves archives and root-private
runtime evidence on unresolved failure, and removes archives only after every
Syft/Grype source, scanner, database, and image-tag check passes. Actual cold
all-four-image Linux CI evidence is required to validate the per-scanner
resource limits and runner-side path. It does not prove whole-host capacity on
the intended 2 GiB RAM plus 2 GiB swap VPS; a guarded host run and free-space
recheck before maintenance remain separate requirements.

## Required Caddy dependency security correction

Exact Code 28 CI run `34344571701` passed the backend, frontend, and Android
lanes, but both container scanners rejected the Caddy image because its
reproducible Go graph selected `google.golang.org/grpc v1.83.1`. The reviewed
upstream [GHSA-2v4p-qf9q-27wj](https://github.com/grpc/grpc-go/security/advisories/GHSA-2v4p-qf9q-27wj)
identifies `v1.83.2` as patched. This records a dependency gate failure; it does
not claim that the deployed Caddy configuration exposes the vulnerable path.

The correction selects only gRPC `v1.83.2` and its required existing
`golang.org/x/net v0.58.0` dependency. The module set, Caddy `v2.11.4`, Go
`1.26.8`, build tags, linker flags, base images, runtime capabilities, and
application behavior remain unchanged. The generated module checksums and the
deterministic Linux/amd64 Caddy binary hash are checked in the Dockerfile and
both CI/release workflows. Run `34344571701` cannot approve this changed source;
the exact new commit must rerun the complete gates and both scanners.

No Android, Web, backend business, billing, permission, offline-queue, or
database behavior change from signed Code 27 is authorized. Product application
source must be byte-identical to signed Code 27 except the coordinated release
identity. Code 28 requires a new protected signing approval and newly produced
artifacts; signed Code 27 bytes cannot be reused.

This ledger records requirements, not completed Code 28 evidence. It does not
claim that exact-source tests, actual image scans, builds, physical validation,
signing, upgrade, deployment, staging, installation, or a live trial passed.

## Required exact-source phases

All evidence must bind to one clean Code 28 commit and, where applicable, the
same newly signed artifact. Prior Code 27 results are historical evidence and
cannot complete a Code 28 gate.

1. **Fix and regression protection.** Review the shared scanner correction,
   execute its negative and cleanup regressions, validate the coordinated
   release identity, and prove the complete 491-file Code 25 regression surface
   plus all non-identity signed Code 27 application behavior remain unchanged.
2. **Full automated and bounded physical validation.** Run the complete backend,
   frontend, Android JVM/instrumentation, release-safety, migration, and build
   suites from the exact source. The existing 413-step, 16-session synthetic
   physical Gaming/POS/Finance lane may run in parallel, but both results must
   bind to that same commit and Code 28 identity. Linux CI must cold-run the
   shared hardened helper against all four exact candidate image archives and
   retain validated reports plus measured resource evidence.
3. **Signing, upgrade, final audit, and deployment.** After explicit new signing
   approval, create and verify Code 28 artifacts and provenance. Prove the
   same-key Code 21-to-Code 28 in-place upgrade without data or queued-work loss.
   Obtain fresh maintenance confirmation and tablet/outbox quiescence, complete
   the final source audit and production backup/restore test, then deploy and
   verify the matching backend/Web runtime and compatibility endpoints.
4. **Owner-visible inactive staging.** Register only the exact verified Code 28
   artifact as an inactive owner-visible Web ERP record. Staging must not
   activate, advertise, or automatically install it.

The separate eight-hour whole-day requirement remains cancelled. After all
four phases pass, the owner may explicitly activate the exact inactive
candidate for the coordinated target-tablet installation. Activation is a
channel-wide offer, not a per-device allowlist, and Android still requires user
installation approval. The supervised real-live operational acceptance trial
then verifies authenticated health, natural token expiry, queued-work behavior,
and financial reconciliation before broader staff rollout.

Cloud physical testing does not prove Redmi Pad 2 or HyperOS behavior. The
target tablet must still prove same-key upgrade, reboot/lock-screen alarm
delivery, notification-denial recovery, and OEM battery-policy behavior.
