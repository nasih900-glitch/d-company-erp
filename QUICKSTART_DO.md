# D Company ERP — 20-Minute DigitalOcean Quickstart

**Your domain:** `dcompany.duckdns.org` (already set up at DuckDNS)
**Your hosting:** DigitalOcean Bangalore Droplet, $12/month (~₹1,000)
**Total active time:** ~20 minutes of clicking + ~10 minutes waiting
**No capacity issues. No rate limits. No drama.**

You already have the DuckDNS subdomain ready, so we just need to:
1. Sign up for DigitalOcean (3 min)
2. Generate an SSH key on your Mac (1 min)
3. Create the droplet (3 min)
4. Update DuckDNS with the new IP (30 sec)
5. Paste the deploy command (1 paste, 10 min wait)
6. Open the app and log in (10 sec)

---

## Step 1 — DigitalOcean signup (3 min) ✋ You do this

1. Open **<https://m.do.co/c/free>** (this referral link gives you $200 in credit for 60 days — you'll basically run for free for the first 2 months)
2. **Sign up with email** (or Google / GitHub if you prefer)
3. Verify your email (click the link they send)
4. Enter **billing details**:
   - Card: your UK card OR Federal Bank with international enabled
   - Address: matches your card
5. Add a $5 minimum credit (won't be used immediately if you used the referral link above — you get $200 credit instead)
6. Wait ~1 min for verification

That's it. You're in.

---

## Step 2 — Generate SSH key on your Mac (1 min) ✋ You do this

Open **Terminal** on your Mac (Cmd+Space → type "Terminal" → Enter).

Paste these three lines, one at a time:

```bash
mkdir -p ~/.ssh
ssh-keygen -t ed25519 -f ~/.ssh/dcompany_erp -N "" -C "dcompany-erp"
cat ~/.ssh/dcompany_erp.pub | pbcopy
```

What just happened:
- Created an SSH key pair in `~/.ssh/dcompany_erp` (private) and `~/.ssh/dcompany_erp.pub` (public)
- The public key is now on your clipboard, ready to paste into DigitalOcean

Leave the Terminal window open — you'll need it again.

---

## Step 3 — Create the Droplet (3 min) ✋ You do this

In the DigitalOcean dashboard:

1. Top right → click **`Create`** → **`Droplets`**
2. **Choose Region**: scroll to **Asia** → click **Bangalore (BLR1)**
3. **Choose Image**: **Ubuntu** → version **22.04 (LTS) x64**
4. **Choose Size**:
   - Shared CPU → **Basic**
   - CPU options → **Regular**
   - **$12/month** (2 GB RAM, 1 vCPU, 50 GB SSD)
   - *(You can resize later if needed — just click and pick bigger.)*
5. **Authentication Method**: **SSH Key**
   - Click **`Add New SSH Key`**
   - In the big text box, **paste** (Cmd+V) — the public key you copied in Step 2
   - Name: `dcompany-mac`
   - Click **`Add SSH Key`**
6. **Hostname** (near the bottom): `d-company-erp`
7. Click **`Create Droplet`** (big blue button bottom-right)
8. Wait ~60 seconds. The status goes from "Creating" → ready.
9. **Copy the IPv4 address** shown on the droplet card. Looks like `159.65.142.38`.

---

## Step 4 — Update DuckDNS (30 sec) ✋ You do this

1. Open the **DuckDNS** tab (https://www.duckdns.org)
2. In the `dcompany` row, paste your droplet IP into the **current ip** field
3. Click **update ip**

DuckDNS propagates in ~10 seconds.

---

## Step 5 — Deploy (1 paste, ~10 min wait) 🤖 Automated

Back in the Terminal on your Mac. Paste this whole block — **change the IP** at the top to YOUR droplet's IPv4:

```bash
# ===== EDIT THIS LINE — paste your droplet IPv4 =====
DROPLET_IP="159.65.142.38"
# ====================================================

KEY=~/.ssh/dcompany_erp
PROJECT_DIR="/path/to/d-company-erp"

echo "==> Uploading code to droplet..."
rsync -avz --quiet \
  --exclude 'node_modules' --exclude '.venv' --exclude '__pycache__' \
  --exclude 'demo-dist*' --exclude 'preview-app' --exclude '*.zip' \
  -e "ssh -i $KEY -o StrictHostKeyChecking=accept-new" \
  "$PROJECT_DIR/" \
  root@$DROPLET_IP:/tmp/d-company-erp/

echo "==> Running installer (~8 minutes)..."
ssh -i $KEY root@$DROPLET_IP \
  "rm -rf /opt/d-company-erp && \
   mv /tmp/d-company-erp /opt/d-company-erp && \
   cd /opt/d-company-erp && \
   bash infra/scripts/install-on-vm.sh dcompany.duckdns.org"
```

What this does:
1. Uploads your code via rsync (~30 sec)
2. Installs Docker (~3 min)
3. Generates strong random secrets
4. Builds and starts the stack (~5 min)
5. Waits for Caddy to issue a real Let's Encrypt HTTPS certificate (~30 sec)
6. **Prints your owner email + password — write this down!**

---

## Step 6 — Open the app (10 sec) ✋ Verify

Open in your browser: **<https://dcompany.duckdns.org>**

You should see the D Company login screen with a real green HTTPS lock 🔒. Log in with:
- Email: printed by the installer
- Password: printed by the installer

**Immediately reset your owner password** (Staff → your account → Change password).

---

## Step 7 — Install as a PWA on every device (1 min each)

Same as before:
- **iPhone**: Safari → URL → Share → Add to Home Screen
- **Android**: Chrome → URL → "Install app" prompt or ⋮ → Install
- **Mac/Windows laptop**: Chrome/Edge → URL → install icon in address bar
- **Café kitchen TV**: Chrome → set URL as homepage → fullscreen

---

## ✅ Done

- **Live URL:** `https://dcompany.duckdns.org`
- **Hosted in:** DigitalOcean Bangalore (~20ms from Kerala)
- **You in UK** + **café staff in India** + **friends anywhere** = same URL, real-time
- **Total cost:** ~₹1,000/month (or **₹0 for the first 2 months** with the referral credit)
- **No drama:** never had to fight capacity, rate limits, or SSH key recovery

---

## Day-to-day reference

```bash
# SSH into your droplet
ssh -i ~/.ssh/dcompany_erp root@YOUR_DROPLET_IP

# Go to project folder
cd /opt/d-company-erp

# Common commands
docker compose -f docker-compose.prod.yml ps         # status
docker compose -f docker-compose.prod.yml logs -f    # live logs
docker compose -f docker-compose.prod.yml restart    # restart
docker compose -f docker-compose.prod.yml down       # stop everything
docker compose -f docker-compose.prod.yml up -d      # start again

# Create another user (cashier, manager, friend)
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email someone@example.com --name "Their Name" \
  --role auditor --password "$(openssl rand -base64 24)"

# Manual backup
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U erp erp | gzip > /root/erp-backup-$(date +%F).sql.gz
```

**Roles:** `owner`, `manager`, `cashier`, `kitchen`, `gaming_supervisor`, `auditor` (read-only).

---

## Backups (do this today)

```bash
# SSH into the droplet, then:
mkdir -p /root/backups
cat <<'CRON' > /etc/cron.weekly/erp-backup
#!/bin/bash
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml exec -T postgres \
  pg_dump -U erp erp | gzip > /root/backups/erp-$(date +%F).sql.gz
find /root/backups -name "erp-*.sql.gz" -mtime +84 -delete
CRON
chmod +x /etc/cron.weekly/erp-backup
```

Weekly Postgres dump, keeps 12 weeks of history. Copy backups to your Mac monthly:

```bash
# Run this from your Mac:
scp -i ~/.ssh/dcompany_erp "root@YOUR_DROPLET_IP:/root/backups/erp-*.sql.gz" \
    ~/Documents/D-Company-Backups/
```

---

## DigitalOcean essentials

- **Resize the droplet** anytime: Power down → Resize → pick bigger plan → Power up. ~2 min downtime.
- **Add another droplet** (e.g. for a second branch): same recipe, different domain.
- **Monitor usage**: Insights tab on the droplet shows CPU, RAM, bandwidth. Free.
- **Snapshots** (backups of the whole droplet): $0.05 per GB per month — for a 50 GB droplet, ~₹200/month. Optional but recommended.
- **Cancel anytime**: Destroy the droplet from the UI. Billing stops at the hour. You can always recreate from a snapshot.

---

## Troubleshooting

**SSH says "Permission denied (publickey)"**
The droplet wasn't created with your SSH key, OR you're using the wrong key path. Verify:
```bash
ls -l ~/.ssh/dcompany_erp      # should exist, owned by you, mode 600
cat ~/.ssh/dcompany_erp.pub    # should match what's in DO dashboard → Settings → SSH Keys
```

**"Connection refused"**
Droplet still booting (rare, usually under a minute) — wait 30 sec, retry.

**Caddy can't get HTTPS**
DNS hasn't propagated. Test: `curl http://dcompany.duckdns.org` — should reach the droplet. If not, recheck DuckDNS IP.

**"docker compose" command not found**
You're using an old Docker. The installer should have installed the latest. SSH in and run `docker compose version` to verify.

**App is slow**
Check `docker stats` on the droplet. If RAM is maxed (>1.8 GB), resize the droplet to $24/month (4 GB RAM) — takes 2 min, no data loss.

---

## When you outgrow this

| Trigger | Action |
|---|---|
| RAM constantly maxed | Resize droplet to 4 GB ($24/mo) |
| 2nd branch opens | Same droplet handles it. Add another `Branch` in the database. |
| Café revenue justifies it | Buy `dcompany.in` domain (₹400/yr), point DNS at droplet, edit one .env line |
| Want App Store listing | Capacitor scaffolding already in place — see `docs/DISTRIBUTION.md` |
| Want thermal printer + cash drawer | Tell me your printer model, I wire ESC/POS |
