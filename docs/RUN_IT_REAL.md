# Run the D Company ERP localhost developer sandbox on your Mac

This developer sandbox persists local test data so engineers can exercise the
real backend behavior. It uses development credentials and plaintext HTTP and
is bound to `127.0.0.1`; it is **not approved for café, payment, employee, or
other production data**, and it must not be exposed to WiFi/LAN devices. Live
operations use the hardened production VM installer in
[`FREE_DEPLOY.md`](FREE_DEPLOY.md).

Three steps total. ~5 minutes the first time, ~10 seconds every time after.

---

## Step 1 — Install Docker Desktop (one-time, free)

Docker is the standard tool for running self-contained apps on a Mac. It's free for personal and small-business use.

1. Go to **<https://www.docker.com/products/docker-desktop/>**
2. Click "Download for Mac" — pick **Apple Silicon** if your Mac is M1/M2/M3/M4, or **Intel** otherwise. (Apple menu → About This Mac will tell you.)
3. Open the `.dmg`, drag **Docker** into Applications.
4. Open Docker from Applications. The first launch asks for your Mac password (it needs to install a system extension).
5. Wait for the whale icon in the menu bar to **stop animating** — that means Docker is ready.

That's it. You only do this once.

## Step 2 — Double-click the installer

In the project folder, find **`Install D Company ERP (Mac).command`**.

1. **Right-click** the file → **Open** → click **Open** in the security dialog.
   - (Macs block downloaded scripts by default. The right-click bypass only needs to happen the first time.)
2. A Terminal window opens. You'll see:
   - "Docker is running" ✓
   - "Building images and starting containers…" — this is the slow part on first run (downloading Postgres, building your backend). **Grab a coffee.**
   - "Waiting for backend (running migrations + seeds inside container)…"
   - "Opening http://localhost:5173"
3. Your browser pops up at <http://localhost:5173>. **Log in with:**
   - Email: `owner@dcompany.local`
   - Password: the `SEED_OWNER_PASSWORD` stored in the mode-`0600` local `.env`
     (the installer never prints or logs it; retrieve it through your local
     password-manager/secret workflow)
   - ⚠️ **Change this password immediately** from the Staff screen.

## Step 3 — Use it

You're on the persistent localhost sandbox now. The yellow **"Demo mode"** badge is gone.

What's wired to the real backend in this build:

| Screen | Status |
|---|---|
| **Login + Auth** | ✅ Real Argon2id-hashed passwords, JWT tokens, role-based permissions |
| **POS** | ✅ Live menu from DB, real order creation, real invoice number from atomic counter, real CGST/SGST split, real round-off, receipt prints with everything Rule-46 mandates |
| **Tables** | 🟡 UI works, status changes don't persist yet — next session |
| **Menu (catalogue)** | 🟡 Loads from DB; editing is read-only for now |
| **Inventory** | 🟡 Static demo data; live coming next session |
| **Gaming** | 🟡 Timer runs in-browser; persistence to DB is next session |
| **Finance** | 🟡 Demo data; live P&L from journal entries comes next session |
| **OCR** | 🟡 UI ready; image processing pipeline next session |
| **Staff** | 🟡 Demo roster; live in next session |
| **Analytics** | 🟡 Demo charts; live aggregates next session |

The POS pipeline is connected end to end for development testing. This is not
authorization to take real sales on the insecure localhost stack.

---

## Network boundary

Every published service is deliberately bound to `127.0.0.1`. Do not change
those bindings to `0.0.0.0` or a LAN address: this sandbox has fixed development
database/object-store credentials, unauthenticated development Redis, and no
TLS. Tablet and multi-device operational testing belongs on a production-like
secured environment, not this Compose file.

---

## Day-to-day commands

Run these in Terminal, inside the project folder.

| What | Command |
|---|---|
| Stop the app | `docker compose down` |
| Start again (data preserved) | Double-click the installer, or `docker compose up -d` |
| See live backend logs | `docker compose logs -f backend` |
| See live database logs | `docker compose logs -f postgres` |
| Restart just the backend (after a code change) | `docker compose restart backend` |
| Permanently tear down test data | Follow **Removing the sandbox completely** below; there is intentionally no one-line recovery command |
| Open the backend's API docs | <http://localhost:8000/docs> |

---

## Backups

### Local Mac (manual)

Your data lives in a Docker volume named `d-company-erp_pgdata`. To back it up to a `.sql.gz` file in your Downloads folder:

```bash
docker compose exec postgres pg_dump -U erp erp | gzip > ~/Downloads/dcompany-backup-$(date +%F).sql.gz
```

To restore from one of these backups:

```bash
gunzip < ~/Downloads/dcompany-backup-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U erp erp
```

This `gunzip | psql` restore only works for backups made with the plain-SQL command directly above. It will **not** work on the production off-site backups described below — those are a different (custom) dump format that `psql` can't read.

### Production recovery is intentionally not documented here

Never restore a production dump in place from this localhost guide. Production
recovery requires a scheduled write gate, drained device outboxes, verified
checksums, a full disposable restore proof, exact migration-head handling,
traffic isolation, and a rehearsed rollback. Follow only the approved recovery
contract in
[`backend/docs/REMOTE_ASSISTANCE_API.md`](../backend/docs/REMOTE_ASSISTANCE_API.md)
with an authorized operator; do not improvise `pg_restore --clean` against a
live database.

---

## Moving beyond the localhost sandbox

The Mac Compose path is for development/trial data only, never a temporary
single-café production server. For staff, payment, multi-device, remote-support,
or public-internet use, move to the hardened production VM path before entering
real data. That is required when you want:

- The café to keep working when your Mac is off
- Two branches to share the same data
- A delivery partner integration that hits us from the public internet
- Cloud backups happening automatically

Use [`FREE_DEPLOY.md`](FREE_DEPLOY.md) and its exact-commit
`install-on-vm.sh` procedure. [`CLOUD_DEPLOY.md`](CLOUD_DEPLOY.md) is explicitly
an unsupported planning document for Code17 until its managed Redis ACL,
secrets, backup, and rollback controls are provider-tested.

---

## Troubleshooting

**The installer says "Docker is not installed."**
You skipped Step 1. Install Docker Desktop, wait for the whale icon to stop animating, then double-click the installer again.

**The installer says "Docker is installed but not running."**
Open Docker Desktop from Applications. Wait for the whale icon to stop animating. Try again.

**Browser opens but says "This site can't be reached."**
First-time build is slow (containers downloading + compiling). Wait 60 more seconds and refresh.

**The login button says "invalid credentials."**
Use the `SEED_OWNER_PASSWORD` value in your local mode-`0600` `.env` only if
you have not changed it. If the protected owner password was changed and lost,
use the normal OTP recovery or run this interactive, no-echo, role-preserving
local-console reset:

```bash
docker compose exec backend python -m scripts.reset_owner_password
```

It targets only `SEED_OWNER_EMAIL`, requires the existing active
`super_owner`, preserves roles, rotates `auth_version`, revokes refresh
sessions, and audits the action. Never delete volumes to recover a credential.

**Port 8000 or 5173 is already in use.**
Another app is using that port. Find it with `lsof -nP -iTCP -sTCP:LISTEN | grep 5173`. Quit that app, or edit `docker-compose.yml` to change the port mapping (e.g. `"5174:80"`).

**I see "Operation not permitted" copying files.**
macOS quarantine. Right-click the installer → Open instead of double-clicking.

**The cart's "Total (est.)" doesn't match the final receipt total.**
That's expected and correct. The cart shows the simple sum; the backend computes the real CGST/SGST split and round-off when you charge, which is what gets stamped on the GST invoice.

**docker compose up fails with "Cannot start: address already in use."**
Stop the conflicting container: `docker ps`, find the one on the port, `docker stop <id>`. Or run `docker compose down` first.

---

## Removing the sandbox completely

This is permanent test-data teardown, never password recovery. First create and
validate a final custom-format backup; then require an explicit typed
confirmation before deleting volumes:

```bash
mkdir -p "$HOME/Downloads/d-company-erp-backups"
BACKUP="$HOME/Downloads/d-company-erp-backups/local-$(date -u +%Y%m%dT%H%M%SZ).dump"
docker compose exec -T postgres pg_dump -U erp -Fc erp > "$BACKUP"
test -s "$BACKUP"
docker compose exec -T postgres pg_restore --list < "$BACKUP" >/dev/null
shasum -a 256 "$BACKUP" > "$BACKUP.sha256"

printf 'Type DELETE LOCAL ERP DATA to destroy the sandbox volumes: '
read -r CONFIRM
test "$CONFIRM" = 'DELETE LOCAL ERP DATA' || { echo 'Cancelled.'; exit 1; }
docker compose down -v
```

Keep the backup and checksum until you have independently restored or reviewed
them. Image deletion is optional and does not need to be coupled to data loss.
