# D Company ERP 3.1.19 (code 29) release candidate

> **Current correction record:** The original `v3.1.19` candidate record below
> is preserved as signed history. The final section of this document supersedes
> its identity and phase order for corrected Code 29 at `v3.1.20`.

Code 29 is the unsigned successor to signed Code 28. Its coordinated candidate
identity is tag `v3.1.19`, Android `versionName=3.1.19`, Android
`versionCode=29`, and database migration head `0071`.

Signed Code 28 (`3.1.18`) is immutable superseded history. Its exact production
images failed the image-identity gate after owner maintenance authorization and
an isolated signed-28 upgrade test, but before the installer entered
maintenance/cutover and before production or partner installation, staging,
activation, or offer. Its source,
tag, manifest, signed artifacts, and evidence must not be modified, reused, or
described as Code 29 evidence. The historical
[`CODE28_RELEASE_CANDIDATE.md`](CODE28_RELEASE_CANDIDATE.md) remains an
unchanged record of what that candidate required; later Code 28 results do not
retroactively rewrite that ledger.

The Code 29 source change is bounded to the coordinated identity, the reviewed
Android quantity-input correction, the reviewed shared FormDialog IME-inset
correction, the reviewed RecipeDetail constrained-scrolling correction, the
reviewed image archive/scanner evidence path, and tests and current operator
records that protect those changes. It does not authorize
pricing, money or Finance calculations, database schema, sync/outbox,
permissions, production installer behavior, Caddy module graph or binary, or
unrelated Android/Web/backend behavior changes.

The test-only audit driver's lockfile also gains the missing release-unit-test
configuration coverage for its existing JUnit and Hamcrest dependencies. No
dependency version, application runtime or audit-driver behavior changes.

The CI and release workflows contain two isolated image-only lanes: Docker
29.6.1 with the classic image store and Docker 29.6.1 with the containerd image
store. Each lane must fail closed unless the ordinary runner and a minimal root
environment resolve the same local Unix socket, setup-action CLI bytes, client
and server identity, disposable Docker root, daemon, and exact store metadata.
Existing hosted-runner Compose and Buildx plugins are prerequisites whose
resolved versions, local `docker` driver, and effective endpoint are recorded;
they are not installed or version-pinned by setup-Docker, and no floating
replacement may be downloaded. The setup action itself logs Docker information
and its shallow-merged daemon configuration. The new retained evidence is
narrowly allowlisted, and these secretless image jobs receive no application,
production, or signing secrets.

All four production images must be built and loaded on both stores. Each lane
must retain runtime image identity, archive digest, scanner config identity,
platform, and actual archive shape. Classic archives must prove config-ID
identity. Containerd archives must prove OCI-index runtime identity, one
runnable Linux/amd64 manifest, and the required recognized attestation link.
The original Caddy binary/module assertion, PostgreSQL 16 compatibility,
Compose runtime health, Redis runtime image identity, and hardened Syft/Grype
gates remain mandatory in both lanes. Passing source tests cannot substitute
for an actual exact-source hosted Ubuntu run against both stores.

The quantity correction accepts the explicitly reviewed dot/zero-whole decimal
forms while rejecting ambiguous or zero-padded grouping. Its deterministic JVM
and UI contracts are necessary but not sufficient runtime evidence. A separate
targeted inventory-form runtime gate remains pending for ingredient, GRN, and
adjustment local-first behavior and recipes' online-only behavior. The existing
413-step, 16-session Gaming/POS/Finance driver is unchanged except candidate
identity and must not be expanded or presented as that inventory-form proof.

The shared FormDialog correction is limited to a finite dialog surface centered
inside Android's measured visible frame, which excludes the areas occupied by
the status bar, navigation bar, and real software keyboard. Its title,
validation error, and body share one permanent scroll region at every height,
while wrapping actions retain reserved space and remain fixed. This deliberately
lets headings scroll in long spacious forms: replacing a focused field's scroll
ancestor when the keyboard opened caused native input loss and a focus-tree
crash. One stable ancestor avoids that failure and nested unbounded measurement.
On smaller frames, existing 16dp spacing tokens replace the spacious 24dp surface
padding and footer separation. New or changed nonblank form errors return the
main scroll region to the top so their explanation remains discoverable.
An explicit backdrop retains idle outside-tap dismissal, rejects dismissal while
busy, and does not treat blank content inside the surface as outside. The change
preserves form content, focus and entered values across IME resizing, callbacks,
enable/busy behavior, visual tokens, and inventory behavior. The targeted
real-IME instrumentation test must capture the real keyboard and prove, after
the IME layout stabilizes, that long warnings and errors remain scrollable and
both expanded 48dp action touch targets remain above it on the API-35 tablet
profile before this source correction is considered verified. Native Android
input must also survive spacious-to-compact resizing, lower numeric-to-upper
text field switching, return traversal, and keyboard hide/reopen; semantic text
replacement alone is not acceptance for typing. Back tests must establish
separate dialog-window focus before injecting the key. Long-form error and
bottom-content reachability must remain verified independently of those keys.

The RecipeDetail correction keeps the SectionCard header fixed and makes its
bounded detail content scrollable. The active recipe's name, recorded cost,
ingredient lines, Edit/Remove controls, and Add ingredient action must remain
reachable in the reproduced 162dp and 224dp panels; the analogous Retry and
Link recipe actions must remain reachable in error and no-active-recipe states.
It preserves the displayed recipe data, ingredient availability behavior,
prices, enabled states, and exact ViewModel callback payloads.

This ledger records requirements, not completed Code 29 release evidence. It
does not claim that exact-source hosted Linux CI, a signed artifact, an in-place
upgrade, production deployment, inactive staging, activation, installation, or
a supervised live trial has passed.

## Required exact-source phases

1. **Source freeze and automated gates.** Independently approve the exact
   signed-28-to-29 source delta, all pinned scanner/quantity files, all reviewed
   existing test/support changes, the inherited 491-file Code 25 regression
   surface, and the complete backend, Web, Android JVM/instrumentation,
   migration, release-contract, build, and syntax suites. Run both image-store
   lanes on the exact clean commit and retain their full bounded evidence.
2. **Signing and upgrade preservation.** Obtain new protected signing approval
   and produce the Code 29 direct APK, AAB, checksums, and manifest together from
   the exact green tagged workflow. Independently verify package, version,
   source, bytes, manifest, and the preserved expected certificate. Prove a
   same-key Code 21-to-Code 29 in-place upgrade without uninstalling, clearing
   app data, losing Room records, or discarding queued work.
3. **Production preparation and cutover.** Obtain fresh maintenance authority,
   quiesce and reconcile tablet/outbox state, rehearse migration, restore and
   rollback from the exact source, then deploy only after the production-image
   and scanner evidence is accepted. Verify the backend, Web ERP, Caddy,
   PostgreSQL, Redis, normal authentication, compatibility endpoint, and public
   networking from the deployed runtime. Source or CI success is not production
   evidence.
4. **Owner-visible inactive staging.** Stage only the exact verified Code 29 CI
   manifest and APK. The staging tool may create an inactive record but cannot
   activate it. The explicitly configured release-controller must review the
   exact version, hash, size, signer, source ref, workflow run, and public bytes.

Only after all four phases pass may the bound release-controller explicitly
activate the inactive record. Activation exposes the offer channel-wide to all
eligible direct clients; there is no per-device allowlist. Keep
`ANDROID_MIN_SUPPORTED_VERSION_CODE=8`, and do not change the independent
monotonic compatibility policy revision merely because a candidate was built,
staged, or activated. Android always requires user consent to install. Code 21
remains the same-channel upgrade predecessor, and staff must not uninstall or
clear data to make an upgrade pass.

After installation, the target tablet must still pass authenticated physical
smoke, offline/restart recovery, alarms and notification-denial recovery, OEM
battery policy behavior, the targeted inventory-form cases, and the supervised
real-live operational and financial reconciliation trial before a wider staff
rollout. Mac/device access is not part of this source phase.

## Corrected Code 29 current record (`3.1.20`)

Original signed Code 29 (`3.1.19`, commit
`0949620b4632ebd6accdfa62a203be8d85b31a24`) is immutable. Its production
installer securely opened and locked the root-owned mode-`0600` lock, then
rejected the inherited descriptor before image builds or maintenance. GNU
coreutils 8.32 reports an empty regular file as `regular empty file` for
`stat %F`, while the shell expected the content-sensitive text `regular file`.
The existing production lock happened to contain seven bytes, so it did not
reproduce the failure; a normally created empty lock, including a new lock
after reboot, did.

The corrected Code 29 identity is tag `v3.1.20`, Android
`versionName=3.1.20`, Android `versionCode=29`, and migration head `0071`.
Changing `versionName` distinguishes the corrected package and manifest while
retaining Code 29. The original `v3.1.19` tag, source, manifest, signatures,
and artifacts must not be moved, rebuilt, overwritten, or relabelled.

The installer now uses GNU stat's numeric `%f` mode and requires exact raw
Linux mode `8180` (`S_IFREG | 0600`) with root UID/GID, one link, and the exact
device/inode passed by the Python bootstrap. Descriptor-relative `O_NOFOLLOW`,
no truncation, nonblocking `flock`, private runtime directories, and all other
fail-closed checks remain intact. Normal CI and the tagged coordinated-release
job run the standalone Linux/root descriptor regression through `sudo -n` with
the configured Python interpreter.

No Android, Web, backend business, billing, money, permission, database,
offline/sync, scanner, or Caddy behavior change is authorized. Application
source remains byte-identical to original signed Code 29 except the backend
version string. The physical plan retains all 413 steps and 16 sessions and
changes identity only. Compatibility stays at minimum code `8`, latest default
code `8`, and policy revision `1`. The broader signed-Code29 scanner-source
review remains incomplete evidence and is not claimed as passed.

The earlier phase order in this ledger is superseded for this correction:

1. Independently approve the exact original-Code29-to-corrected-Code29 delta.
   Run the mandatory Linux/root lock verifier, complete repository, backend,
   Web, Android, migration, syntax, and both production-image-store scanner
   gates on one clean commit.
2. Only after those checks pass, tag the exact commit and obtain protected
   signing approval. Produce the `3.1.20` Code 29 APK/AAB, checksums, and
   manifest together, then independently verify their source, bytes, package,
   version code/name, and preserved signer. Retain the anonymous offline fixture
   chain from signed Code 21 to original signed Code 29 and then the same-code
   corrected Code 29 replacement, with IPv4 and IPv6 blocked. This is chained
   upgrade evidence, not a direct Code21-to-corrected-Code29 proof, and remains
   separate from the authenticated user-and-agent trial.
3. Complete the authenticated available-device, offline/restart, inventory,
   Gaming, POS, notification, performance, and financial-reconciliation trial.
   While the Redmi tablet is unavailable, authenticated emulator execution and
   Web ERP reconciliation are the accepted alternative. Preserve prior Lenovo
   physical evidence separately; do not describe it as a new physical run. A
   signed package is not authority to deploy, stage, activate, or offer it.
4. Only after the user accepts a successful trial may production preparation
   and deployment begin. The user has already confirmed the real staff tablet
   was synced and paused; repeat fresh read-only outbox and paused-state checks
   before production work without clearing app data. Reconfirm quiescence,
   backup, migration rehearsal, restore, rollback, exact runtime images,
   authentication, compatibility, and public network behavior. Any later
   inactive staging and channel-wide offer remains a separate owner-controlled
   action.

This correction record does not claim that corrected Code 29 is release-ready,
signed, trial-accepted, deployed, staged, active, offered, or installed in
production.
