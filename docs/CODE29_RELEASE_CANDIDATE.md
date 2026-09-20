# D Company ERP 3.1.19 (code 29) release candidate

> **Current correction record:** The original `v3.1.19` candidate record below
> is preserved as signed history. The final section of this document supersedes
> its identity and phase order for current Code 29 at `v3.1.21`.

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

## Current Code 29 patch record (`3.1.21`)

Original signed Code 29 (`3.1.19`, commit
`0949620b4632ebd6accdfa62a203be8d85b31a24`) is immutable. Its production
installer securely opened and locked the root-owned mode-`0600` lock, then
rejected the inherited descriptor before image builds or maintenance. GNU
coreutils 8.32 reports an empty regular file as `regular empty file` for
`stat %F`, while the shell expected the content-sensitive text `regular file`.
The existing production lock happened to contain seven bytes, so it did not
reproduce the failure; a normally created empty lock, including a new lock
after reboot, did.

The reviewed installer correction uses GNU stat's numeric `%f` mode and requires
exact raw Linux mode `8180` (`S_IFREG | 0600`) with root UID/GID, one link, and
the exact device/inode passed by the Python bootstrap. Descriptor-relative
`O_NOFOLLOW`, no truncation, nonblocking `flock`, private runtime directories,
and all other fail-closed checks remain intact. Normal CI and the tagged
coordinated-release job run the standalone Linux/root descriptor regression
through `sudo -n` with the configured Python interpreter. The `v3.1.20`
release workflow run `34716757359` was cancelled before build or signing after
the POS notice defect was confirmed; it produced no release artifact and is
preserved as historical evidence.

The current Code 29 identity is tag `v3.1.21`, Android `versionName=3.1.21`,
Android `versionCode=29`, and migration head `0071`. Version name distinguishes
the current package and manifest from immutable `v3.1.19` and cancelled
`v3.1.20` history while retaining Code 29. Minimum supported code `8`, latest
default code `8`, compatibility policy revision `1`, and all parsers and schema
contracts remain unchanged.

Two bounded Android presentation defects are corrected. A same-order held POS
review now keeps its success notice above checkout-version refreshes while its
inner versioned `rememberSaveable` editor state remains intact. The loaded
Inventory workspace now scrolls naturally measured header content and keeps a
finite stock/FIFO pane when refresh errors, pending changes, summaries, and low
stock fill a landscape viewport. The reviewed changes preserve all POS and
Inventory callbacks, permissions, mutation safeguards, billing, money,
database, offline/sync, scanner, Caddy, and backend business behavior. The
physical audit plan retains all 413 steps and 16 sessions and changes identity
only.

Evidence is phase-specific. Full CI run `34715219053` passed against the
preceding `3.1.20` source at commit
`0c072b8ce9bb69776525ea5fb6a9c799d64a66ff`; it is not a pass for `3.1.21`.
The isolated recovery harness at that commit passed pre-ingress rollback,
committed-data retention after ingress, and same-candidate reopen with 117
tables and was independently audited before its VM stopped. That evidence
supports the unchanged recovery mechanism, but it was not executed against
`3.1.21`. Reviewed before/after instrumentation proves both new UI defects and
their focused corrections. The focused stock trial at the preceding commit
completed all 87 checks at 22:18:55 UTC on the clean `0c072b8` source using the
debug `3.1.20` build and synthetic API on port `55905`; the recipe checks used
the recorded portrait workaround, and cleanup was verified at 22:21 UTC. This
is prior-source evidence, not final `3.1.21` acceptance. The broader signed-Code29
scanner-source review remains incomplete evidence and is not claimed as passed.

The current user direction authorizes completing this bounded Code 29 patch,
its required checks, the final-source synthetic trial, protected signing, and
signed-byte continuity, followed by production deployment and the controlled
offer only if every preceding gate passes. No additional generic permission
loop is required. This authority does not waive a gate, permit moving historical
tags or artifacts, or turn evidence from another source identity into `3.1.21`
evidence.

The current sequence is:

1. Independently approve the exact source delta and run final exact-source CI,
   backend, Web, Android, migration, syntax, Linux/root installer, and both
   production-image-store scanner gates on one clean `3.1.21` commit.
2. Before tagging, run the canonical authenticated synthetic Android and Web
   business trial from the final clean `3.1.21` source using its `physicalAudit`
   APK. Separately run the preserved-cache landscape acceptance with the final
   debug APK. These test variants prove the final source against synthetic
   services; they are not the production-signed release bytes. While Redmi is
   unavailable, the authenticated emulator and Web route is accepted; preserve
   prior Lenovo physical evidence separately.
3. Tag that exact commit as `v3.1.21`; use the protected workflow to produce the
   Code 29 APK/AAB, checksums, and manifest together, then independently verify
   source, bytes, package, version code/name, and the preserved signer.
4. On the isolated emulator `5574`, prove anonymous offline continuity for those
   exact signed bytes as a same-code replacement of original signed Code 29
   `3.1.19`, without uninstalling or clearing data. The completed signed chain is
   only Code 21 `3.1.10` to original Code 29 `3.1.19`; no signed or installed
   `3.1.20` link exists. The pending `3.1.21` replacement is separate from the
   synthetic business trial and is not direct Code-21-to-`3.1.21` proof.
5. After the final-source trials, signed-byte continuity, and runtime gates pass,
   complete production preparation and deployment under the existing authority.
   The user has already confirmed the real staff tablet was synced and paused;
   repeat fresh read-only outbox and paused-state checks before production work
   without clearing app data. Reconfirm quiescence, backup, migration rehearsal,
   restore, rollback, exact runtime images, authentication, compatibility, and
   public network behavior, then stage and activate only the independently
   verified artifact through the bound release-controller. The offer is
   channel-wide for eligible direct clients, and Android still requires user
   consent to install.

This record does not claim that `3.1.21` final CI, signing, continuity,
final-source synthetic trial, production deployment, staging, activation, offer, or
production installation has passed.
