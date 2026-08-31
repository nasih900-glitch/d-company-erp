# D Company ERP 3.1.7 (code 18) local release candidate

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

## Open release-security gate

The Code 17 React Router 6.30.5 exception explicitly expired before Code 18.
It does not carry forward automatically. Before Code 18 can be described as a
release-ready coordinated product, either complete the separately tested React
Router 7 migration or record a new evidence-backed, time-bounded security
disposition approved for Code 18. The Android visual work does not waive this
web release gate.

## Acceptance language

Until all gates pass, describe Code 18 as a local candidate, not as deployed,
published, server-delivered, production-ready, physical-device verified, or
bug-free.
