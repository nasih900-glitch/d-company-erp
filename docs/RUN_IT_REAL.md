# Run the real D Company ERP on your Mac

This is the version that **actually saves data**. Orders persist. Receipt numbers are sequential and never repeat. Multiple devices on your WiFi see the same data. Real Kerala GST math, real invoice numbering, real audit trail.

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
   - Password: the `SEED_OWNER_PASSWORD` value printed by the installer or stored in your local `.env`
   - ⚠️ **Change this password immediately** from the Staff screen.

## Step 3 — Use it

You're on the real app now. The yellow **"Demo mode"** badge is gone.

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

**The POS pipeline is end-to-end live.** That's the most important one for actually taking sales.

---

## Using it from a phone or tablet (same WiFi)

The app runs on your Mac but other devices on the same WiFi can use it too — that's how a real café works (cashier on a tablet, manager on a laptop, kitchen on a TV).

1. On your Mac, open Terminal and run:
   ```bash
   ipconfig getifaddr en0
   ```
   You'll see something like `192.168.1.42`.

2. On the other device, open Safari/Chrome and go to **`http://192.168.1.42:5173`**.

3. The app loads. Add it to the home screen (Share → Add to Home Screen on iPhone/iPad; ⋮ → Install app on Android Chrome) for an app-icon experience.

You can have as many devices using it as your WiFi can handle. They all hit the same backend on your Mac, so they all see the same orders, the same inventory, the same shift.

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
| **Wipe all data and start fresh** | `docker compose down -v` ← careful, deletes Postgres volume |
| Open the backend's API docs | <http://localhost:8000/docs> |

---

## Backups

Your data lives in a Docker volume named `d-company-erp_pgdata`. To back it up to a `.sql.gz` file in your Downloads folder:

```bash
docker compose exec postgres pg_dump -U erp erp | gzip > ~/Downloads/dcompany-backup-$(date +%F).sql.gz
```

To restore from a backup:

```bash
gunzip < ~/Downloads/dcompany-backup-YYYY-MM-DD.sql.gz | docker compose exec -T postgres psql -U erp erp
```

For real production, set up a cron job that does the above weekly and uploads to Google Drive / iCloud Drive.

---

## When you run out of your Mac

Running on your Mac is fine for trying the system and even for a single café for a while. But your Mac has to be **awake and on the WiFi** for the café to take orders. The day you want:

- The café to keep working when your Mac is off
- Two branches to share the same data
- A delivery partner integration that hits us from the public internet
- Cloud backups happening automatically

…you migrate the backend to a real cloud server. The code is identical — you just point the frontend at `https://api.yourdomain.com/api/v1` instead of `http://localhost:8000/api/v1`. See [`docs/CLOUD_DEPLOY.md`](CLOUD_DEPLOY.md) when you get there. (Cheapest free path: Oracle Cloud Always Free; cheapest paid: Render at ~$15/mo.)

---

## Troubleshooting

**The installer says "Docker is not installed."**
You skipped Step 1. Install Docker Desktop, wait for the whale icon to stop animating, then double-click the installer again.

**The installer says "Docker is installed but not running."**
Open Docker Desktop from Applications. Wait for the whale icon to stop animating. Try again.

**Browser opens but says "This site can't be reached."**
First-time build is slow (containers downloading + compiling). Wait 60 more seconds and refresh.

**The login button says "invalid credentials."**
Use the `SEED_OWNER_PASSWORD` value in your local `.env`. If you've already changed it and forgot, run: `docker compose down -v && docker compose up -d` to reset (deletes ALL data), then set a fresh `SEED_OWNER_PASSWORD` before seeding again.

**Port 8000 or 5173 is already in use.**
Another app is using that port. Find it with `lsof -nP -iTCP -sTCP:LISTEN | grep 5173`. Quit that app, or edit `docker-compose.yml` to change the port mapping (e.g. `"5174:80"`).

**I see "Operation not permitted" copying files.**
macOS quarantine. Right-click the installer → Open instead of double-clicking.

**The cart's "Total (est.)" doesn't match the final receipt total.**
That's expected and correct. The cart shows the simple sum; the backend computes the real CGST/SGST split and round-off when you charge, which is what gets stamped on the GST invoice.

**docker compose up fails with "Cannot start: address already in use."**
Stop the conflicting container: `docker ps`, find the one on the port, `docker stop <id>`. Or run `docker compose down` first.

---

## Removing it completely

```bash
docker compose down -v        # stop + delete volumes
docker rmi $(docker images "*d-company-erp*" -q)  # delete the built images
```

Now Docker is back to its initial state for this project.
