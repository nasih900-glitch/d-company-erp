# Build 59 guarded deployment

Apply the Build 58, Build 57 and Build 55 preservation runbooks with independently
reviewed 3.1.52 / Build 59 source and its immutable tag. Bind the actual successful CI run
and attempt, original signer, signed APK, manifest and canonical source archive.
Never reuse Build 58's Owner nonce, plan, expired session, gate, credential input,
scanner receipts or artifact/offer expectations.

Prior operational runtime remains Build 54 / 3.1.46 / schema 0091. Target is Build 59 / 3.1.52 with
schema 0093 and Room 58. The early candidate-runtime check must pass before scans,
Owner authentication or maintenance. The stopped full source/runtime/history
pre-bindings check still precedes backup and migration. An exact frontend
`NGINX_VERSION=1.31.5` to `1.31.8` transition joins the existing reviewed backend Python
patch; no other environment drift or Compose metadata masking is allowed.

The actual-image CI check complements, and never replaces, the private real
runtime checks. Preserve .env, all financial/audit/history/catalogue/fleet rows,
stale tablet queues and exact prior command/image/environment pins. Keep floor 54
and policy revision 4. Retain the forward-only boundary after any new application
writer starts. No generic environment allowance, manual source hotfix, database
cleanup or direct Compose bypass is authorized by this correction.

Signed but unoffered Builds 56 and 58 and unsigned withheld Build 57 remain immutable history.
Verify installation continuity, actual guarded deployment, staging, Owner offer
and public download behavior separately. Physical Redmi Pad 2 testing remains an
explicit limitation. See [V3_1_52_RELEASE_CANDIDATE.md](V3_1_52_RELEASE_CANDIDATE.md).
