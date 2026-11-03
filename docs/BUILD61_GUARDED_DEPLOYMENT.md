# Build 61 guarded deployment

Build 61 / 3.1.54 is an unsigned source candidate based on immutable Build 60
commit `3401d1fb985f272c5cef568421cf3638c2c2e528`. Preserve that tag, source and
failed CI evidence. Apply the earlier preservation runbooks with this
candidate's independently reviewed source and eventual successful canonical
release provenance. Never reuse a predecessor's credential input, nonce,
scanner result, source admission or artifact expectation.

Require complete successful branch CI before creating the new release tag.
Canonical tagged CI, original signer, signed APK, manifest and canonical archive
remain separate prerequisites. A source review or prepared private helper is
not deployment or permission to execute a handoff.

Room remains 58 and backend schema remains 0094. The exact operational
transition still starts from deployed Build 54 / 3.1.46 at schema 0091. Only the
current candidate version changes to 3.1.54; normal-login authorization,
early-runtime and stopped-state preservation, complete backup/restore,
0091-to-0094 catalogue proof, image/environment admission and the forward-only
boundary retain their existing checks. Reconcile any different prior runtime.

Keep these source facts distinct: backend inventory hashes serialize
repository-relative paths followed by NUL and digest; the backend wire manifest
serializes digest, TAB, backend-relative POSIX path and LF in path order.
`CANDIDATE_BACKEND_MANIFEST_SHA256` must bind the latter. Protected-source
inventory is a third independently reviewed value. The actual Git-archive
regression must pass against the final committed source, without monkeypatching
its candidate constant.

Preserve all financial, attendance, payroll, audit, fleet and offline records.
Inherited pay estimates are read-only and post no payroll or ledger entries.
No new migration or Sheets activation is included. Configure actual staff
terms only through normal Owner-authorized controls after verified deployment.

Before staging or offering, complete application regression, original-signer
upgrade tests, update/download checks and guarded live acceptance. Keep the
minimum supported floor 54 and policy revision 4. Installation on the
unavailable physical tablet remains distinct from offering the verified update.
