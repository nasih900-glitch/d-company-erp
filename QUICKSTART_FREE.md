# D Company ERP — 20-Minute Free Quickstart

**Your domain:** `dcompany.duckdns.org` (already picked, configs pre-filled)
**Total cost:** ₹0/month, forever
**Time you have to spend:** ~20 minutes of clicking + ~15 minutes waiting

You do **5 things**, in this exact order. I cannot do them because each needs your identity (phone OTP, card verification). But everything between the manual steps is automated.

---

## Step 1 — DuckDNS (3 minutes) ✋ You do this

1. Open **<https://www.duckdns.org>**
2. Click **Sign in with Google** (top right)
3. In the **sub domain** box, type: **`dcompany`** → click **add domain**
4. **Copy your token** at the top of the page — looks like `aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee`
5. Leave this tab open. You'll come back in Step 4.

✅ You now own `dcompany.duckdns.org` (free, no renewal, no expiry).

---

## Step 2 — Oracle Cloud signup (15 minutes) ✋ You do this

**You're in the UK with a UK card — use UK address + UK card.** Oracle accepts it without issue. But still pick **Mumbai region** so your café in India has low latency.

1. Open **<https://signup.oraclecloud.com>**
2. **Start for free**
3. **Country: United Kingdom** (matches your card's billing address)
4. Email → click **Verify** → check email → click link → set password
5. Account name: **dcompany-erp** (just for Oracle's URL, doesn't matter)
6. ⚠️ **Region: Mumbai (ap-mumbai-1)** — CANNOT BE CHANGED LATER. *Critical: this is where your café data physically lives. Mumbai = ~30ms latency from Kerala. London region would be ~150ms — usable but sluggish for cashiers.*
7. Address: your UK address (matches the card)
8. Phone: UK mobile → OTP from SMS (UK numbers work fine — Oracle doesn't require an Indian number)
9. **Card: your UK debit card.** Oracle does a £0 verification hold (no actual charge). Always Free tier resources can't be billed even if you tried.
10. Submit. Account creation takes 2–10 min. Wait for the confirmation email.

> **If for any reason your UK card gets rejected:** fall back to your Indian Federal Bank debit card. Make sure international transactions are enabled (check the Federal Bank app → Card Controls → International on). If that also fails, ping me and we switch to the truly card-free path (Hugging Face Spaces).

⏳ While waiting for the account email, you can do Step 1 (DuckDNS) if you haven't.

---

## Step 3 — Create the VM (5 minutes) ✋ You do this

Log in at **<https://cloud.oracle.com>**:

1. ☰ → **Compute → Instances → Create instance**
2. **Name:** `d-company-erp`
3. **Image and shape** → **Edit** → **Change shape**:
   - **Ampere** (ARM) → **VM.Standard.A1.Flex**
   - OCPUs: **4** (drag slider all the way)
   - Memory: **24 GB**
4. **Image** → **Change image** → **Canonical Ubuntu 22.04** (minimal)
5. **SSH keys** → **Generate a key pair for me** → **Download both files**
   Save them to `~/Documents/oracle-keys/` on your Mac.
6. Click **Create**. Wait ~90 sec → status: **Running**.
7. **Copy the Public IP** at the top of the page. Looks like `152.67.83.45`. Write it down.

✅ If "Out of capacity": reduce OCPUs to 2 / Memory to 12 GB → still free, still works.

---

## Step 4 — Point DuckDNS at the VM (30 seconds) ✋ You do this

1. Back to the DuckDNS tab from Step 1.
2. Paste your Oracle VM Public IP into the **current ip** field next to `dcompany`.
3. Click **update ip**.

DuckDNS propagates in ~10 seconds (no 2-hour DNS wait).

---

## Step 5 — Open ports in Oracle (3 minutes) ✋ You do this

On your VM's instance page:

1. Click the **subnet** name (under "Primary VNIC")
2. Click the **Default Security List**
3. **Add Ingress Rules** → add these three:

| Source CIDR | IP Protocol | Destination Port |
|---|---|---|
| `0.0.0.0/0` | TCP | 22 |
| `0.0.0.0/0` | TCP | 80 |
| `0.0.0.0/0` | TCP | 443 |

4. **Add Ingress Rules** at the bottom.

✅ Manual steps done. Now I take over.

---

## Step 6 — Deploy (1 paste, ~10 min wait) 🤖 Automated

Open **Terminal** on your Mac. Paste this **whole block** in one go, but **change the IP** at the top to YOUR VM's Public IP:

```bash
# ===== EDIT THIS LINE — paste your Oracle VM Public IP =====
VM_IP="152.67.83.45"
# ===========================================================

KEY=~/Documents/oracle-keys/ssh-key-*.key
PROJECT_DIR="/path/to/d-company-erp"
chmod 600 $KEY 2>/dev/null

echo "==> Uploading code to VM..."
rsync -avz --quiet \
  --exclude 'node_modules' --exclude '.venv' --exclude '__pycache__' \
  --exclude 'demo-dist*' --exclude 'preview-app' --exclude '*.zip' \
  -e "ssh -i $KEY -o StrictHostKeyChecking=accept-new" \
  "$PROJECT_DIR/" \
  ubuntu@$VM_IP:/tmp/d-company-erp/

echo "==> Running installer (this takes ~8 minutes)..."
ssh -i $KEY ubuntu@$VM_IP \
  "sudo rm -rf /opt/d-company-erp && \
   sudo mv /tmp/d-company-erp /opt/d-company-erp && \
   sudo chown -R ubuntu:ubuntu /opt/d-company-erp && \
   cd /opt/d-company-erp && \
   sudo bash infra/scripts/install-on-vm.sh dcompany.duckdns.org"
```

What this does:
1. Uploads your code to the VM (~30 sec)
2. Installs Docker, opens iptables (~3 min)
3. Generates strong random secrets
4. Builds containers, runs migrations, seeds Kerala/India tax data (~5 min)
5. Waits for Caddy to get a real Let's Encrypt HTTPS cert (~30 sec)
6. **Prints your owner email + password — SAVE THIS**

---

## Step 7 — Open the app (10 seconds) ✋ Verify

Open in your browser: **<https://dcompany.duckdns.org>**

You should see a login screen with a real HTTPS lock icon. Log in with the credentials the installer printed.

**Reset the owner password immediately** (Settings → Staff → your account → change password).

---

## Step 8 — Install as an app on every device (1 min each)

Your café team uses a **mix** — manager laptop, cashier tablets/phones, kitchen TV. Same app, each device installs it.

**On a cashier's iPhone/iPad:** Safari → open URL → Share → Add to Home Screen → Add.
**On an Android tablet/phone:** Chrome → open URL → "Install app" prompt OR ⋮ → Install.
**On the manager's laptop (Mac/Windows):** Chrome or Edge → install icon in address bar → Install.
**On the kitchen TV:** open the URL in Chrome → make it the homepage → it's always there full-screen.

Each device shows the same data live. A cashier takes an order on a tablet → the manager sees it on their laptop → the kitchen TV displays it instantly. That's the cloud-hosted advantage.

Each person needs their own login (Step 11 below).

---

## ✅ Done

- **Live URL:** `https://dcompany.duckdns.org`
- **Real HTTPS:** auto-renewed forever
- **Real database:** Postgres saving every order in Mumbai (fast for Kerala café)
- **You in the UK** + **café staff in India** + **friends/partners anywhere** — all use the same URL, each with their own login.
- **Each device installs as a PWA** — looks like a real app on phones, tablets, laptops, the kitchen TV.
- **Total cost: ₹0/month, forever** (£0/month from your UK side too)

---

## Need to do something later? Quick reference

**Create another login** (your manager, a cashier, your friend):
```bash
ssh -i ~/Documents/oracle-keys/ssh-key-*.key ubuntu@YOUR_VM_IP
cd /opt/d-company-erp
docker compose -f docker-compose.prod.yml exec backend python -m scripts.create_user \
  --email someone@example.com --name "Their Name" \
  --role auditor --password "$(openssl rand -base64 24)"
```

**Roles:** `owner`, `manager`, `cashier`, `kitchen`, `gaming_supervisor`, `auditor` (read-only)

**Restart the app:** `docker compose -f docker-compose.prod.yml restart`
**Stop the app:** `docker compose -f docker-compose.prod.yml down`
**Start again:** `docker compose -f docker-compose.prod.yml up -d`
**View logs:** `docker compose -f docker-compose.prod.yml logs -f`

**Need backups?** See `docs/FREE_DEPLOY.md` step 12 — adds a weekly cron.

---

## I'm stuck — what do I do?

Reply with what you saw on screen. Common issues:

- **"Out of capacity"** in Oracle VM creation → Try 2 OCPUs + 12 GB. Or try at 6 AM IST tomorrow.
- **Can't SSH** → SSH key permissions: `chmod 600 ~/Documents/oracle-keys/ssh-key-*.key`. Or wrong IP.
- **Caddy can't get HTTPS cert** → DuckDNS hasn't propagated yet (wait 30 sec) OR ports 80/443 not open in Oracle Security List (Step 5).
- **Browser shows "Not secure"** → Cert is still being issued. Wait 60 seconds, refresh.

---

## When you outgrow free

- Want `dcompany.in` instead of `dcompany.duckdns.org`? → ₹400/yr, 30 seconds to switch (see `docs/FREE_DEPLOY.md` end)
- Want App Store listing? → ₹8,200/yr Apple + ₹2,100 Google + 2-4 weeks review. Capacitor scaffolding already ready in `frontend/capacitor.config.ts`.
- Want thermal printer + cash drawer? → say the word, I wire ESC/POS to your Epson/TVS printer.
