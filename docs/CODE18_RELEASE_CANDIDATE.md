# D Company ERP 3.1.7 (code 18) rejected release attempt

Status: **rejected before signing**. The immutable `v3.1.7` workflow failed its
backend release gate, so it produced no authorised APK and was never staged or
advertised. Do not move or reuse that tag. The current corrected release identity
is `3.1.10` / code `21`; see
[`CODE21_RELEASE_CANDIDATE.md`](CODE21_RELEASE_CANDIDATE.md).

Code 18 is a new Android and coordinated product identity for the
standard-premium visual refinement. Code 17 (`3.1.6`) remains immutable: do not
rebuild, overwrite, or relabel any Code 17 artifact as Code 18.

This source bump does not publish, advertise, register, stage, activate, or
deploy an Android update. Production keeps its existing minimum/latest policy
defaults. A built APK is not a release until the gates below are complete and a
separate rollout decision is recorded.

## Scope

- Refine the native Android presentation toward a standard, restrained premium
  product rather than a gaming-themed interface.
- Preserve every Code 17 Gaming, POS, Shift, Finance, offline, authentication,
  diagnostics, update, and ERP-only remote-assistance contract.
- Keep status colours semantic and reserve the muted brand accent for primary
  actions and selection.
- Keep the web/backend/frontend version surfaces coordinated at `3.1.7` while
  leaving Android compatibility and server-update activation policy unchanged.

## Immutable predecessor and rollout boundary

- Code 17 remains the exact predecessor for an in-place Code 17 to Code 18
  upgrade test.
- No Code 18 APK may reuse a Code 17 filename, version code, manifest, checksum,
  or registry row.
- Do not copy a candidate APK to the public download directory, create a release
  registry row, alter `ANDROID_LATEST_VERSION_CODE`, or invoke the activation
  endpoint during this refinement pass.
- A future direct update still requires employee approval in Android. The ERP
  cannot silently install an APK.

## Release gates

Before any partner handoff or rollout decision, record current evidence for:

- coordinated version-contract checks for `3.1.7` / code `18`;
- backend unit/integration suites and migration proof appropriate to the final
  source commit;
- frontend typecheck, zero-warning lint, full tests, and production build;
- Android JVM tests, lint, debug/instrumentation compilation, and signed direct
  release assembly;
- package, code/name, signer, SHA-256, byte-size, and permissions verification;
- in-place Code 17 to Code 18 installation without clearing application data;
- authenticated Gaming, POS, Shift, Finance, offline/reconnect, diagnostics,
  and Help/remote-assistance regression checks;
- tablet-landscape visual and touch review at the actual Redmi Pad 2 resolution;
- Firebase device evidence where available, kept distinct from physical Redmi
  Pad 2 / HyperOS acceptance; and
- confirmation that no production deployment, production data mutation, or
  server-update activation occurred during candidate preparation.

## React Router security disposition

Code 18 closes the expired Code 17 exception by pinning `react-router-dom` to
`7.18.2`; both committed lockfiles resolve `react-router` and
`react-router-dom` to `7.18.2`. React 18 and the existing declarative
`BrowserRouter` / `HashRouter` architecture are preserved.

The upstream
[`GHSA-qwww-vcr4-c8h2`](https://github.com/remix-run/react-router/security/advisories/GHSA-qwww-vcr4-c8h2)
advisory affects `react-router` versions from `7.12.0` through `7.18.1` and
names `7.18.2` as the first patched v7 release. The advisory only applies to
unstable React Server Component APIs.
D Company is a client-only Vite SPA and does not use those APIs, SSR, or
hydration, so that vulnerable path was not reachable here; the dependency is
patched anyway rather than relying on non-applicability alone. The strict
`internalAppRouteOr` boundary remains as defense in depth for every
data-derived navigation destination.

The final Code 18 frontend gate passed typecheck, zero-warning lint, all 318
tests, and the verified production build. The build toolchain was also moved to
the pinned, audited and repository-tested Vite 6 / Vitest 3 compatibility line,
and both committed lockfiles were refreshed.
Full and production-only audits through both npm and pnpm report zero known
dependency vulnerabilities; this is no longer carried as a deferred Code 17
exception.

## Acceptance language

Code 18 must be described as a rejected pre-signing attempt, not as deployed,
published, server-delivered, production-ready, physical-device verified, or
bug-free.
