# DigitalOcean quickstart — retired for Code17

This legacy guide is intentionally non-executable. Its old `rsync`/`rm -rf`
sequence could destroy the only production `.env` and rollback evidence before
the Code17 installer validates or snapshots an existing installation. Its
direct Compose restart and password-display instructions also bypass the
current maintenance, backup, secret, and acceptance contract.

For a fresh DigitalOcean VM, follow [`docs/DEPLOY_LIVE.md`](docs/DEPLOY_LIVE.md)
from a clean, exact reviewed release commit. For every existing installation:

1. preserve `/opt/d-company-erp`, its mode-`0600` `.env`, running immutable
   images, database volumes, and rollback evidence;
2. schedule a write outage and sync all tablet outboxes;
3. use `infra/scripts/install-on-vm.sh DOMAIN --maintenance-confirmed` from the
   reviewed commit (plus its documented verified Code16 bridge when required);
4. follow the backup, acceptance, exact-head rollback, and recovery contract in
   [`backend/docs/REMOTE_ASSISTANCE_API.md`](backend/docs/REMOTE_ASSISTANCE_API.md).

The installer never prints an owner password. Retrieve a newly generated value
only through the approved root host-secret workflow, then rotate it after first
login. Do not replace the installation directory, regenerate an existing env,
or use a direct Compose rebuild as an upgrade.
