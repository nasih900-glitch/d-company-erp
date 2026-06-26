# Going live — completely free, forever ($0)

The truly-zero-cost path:

- **Domain** — `dcompany.duckdns.org` (DuckDNS, free forever, no renewal hassle)
- **Server** — Oracle Cloud Always Free (4 ARM cores, 24 GB RAM, free forever)
- **HTTPS** — Let's Encrypt via Caddy (automatic, free forever)
- **Database** — Postgres in a Docker container on the same VM (free)
- **App on each device** — PWA install (free, no app store)

**Total cost: ₹0/month, forever.**

When your café grows and you want a real `dcompany.in` domain (₹400/yr) or App Store listings (₹8,200/yr Apple + ₹2,100 one-time Google), the migration is one command — see the bottom of this guide.

**Total time: ~90 minutes**, most of it waiting for Oracle account verification.

---

## Step 0 — What you need

- A laptop with internet
- A credit/debit card (Oracle uses it ONLY to verify you're a real person; the free tier cannot be billed even if you try)
- Indian phone number for the OTP
- A Google account (for DuckDNS login — saves you signup hassle)
- 90 minutes of focus

---

## Step 1 — Get your free domain from DuckDNS (3 minutes)

1. Open **<https://www.duckdns.org>**.
2. Click **Sign In** at the top → choose **Google** (or GitHub/Reddit/Twitter — whichever you have).
3. After signing in, you'll see a dashboard with a "domains" section and a "Token" at the top.
4. In the **sub domain** box, type your desired name. Examples:
   - `dcompany`
   - `dcompany-erp`
   - `dcompanycafe`
   - `dcompany-kerala`
   Click **add domain**.
5. ✓ You now own `dcompany.duckdns.org` (or whatever name you picked). **Free, forever, no renewal.**
6. **Copy your token** at the top of the page — looks like `aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee`. You'll need it in Step 4.

**Reply with the name you picked** (e.g., `dcompany.duckdns.org`). I'll tailor the rest of this guide to your exact subdomain.

---

## Step 2 — Sign up for Oracle Cloud Always Free (20 min)

1. Go to **<https://signup.oraclecloud.com>**.
2. Click **Start for free**.
3. Fill in:
   - **Country**: India
   - **Email**: your real email
   - Click **Verify my email**.
4. Check email → click verify link → set a strong password.
5. Continue:
   - **Cloud Account Name**: `dcompany-erp` (or any unique name — used only in Oracle URLs)
   - **Home Region**: **Mumbai (ap-mumbai-1)** ⚠️ *You can't change this later. Pick Mumbai for India.*
   - **Address**: your real address
   - **Mobile**: your Indian phone → OTP
6. Enter the SMS code.
7. **Payment verification**: enter a card. Oracle charges ₹0 (might do a temporary auth hold that drops in 24h). **The card is only for fraud prevention. Always Free cannot be charged.**
8. Submit. Account creation takes 2–10 minutes. You'll get an email.

### India-specific gotcha

If you get "out of capacity" when creating the VM in Step 3:
- Try again early morning (6–8 AM IST) — Mumbai region opens up
- Or fall back to Hyderabad — still fast for Kerala

---

## Step 3 — Create the VM (10 min)

Log in to **<https://cloud.oracle.com>** with your new Oracle account.

1. ☰ menu → **Compute → Instances → Create instance**
2. **Name**: `d-company-erp`
3. **Image and shape** → **Edit** → **Change shape**:
   - **Ampere** (ARM)
   - Shape: **VM.Standard.A1.Flex**
   - OCPUs: drag to **4**
   - Memory: drag to **24 GB**
4. **Image** → **Change image** → **Canonical Ubuntu 22.04** (minimal is fine)
5. **Networking**: leave defaults, confirm "Assign public IPv4" = YES
6. **SSH keys** → **Generate a key pair for me** → **Download both files** (private + public). Save to `~/Documents/oracle-keys/`.
7. **Create**. Wait ~90 seconds for status → Running.
8. **Copy the Public IP** at the top of the page. Looks like `152.67.83.45`. You'll need it twice in the next steps.

If you get "out of capacity":
- Reduce OCPUs to 2 / Memory to 12 GB → retry → still works, still free.

---

## Step 4 — Point your DuckDNS subdomain at the VM IP (2 min)

Back at **<https://www.duckdns.org>** dashboard:

1. Find your subdomain row (`dcompany`)
2. In the **current ip** column, paste your Oracle VM public IP (`152.67.83.45`).
3. Click **update ip** on that row.

DuckDNS propagates in **~10 seconds**. (No 2-hour DNS wait like real domains.) Test at <https://dnschecker.org> — paste `dcompany.duckdns.org` and confirm it resolves to your VM IP.

---

## Step 5 — Open the firewall in Oracle (5 min)

Oracle blocks all inbound traffic by default. We need ports 22, 80, 443 open.

1. On your VM instance page, click the **subnet** name (under Primary VNIC).
2. Click the **Security List** (`Default Security List for vcn-xxxxx`).
3. **Add Ingress Rules** → add these three (one at a time, OR all in one form):

| Source CIDR | IP Protocol | Dest Port | Description |
|---|---|---|---|
| `0.0.0.0/0` | TCP | 22 | SSH |
| `0.0.0.0/0` | TCP | 80 | HTTP |
| `0.0.0.0/0` | TCP | 443 | HTTPS |

4. Click **Add Ingress Rules** at the bottom of the form.

The `install-on-vm.sh` script handles Ubuntu's internal iptables block in Step 7. You don't touch that.

---

## Step 6 — SSH into the VM (3 min)

In Terminal on your Mac:

```bash
cd ~/Documents/oracle-keys
chmod 600 ssh-key-*.key            # SSH refuses world-readable keys

# Replace 152.67.83.45 with YOUR VM's public IP
ssh -i ssh-key-*.key ubuntu@152.67.83.45
```

First-time prompt: type `yes`.

You're inside the VM. The prompt becomes `ubuntu@d-company-erp:~$`.

---

## Step 7 — Upload the code to the VM (3 min)

Open a **second Terminal window on your Mac** (keep the SSH session open in the first one):

```bash
cd "/path/to/d-company-erp"

# Replace 152.67.83.45 with YOUR VM IP
rsync -avz \
  --exclude 'node_modules' --exclude '.venv' \
  --exclude 'demo-dist*' --exclude 'preview-app' \
  --exclude '*.zip' --exclude '__pycache__' \
  -e "ssh -i ~/Documents/oracle-keys/ssh-key-*.key" \
  ./ ubuntu@152.67.83.45:/tmp/d-company-erp/
```

Takes ~30 seconds. Then back in the **SSH window** (first terminal):

```bash
sudo mv /tmp/d-company-erp /opt/d-company-erp
sudo chown -R ubuntu:ubuntu /opt/d-company-erp
cd /opt/d-company-erp
ls
```

You should see `backend/`, `frontend/`, `docker-compose.prod.yml`, etc.

---

## Step 8 — Run the installer (10 min)

Still in the SSH session:

```bash
cd /opt/d-company-erp
sudo bash infra/scripts/install-on-vm.sh dcompany.duckdns.org
```

Replace `dcompany.duckdns.org` with your actual DuckDNS subdomain.

The installer:
1. Installs Docker
2. Opens ports 80 + 443 in iptables (Oracle Ubuntu gotcha)
3. Generates strong random secrets (saved to `.env`)
4. Builds the containers (~5 min first time)
5. Starts the stack
6. Runs database migrations + seeds Kerala/India reference data
7. Waits for Caddy to issue your Let's Encrypt HTTPS certificate (~30 seconds)
8. **Prints your owner login email + password**

**Write down the password it prints** — only shown once.

---

## Step 9 — First login (2 min)

Open **`https://dcompany.duckdns.org`** in your browser (yours will be different).

✅ You should see the **D Company login screen** — full HTTPS, real lock icon.

- Login: `owner@dcompany.duckdns.org` + the password the installer printed
- Go to **Staff** → reset your own password to something you'll remember
- Sign out, sign in with the new password
- The default seed password is gone forever.

---

## Step 10 — Install the app on your phone (1 min per device)

This is the "make it an app" step — works because we built the PWA properly.

**On your iPhone:**
1. Open `https://dcompany.duckdns.org` in **Safari** (must be Safari, not Chrome)
2. Tap the **Share** button (square with arrow, at the bottom)
3. Scroll down → tap **Add to Home Screen**
4. Tap **Add**

**On Android:**
1. Open in **Chrome**
2. The browser automatically prompts "Install app" — tap it
3. Or: tap ⋮ menu → **Install app**

**On your Mac/Windows laptop:**
1. Open in **Chrome** or **Edge**
2. Look at the address bar → there's a small install icon (computer with down arrow)
3. Click it → **Install**

You now have a D Company app icon. Tap it → full-screen app, no browser bar. **It's an app.**

---

## Step 11 — Create logins for your team (3 min)

Back in the VM's SSH session:

```bash
cd /opt/d-company-erp

# Example — your manager
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email manager@example.com --name "Manager Name" \
  --role manager --password "$(openssl rand -base64 24)"

# Example — a cashier
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email cashier@example.com --name "Cashier Name" \
  --role cashier --password "$(openssl rand -base64 24)"

# Example — your friend overseas (read-only)
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email auditor@example.com --name "Auditor Name" \
  --role auditor --password "$(openssl rand -base64 24)"
```

**Roles:**
- `owner` — you, full access
- `manager` — POS, inventory, gaming, events, staff write
- `cashier` — POS only
- `kitchen` — kitchen display only
- `gaming_supervisor` — gaming + POS
- `auditor` — read-only everything (perfect for partners/CAs/friends)

Email each person their URL + login. **They install the PWA same way as Step 10**, each on their own device.

---

## Step 12 — Set up automatic backups (3 min, do this today)

On the VM:

```bash
sudo mkdir -p /root/backups
cat <<'CRON' | sudo tee /etc/cron.weekly/erp-backup
#!/bin/bash
cd /opt/d-company-erp
DATE=$(date +%F)
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U erp erp | gzip > /root/backups/erp-${DATE}.sql.gz
find /root/backups -name "erp-*.sql.gz" -mtime +84 -delete
CRON
sudo chmod +x /etc/cron.weekly/erp-backup
```

Cron now backs up the database every Sunday and keeps 12 weeks of history.

**Monthly: copy a backup to your Mac:**

```bash
# Run this from your Mac (not the VM)
scp -i ~/Documents/oracle-keys/ssh-key-*.key \
    'ubuntu@152.67.83.45:/root/backups/erp-*.sql.gz' \
    ~/Documents/D-Company-Backups/
```

---

## ✓ You're done. The whole thing is live for ₹0.

- **Real URL** — `https://dcompany.duckdns.org`
- **Real HTTPS** — auto-renewed by Caddy/Let's Encrypt
- **Real database** — Postgres on the VM, backed up weekly
- **Real users** — each person has their own login
- **Real PWA** — installable as an app on every device
- **Works from anywhere in the world** — friend in another country opens the URL, logs in, sees live data
- **Costs ₹0** — no domain fees, no hosting fees, no app store fees

---

## When you're ready to upgrade

### Buy a real domain (recommended after 3-6 months of use)

When you want `dcompany.in` instead of `dcompany.duckdns.org`:

1. Buy the domain at Namecheap / Hostinger (₹400-700/year).
2. Point its A record at your existing Oracle VM IP.
3. On the VM, edit `/opt/d-company-erp/.env`:
   ```bash
   nano /opt/d-company-erp/.env
   # Change DOMAIN=dcompany.duckdns.org to DOMAIN=dcompany.in
   # Change CORS_ORIGINS to ["https://dcompany.in","https://www.dcompany.in"]
   ```
4. Restart:
   ```bash
   docker compose -f docker-compose.prod.yml --env-file .env up -d
   ```
5. Caddy issues a fresh Let's Encrypt cert for the new domain automatically.

Your old DuckDNS URL still works during transition — keep both for a week, then drop DuckDNS.

### App Store deployment

When you want to be on Apple App Store + Google Play:

- **Costs:** ₹8,200/year Apple Developer Program + ₹2,100 one-time Google Play Console.
- **Already prepared:** the Capacitor scaffolding exists from a previous session. We have `frontend/capacitor.config.ts`, the Android + iOS shells ready to wrap the same React code.
- **Process:**
  1. Pay the fees.
  2. Build the `.apk` and `.aab` on any machine (Linux, Mac, Windows) — instructions in [`docs/DISTRIBUTION.md`](DISTRIBUTION.md).
  3. Build the `.ipa` on a Mac with Xcode (only step that needs a Mac).
  4. Upload to App Store Connect (iOS) and Play Console (Android).
  5. Wait 1–4 weeks for review.

**The PWA you have today is essentially the same product** — users feel zero difference. App Store is for discovery and trust badges, not better UX. Most cafés never bother.

---

## Day-to-day reference

SSH in: `ssh -i ~/Documents/oracle-keys/ssh-key-*.key ubuntu@YOUR_VM_IP`

```bash
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml ps                 # status
docker compose -f docker-compose.prod.yml logs -f backend    # live logs
docker compose -f docker-compose.prod.yml restart backend    # restart one service
docker compose -f docker-compose.prod.yml down               # stop everything
docker compose -f docker-compose.prod.yml up -d              # start again
```

Create users any time:
```bash
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user --help
```

Update to new code from your Mac:
```bash
# On Mac (in project folder):
rsync -avz --exclude 'node_modules' --exclude 'preview-app' --exclude '*.zip' \
  -e "ssh -i ~/Documents/oracle-keys/ssh-key-*.key" \
  ./ ubuntu@YOUR_VM_IP:/tmp/d-company-erp/

# Then SSH in and:
sudo rsync -av /tmp/d-company-erp/ /opt/d-company-erp/
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml up -d --build
```

---

## Troubleshooting

**"This site can't be reached" after install**
DuckDNS propagation can take up to 1 minute (vs hours for real DNS). Wait, refresh.

**Caddy can't get HTTPS cert**
- Confirm DuckDNS is pointing at your VM: `curl http://dcompany.duckdns.org` from your Mac should hit your VM
- Confirm port 80 is open in BOTH Oracle Security List AND iptables (`sudo iptables -L INPUT | grep -E "80|443"`)
- Caddy logs: `docker compose -f docker-compose.prod.yml logs caddy`

**Out of capacity creating VM**
Oracle Mumbai gets oversubscribed. Try at 6 AM IST. Or switch home region to Hyderabad next account.

**VM IP changed after restart**
Shouldn't happen with Always Free ephemeral IPs *while the VM is running*, but if you stop the VM you may get a new IP. Fix: update the IP at DuckDNS (Step 4 again). 30 seconds.

**Forgot owner password**
On the VM:
```bash
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email owner@example.com --name "Owner" --role owner --password "$(openssl rand -base64 24)"
```
