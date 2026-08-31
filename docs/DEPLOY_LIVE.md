# Going live — Oracle Cloud Always Free + your own domain

Step-by-step, "we're doing this together right now" guide to put D Company ERP at `https://yourdomain.com` for free, forever. Real Postgres, real HTTPS, multi-device, multi-user, real GST invoices that save.

**Total time: ~2 hours** (most of it waiting — Oracle account verification + DNS propagation).
**Total cost: ₹700/year for the domain. Everything else: ₹0 forever.**

---

## Step 0 — What you need before you start

- A laptop with internet
- A credit/debit card (Oracle uses it ONLY to verify you're human; they don't charge the Always Free tier)
- An Indian phone number (Oracle texts an OTP)
- An email address (Gmail is fine)
- ~₹700 in your account for the domain

---

## Step 1 — Decide your domain name (5 min)

Pick something short and memorable. Examples:

| Option | Cost (Namecheap, Cloudflare) | Notes |
|---|---|---|
| `dcompany.in` | ₹399/yr first year, ~₹700/yr after | Indian, short, professional |
| `dcompany.cafe` | ~₹1,800/yr | Best for a café business |
| `dcompanyerp.com` | ₹700–900/yr | Universal |
| `dcompany.cloud` | ₹1,500/yr | Modern |

**My recommendation:** `dcompany.in` — cheapest, India-coded, and you can use it forever.

**Buy it from:**
- [**Namecheap**](https://namecheap.com) — clean UI, accepts UPI/cards, no upselling
- [**Cloudflare Registrar**](https://dash.cloudflare.com) — sells at cost (no markup), but requires a Cloudflare account
- [**Hostinger**](https://hostinger.in) — Indian-friendly, accepts UPI directly

You don't need hosting from them. Just the domain. Whatever you buy, save the login.

> **Stop here, buy your domain, come back with the name in hand. Reply telling me the domain you chose so I can tailor the next steps.**

---

## Step 2 — Sign up for Oracle Cloud Always Free (20 min)

1. Go to **<https://signup.oraclecloud.com>**
2. Click **Start for free**
3. Fill in:
   - **Country**: India
   - **First/last name**: yours
   - **Email**: your real email (you'll click a verification link)
   - Click **Verify my email**
4. Check your email. Click the verify link. **Set a strong password.**
5. You're back on the signup page. Continue with:
   - **Cloud Account Name**: something unique like `dcompany-erp` (this is for the Oracle URL, doesn't matter much)
   - **Home Region**: **Mumbai (ap-mumbai-1)** — closest to Kerala, fastest. *(Important: you cannot change this later. Choose Mumbai.)*
   - **Address**: your real address
   - **Mobile**: your Indian phone — they send an OTP
6. Enter the SMS code.
7. **Payment verification** — enter card details. They charge ₹0 and refund any auth hold within 24 hours. **This card is ONLY for verification.** Always Free resources cannot be billed.
8. Submit. Account creation takes **2–10 minutes**. You'll get an email when it's ready.

### India-specific gotcha

Oracle's free tier was historically tight in Mumbai region. If you get **"out of capacity"** when creating a VM later:
- Try again at off-peak hours (9 AM IST is usually fine)
- Or pick **Hyderabad (ap-hyderabad-1)** as your region — also fast for Kerala

---

## Step 3 — Create an Always Free VM (15 min)

Once logged in to **<https://cloud.oracle.com>**:

1. Top menu → ☰ → **Compute** → **Instances**
2. Click **Create instance**
3. Fill in:
   - **Name**: `d-company-erp`
   - **Compartment**: leave default (your root compartment)
   - **Image and shape** → click **Edit** → click **Change shape**
     - Pick **Ampere** (the ARM option)
     - Shape: **VM.Standard.A1.Flex**
     - OCPUs: **4** (drag the slider to max)
     - Memory: **24 GB**
     - This is the full Always Free allocation. **Free forever, no card charges.**
   - **Image**: click **Change image** → pick **Canonical Ubuntu 22.04** (the minimal one)
4. **Networking** section:
   - Leave VCN + subnet at defaults (Oracle creates them automatically)
   - Make sure **Assign a public IPv4 address** = YES
5. **SSH keys** section:
   - Click **Generate a key pair for me**
   - **Download both** the private key (.key) and the public key (.pub). Save them in `~/Documents/oracle-keys/`. **You can never re-download them.**
6. Click **Create**.
7. Wait 60–120 seconds for status to go from "Provisioning" → "Running".
8. **Note down the Public IP address** at the top of the instance page. Example: `152.67.83.45`.

### If you get "Out of capacity"

Click **Edit shape** → reduce OCPUs to 2, memory to 12 GB → try again. You can resize back up later when capacity opens up. Still totally free.

---

## Step 4 — Open the firewall (5 min)

Oracle blocks all ports by default. We need 22 (SSH), 80 (HTTP), 443 (HTTPS).

1. On the instance page, click your subnet name (under "Primary VNIC" → "Subnet")
2. Click your **Security List** (default is `Default Security List for vcn-xxxxx`)
3. Click **Add Ingress Rules** → add three rules:

| Source CIDR | IP Protocol | Source Port | Dest Port | Description |
|---|---|---|---|---|
| `0.0.0.0/0` | TCP | (blank) | 22 | SSH |
| `0.0.0.0/0` | TCP | (blank) | 80 | HTTP |
| `0.0.0.0/0` | TCP | (blank) | 443 | HTTPS |
| `0.0.0.0/0` | UDP | (blank) | 443 | HTTP/3 (optional) |

4. Click **Add Ingress Rules** at the bottom.

The `install-on-vm.sh` script later handles Ubuntu's internal iptables block too. You don't need to touch that yourself.

---

## Step 5 — Point your domain at the VM (15 min + DNS propagation)

In your domain registrar's dashboard (Namecheap / Cloudflare / Hostinger):

1. Find **DNS** or **Manage DNS** or **Advanced DNS**.
2. Delete any existing A records pointing at the registrar's parking page.
3. Add two A records:

| Type | Host/Name | Value | TTL |
|---|---|---|---|
| A | `@` (or blank) | `152.67.83.45` (your VM's public IP) | 300 (5 min) |
| A | `www` | `152.67.83.45` (same) | 300 |

4. Save.

**DNS propagation takes 5 min to 2 hours.** Check progress at <https://dnschecker.org> — paste your domain, look for green checkmarks across the world. When most are green, you're ready.

---

## Step 6 — SSH into your VM (5 min)

On your Mac, open Terminal:

```bash
cd ~/Documents/oracle-keys
chmod 600 ssh-key-*.key      # SSH refuses to use keys that aren't user-private

# Replace 152.67.83.45 with YOUR VM's public IP from step 3
ssh -i ssh-key-*.key ubuntu@152.67.83.45
```

First time, it asks "Are you sure you want to continue connecting?" → type **yes**.

You're now inside the VM. Prompt looks like `ubuntu@d-company-erp:~$`.

---

## Step 7 — Get the code onto the VM (5 min)

The cleanest way is via a git repo, but if you don't want to set that up, you can `scp` (secure copy) the project folder directly from your Mac.

### Option A — Push code from your Mac (no GitHub needed)

In a **second Terminal window on your Mac** (keep the SSH session open):

```bash
cd "/path/to/d-company-erp"

# Copy the whole project to the VM. Takes ~30 seconds.
rsync -avz --exclude 'node_modules' --exclude '.venv' --exclude 'demo-dist*' \
      --exclude 'preview-app' --exclude '*.zip' \
      -e "ssh -i ~/Documents/oracle-keys/ssh-key-*.key" \
      ./ ubuntu@152.67.83.45:/tmp/d-company-erp/
```

Back in the **SSH session on the VM**:

```bash
sudo mv /tmp/d-company-erp /opt/d-company-erp
sudo chown -R ubuntu:ubuntu /opt/d-company-erp
cd /opt/d-company-erp
ls
```

You should see `backend/`, `frontend/`, `docker-compose.prod.yml`, etc.

### Option B — Clone from GitHub (if you've pushed the repo)

```bash
sudo apt-get install -y git
cd /opt
sudo git clone https://github.com/your-username/d-company-erp.git
sudo chown -R ubuntu:ubuntu d-company-erp
cd d-company-erp
```

---

## Step 8 — Run the installer (15 min)

```bash
cd /opt/d-company-erp
sudo bash infra/scripts/install-on-vm.sh yourdomain.in
```

Replace `yourdomain.in` with your actual domain.

The script:
1. Installs Docker (~3 min)
2. Opens ports 80 + 443 in iptables
3. Generates independent strong secrets in a root-readable, mode-`0600` `.env`
4. Validates the release commit, configuration, storage capacity, and Compose graph
5. Builds and starts the containers (~5 min first time)
6. Runs fatal-on-error migrations, seed, and role setup
7. Proves internal and public `/readyz` plus protected owner access
8. Prints the login email and secret-file location, but never the password

For a fresh install, retrieve `SEED_OWNER_PASSWORD` only through the approved
root host-secret workflow (for example, open the mode-`0600` `.env` in a
non-recorded root session and transfer the value directly into a password
manager). Never print it to stdout, shell history, logs, or a ticket.

If the cert step fails, it's almost always DNS. Wait 10 minutes and re-run.

---

## Step 9 — First login (2 min)

Open <https://yourdomain.in> in your browser.

- Login with the `SEED_OWNER_EMAIL` and `SEED_OWNER_PASSWORD` stored in the
  mode-`0600` `.env`
- Go to **Staff → Add user → set a new password for yourself**
- Sign out, sign back in with the new password
- You're done. The default seed password is gone.

---

## Step 10 — Create your friend's view-only account (3 min)

Back on the VM's SSH session:

```bash
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email friend@example.com \
  --name "Mo's Friend" \
  --role auditor
```

The command reads and confirms the temporary password with terminal echo
disabled and never prints it. Transfer it through an approved
password-manager/secret channel. It is create-only and refuses existing
accounts; use authenticated Staff/OTP workflows for later changes.

Send your friend the login through a separate approved channel:

> Hi! You can now see D Company ERP live at
> **<https://yourdomain.in>**
> Login: `friend@example.com`
> Password: `[temporary password you generated]`
> Tell them to change it on first login.

**Auditor role gets read-only access to:** POS receipts, tables, menu, inventory, gaming, finance, staff list, and analytics. They cannot create orders, modify the menu, change settings, or read the protected audit log. Audit-log authority remains restricted to the protected owner.

---

## Step 11 — Set up backups (5 min, do this today)

You don't want to lose data ever. Run this on the VM:

```bash
sudo mkdir -p /root/backups
cat <<'CRON' | sudo tee /etc/cron.weekly/erp-backup
#!/bin/bash
cd /opt/d-company-erp
DATE=$(date +%F)
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U erp erp | gzip > /root/backups/erp-${DATE}.sql.gz
# Keep last 12 weekly backups, delete older
find /root/backups -name "erp-*.sql.gz" -mtime +84 -delete
CRON
sudo chmod +x /etc/cron.weekly/erp-backup
```

For peace of mind, copy a backup to your Mac monthly:

```bash
# On your Mac:
scp -i ~/Documents/oracle-keys/ssh-key-*.key \
    ubuntu@152.67.83.45:/root/backups/erp-*.sql.gz \
    ~/Documents/D-Company-Backups/
```

---

## Step 12 — You're done. Now use it.

- **Your café staff** open <https://yourdomain.in> on any device (phone, tablet, laptop, café Mac). Cashier logins as cashier. Manager as manager. Multi-device live sync.
- **Your friend overseas** opens the same URL from anywhere in the world.
- **Orders, inventory, gaming sessions, events, receipts, GST math** — all save permanently in your Postgres database on the Oracle VM.
- **Google Sheets sync** — go to Settings → Google Sheets and paste your Apps Script URL (same as before). Now every order flows to your sheet, server-side, regardless of which device made the sale.
- **You can shut your laptop, your Mac, your café** — the system keeps running on the cloud VM.

---

## Day-to-day commands (reference)

SSH into the VM whenever you need:

```bash
ssh -i ~/Documents/oracle-keys/ssh-key-*.key ubuntu@152.67.83.45
cd /opt/d-company-erp
```

| What | Command |
|---|---|
| Status | `docker compose -f docker-compose.prod.yml ps` |
| Live logs | `docker compose -f docker-compose.prod.yml logs -f backend` |
| Restart backend | `docker compose -f docker-compose.prod.yml restart backend` |
| Stop existing containers | `docker compose -f docker-compose.prod.yml stop` |
| Start the same images/config | `docker compose -f docker-compose.prod.yml start` |
| Create another user | `docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user --help` |
| Manual backup | `docker compose -f docker-compose.prod.yml exec -T postgres pg_dump -U erp erp | gzip > /root/backups/manual-$(date +%F).sql.gz` |
| Upgrade to reviewed code | Use the maintenance installer procedure below; direct `rsync`/Compose rebuilds are unsupported |

### Safe production upgrade

Schedule a write outage and verify every tablet outbox has synced. On the VM,
move only to an independently reviewed, exact release commit; the installer
rejects dirty or ambiguous source:

```bash
cd /opt/d-company-erp
git fetch --tags --prune
git checkout --detach REVIEWED_40_HEX_RELEASE_COMMIT
test -z "$(git status --porcelain --untracked-files=normal)"
sudo bash infra/scripts/install-on-vm.sh yourdomain.in \
  --maintenance-confirmed
```

For an untouched Code16 installation whose running image has the known
historical placeholder revision label, use the verified one-time bridge:

```bash
sudo bash infra/scripts/install-on-vm.sh yourdomain.in \
  --maintenance-confirmed \
  --legacy-code16-revision 2ac3fc88e4ce14d0f05d049b443a6a09c387a78a
```

For the known Code14 production deployment whose immutable `3.1.3` backend
image records the historical short label `e5e90df` and whose database is at
`0058`, use its separate one-time bridge:

```bash
sudo bash infra/scripts/install-on-vm.sh yourdomain.in \
  --maintenance-confirmed \
  --legacy-code14-revision e5e90df5781e93681b8e9dcdd1ae9a6a5fb6a0b9
```

The bridge still exports and hashes the stopped immutable image against that
full commit before it creates rollback evidence. It rejects every other short
label, image version, database head or revision.

Do not use direct `rsync`, `git pull` on a dirty checkout, or a Compose rebuild
as an upgrade. The supported installer validates/backfills secrets, snapshots
the prior immutable images and exact database head, quiesces writes, proves a
full backup restore, and holds ingress closed through migration and internal
acceptance. Follow the rollback and recovery contract in
[`backend/docs/REMOTE_ASSISTANCE_API.md`](../backend/docs/REMOTE_ASSISTANCE_API.md).

---

## Troubleshooting

**"This site can't be reached" after install completes**
DNS hasn't fully propagated. Wait 30 minutes, try again. Confirm at dnschecker.org.

**Caddy says "no certificate available"**
Either DNS isn't pointing at the VM yet, or Oracle's firewall ports aren't open. Verify with `curl http://yourdomain.in` from your Mac. If it hangs, firewall. If you see Caddy's HTML, DNS is good.

**Out of memory / VM is slow**
Stop the existing containers, investigate pressure, and resize the VM if needed;
do not recreate a partial production stack ad hoc. Restart the same accepted
images/config with `docker compose -f docker-compose.prod.yml start`.

**Friend can't log in**
Use the centrally approved OTP password-reset flow or an authenticated owner
workflow in **Staff**. `scripts.create_user` intentionally refuses every
existing identity and never changes a password or role.

**I forgot the owner password**
Use the normal centrally approved OTP reset when available. For an emergency
local-console recovery, run the dedicated role-preserving command from an
interactive, non-recorded root session:
```bash
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml --env-file .env exec backend \
  python -m scripts.reset_owner_password
```

The command targets only `SEED_OWNER_EMAIL`, verifies the existing
`super_owner`, reads and confirms the secret without terminal echo, preserves
all roles, rotates `auth_version`, revokes active refresh sessions, and records
an audit event. It never creates or demotes an owner and fails closed if the
configured protected identity is missing. Never put a password in argv/history.

---

## What's next (when you're ready)

- **Real billing-machine integration** — wire up the Epson TM-T82 / TVS thermal printer for one-tap receipts and cash drawer
- **Razorpay / Cashfree payment gateway** — real UPI deep links so customers tap to pay instead of cashier typing the amount
- **Mobile app installers** (.dmg, .exe, .apk, .ipa) — `docs/DISTRIBUTION.md` already covers this
- **Multi-branch** — when you open a second location, just add another `Branch` row + cashier accounts. Schema already supports it.
- **Power BI export** — analytics module has the hook; we wire it when you want fancy partner dashboards
