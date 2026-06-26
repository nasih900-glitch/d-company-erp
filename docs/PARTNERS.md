# Adding partners (India + Canada)

D Company ERP runs from a single droplet in Bangalore — **anyone in the world with a login can use it**. Your partners in India and Canada open the same URL, install it as a PWA, and they're in.

## Step 1 — Create partner accounts (one SSH command per partner)

You said you want partners to have **full owner-level access** (not read-only). The commands below create them with the `owner` role.

SSH into your droplet:

```bash
ssh -i ~/.ssh/dcompany_erp root@YOUR_DROPLET_IP
```

Then for each partner, run:

```bash
# ===== Partner 1: India =====
docker compose -f /opt/d-company-erp/docker-compose.prod.yml exec -T backend \
  python -m scripts.create_user \
  --email "partner-india@example.com" \
  --name "Partner Name In India" \
  --role owner \
  --password "$(openssl rand -base64 24)"

# ===== Partner 2: Canada =====
docker compose -f /opt/d-company-erp/docker-compose.prod.yml exec -T backend \
  python -m scripts.create_user \
  --email "partner-canada@example.com" \
  --name "Partner Name In Canada" \
  --role owner \
  --password "$(openssl rand -base64 24)"
```

**Replace the email/name/password** with their real values before pasting.

Each command prints `Created user <email>` followed by `• role=owner` and `• company=D Company`. That confirms they're in.

## Step 2 — Email the partners their access

Send each partner an email like this:

> **Subject:** Your D Company ERP login
>
> Hi [name],
>
> You now have access to D Company ERP — the café + gaming-lounge management system. You can see today's revenue, gaming sessions, events, P&L, and reports from anywhere in the world.
>
> 1. Open **https://dcompany.duckdns.org** on your phone or laptop.
> 2. Email: `[partner email]`
> 3. Password: `[temporary password you generated]` (please change it immediately)
> 4. Once logged in, go to **Staff → your account → change password** and set something memorable.
>
> **Install as a phone app (optional):**
> - **iPhone (Safari):** open the URL → Share button → "Add to Home Screen" → Add.
> - **Android (Chrome):** open the URL → tap ⋮ menu → "Install app".
> - **Mac/Windows:** open in Chrome or Edge → click the install icon in the address bar.
>
> After installing, the D Company icon appears on your home screen and opens full-screen like a native app.
>
> Anything I can help with, let me know.

## Step 3 — What partners can do once they log in

With the **owner** role, they have the same access as you:

- **POS** — take orders, see live cart, charge
- **Tables** — manage floor plan, reservations
- **Menu** — edit prices, items, categories
- **Inventory** — see stock levels, low-stock alerts
- **Gaming** — start/stop PS5 sessions, see revenue
- **Events** — create screenings, sell tickets, check-in at door
- **Finance** — see daily P&L, expenses, partner balance
- **OCR** — upload bills, approve into expenses
- **Staff** — add/remove users, change roles
- **Analytics** — KPIs, top items, trends
- **Reports** — daily/monthly/quarterly/yearly P&L, **print** or **save as PDF** or **push to Google Sheets**
- **Settings → Google Sheets** — connect the integration once, all entries flow into your sheet

⚠️ **With owner access, your partners can also:**
- Add or remove other users
- Change any password
- Delete data
- Modify the menu and prices

If you want stricter control later, switch them to the `partner` role (read-only finance + capital write) or `auditor` role (read-only everything):

```bash
# Demote to partner (finance read + capital write, no POS)
docker compose -f /opt/d-company-erp/docker-compose.prod.yml exec -T backend \
  python -m scripts.create_user \
  --email "partner@example.com" --name "..." \
  --role partner --password "..."

# Or auditor (read-only)
docker compose -f /opt/d-company-erp/docker-compose.prod.yml exec -T backend \
  python -m scripts.create_user \
  --email "partner@example.com" --name "..." \
  --role auditor --password "..."
```

The `create_user` script is idempotent: re-running it with the same email **updates** the existing user's name/password/role.

## Step 4 — Daily / monthly reports for partners

Open **Reports** in the sidebar. Pick the period (daily/monthly/quarterly/yearly), pick the date or fiscal year, and you get a real-time P&L.

- **Print or save PDF:** click "Print / Save PDF" → Cmd+P → choose printer or "Save as PDF".
- **Email to partner:** save as PDF first, then attach to email.
- **Auto-sync to Google Sheets:** click "Push to Sheets" → the report appears as a new row in the **ERP Entries** tab of your Google Sheet. Partners with access to the sheet see it without logging into the ERP.

## Step 5 — Partner can change their own password

Once logged in:

1. Click **Staff** in the sidebar
2. Click their own name
3. **Change password**
4. Pick something they'll remember; the temporary password we created is gone.

## Roles reference

| Role | POS | Tables | Inventory | Gaming | Finance | Reports | Staff write |
|---|---|---|---|---|---|---|---|
| `owner` | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| `partner` | — | — | — | — | ✓ (capital write) | ✓ | — |
| `manager` | ✓ | ✓ | ✓ | ✓ | — | — | ✓ |
| `cashier` | ✓ (POS only) | ✓ (read) | ✓ (read) | — | — | — | — |
| `kitchen` | ✓ (read) | — | — | — | — | — | — |
| `gaming_supervisor` | ✓ | ✓ (read) | — | ✓ | — | — | — |
| `auditor` | ✓ (read) | ✓ (read) | ✓ (read) | ✓ (read) | ✓ (read) | ✓ | — |

## If a partner forgets their password

You (with owner access) can reset it any time with the same `create_user` command — it overwrites the password.
