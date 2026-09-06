# Deployment — Code17 supported paths

The root `docker-compose.yml` and `.env.example` are localhost-only development
assets. They contain development credentials/plaintext HTTP and are bound to
`127.0.0.1`; never expose or use them for café, employee, payment, or other
production data.

## Production VM

Use one of the maintained guides:

- [`FREE_DEPLOY.md`](FREE_DEPLOY.md) for the Oracle/Ubuntu path.
- [`DEPLOY_LIVE.md`](DEPLOY_LIVE.md) for the general Ubuntu/VPS path.

Both converge on `infra/scripts/install-on-vm.sh` from a clean exact reviewed
commit. Existing installations require a scheduled write outage and
`--maintenance-confirmed`; the known Code14 short-label and untouched Code16
placeholder-label deployments use only their documented, exact legacy bridge.
The installer validates/backfills
secrets, proves capacity and prior provenance, quiesces writes, drains outboxes,
verifies a full database restore, holds ingress closed through migration and
internal acceptance, and records an exact recovery snapshot.

Do not copy `.env.example`, run a direct Compose rebuild, replace `/opt`, or
regenerate an existing `.env` as a production deployment/upgrade.

## Managed cloud

[`CLOUD_DEPLOY.md`](CLOUD_DEPLOY.md) is an unsupported planning document for
Code17. No Render/Upstash/generic managed-cloud route is approved until a
provider-specific runbook proves the required `erp_backend` Redis ACL, private
networking, independent secret policy, quiesced backup/full restore, exact
migration rollback, and protected-owner acceptance.

## Recovery and health

Use `/readyz` for acceptance; `/healthz` proves process liveness only. The exact
Code16-head rollback, pre-/post-ingress failure boundaries, and production
recovery contract live in
[`backend/docs/REMOTE_ASSISTANCE_API.md`](../backend/docs/REMOTE_ASSISTANCE_API.md).
