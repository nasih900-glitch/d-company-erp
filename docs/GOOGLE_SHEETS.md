# Google Sheets integration

Connect D Company ERP to a Google Sheet so every order, ticket, and event change shows up there automatically — no manual export, no API keys, no Google Cloud project. **One Apps Script + one URL.**

This guide is the long version. Inside the app, **Settings → Google Sheets** walks you through the same steps with a "Copy script" button.

## What you'll have when you're done

Three tabs in your sheet, kept in sync in real time:

- **Orders** — Invoice No, Date, Time, Type, Table, Items, Item Count, Cashier, Taxable, CGST, SGST, IGST, Round-off, Total, Payment, GSTIN, Place of supply
- **Tickets** — Ticket No, Event, Event Date, Customer, Phone, Seat, Taxable, CGST, SGST, Paid, Sold At, Status, Checked-In At
- **Events** — Event ID, Name, Type, Screen, Starts At, Ends At, Capacity, Ticket Price, SAC, GST Rate, Status, Created At

Money columns are real numbers, so SUM, AVERAGE, pivot tables — all work natively. Rows are idempotent: if the same invoice number arrives twice (e.g. a manual reissue), the existing row is overwritten in place, never duplicated.

## Setup (5 minutes, once)

### 1. Open a Google Sheet

Use an existing sheet or create a new one at **<https://sheets.new>** (Google's blank-sheet shortcut).

### 2. Open the Apps Script editor

In the menu: **Extensions → Apps Script**. A new tab opens with an empty `Code.gs` file.

### 3. Paste the script

Two ways to get the script content:

- **From the app**: Open **D Company ERP → Settings → Google Sheets**, click **Copy the Apps Script** in step 3. Paste it into `Code.gs` (overwriting the empty `myFunction(){}`). Save (disk icon).
- **From the repo**: Open `integrations/google-sheets/Code.gs` in any text editor, copy all of it, paste into `Code.gs` in the script editor.

### 4. Deploy as a Web app

Click **Deploy → New deployment**. Settings:

| Field | Value |
|---|---|
| Type | **Web app** |
| Description | "D Company ERP webhook" (anything works) |
| Execute as | **Me** (your Google account) |
| Who has access | **Anyone with the link** |

Click **Deploy**. Google asks you to authorize the script — it needs permission to write to your sheet.

You'll see a warning: **"Google hasn't verified this app."** This is normal for any personal Apps Script. Click **Advanced → Go to … (unsafe)**, then **Allow**.

### 5. Copy the Web app URL

After deploying, Google shows you the **Web app URL**. It looks like:

```
https://script.google.com/macros/s/AKfycbxXXXXXXXXXXXXXXX/exec
```

Copy it.

### 6. Paste the URL into D Company ERP

Open **Settings → Google Sheets** in the ERP. Paste the URL into the input. Click **Test connection &amp; save**.

You should see a green "Connected" badge. The sheet now syncs live.

## Try it

In the app, go to **POS**, add a Cappuccino, charge it. Within ~2 seconds, your sheet's **Orders** tab has a new row.

Sell an event ticket from the **Events** screen → the **Tickets** tab gets a row. Create a new event → the **Events** tab gets a row.

## What you'll see in the sheet

Example after a few minutes of POS use:

| Invoice No | Date | Time | Type | Items | Total | CGST | SGST | Payment |
|---|---|---|---|---|---|---|---|---|
| D/MN/2026-27/00231 | 20-May-2026 | 18:42 | dine_in | 2× Cappuccino, 1× Croissant | 760.00 | 18.10 | 18.10 | upi |
| D/MN/2026-27/00232 | 20-May-2026 | 18:51 | takeaway | 1× Espresso, 1× Brownie | 380.00 | 9.05 | 9.05 | cash |

Money columns are real numeric values, so `=SUM(N:N)` on the Total column gives you today's gross instantly. Hand the sheet to your CA — they'll do the rest.

## How it actually works

The Apps Script you pasted exposes a `doPost(e)` endpoint. The ERP POSTs JSON of the shape:

```json
{
  "kind": "order" | "ticket" | "event" | "ping",
  "payload": { ...field values... }
}
```

The script:
1. Picks the right tab based on `kind`.
2. Creates the tab + headers if they don't exist yet.
3. Looks up the unique id (`invoice_no` / `ticket_no` / event id) in column A.
4. Overwrites that row if found, appends a new row otherwise.
5. Returns `{ok: true}`.

The ERP fires the POST as soon as the sale completes. It's a fire-and-forget HTTP call:

- Demo mode → from your browser (`frontend/src/lib/google-sheets.ts`).
- Live mode → from the FastAPI backend (`backend/app/services/integrations/google_sheets.py`), so even if 5 cashier tablets are taking orders simultaneously, the sheet stays current without any one of them needing to know the URL.

Failures are logged but never block the sale. The Settings page shows the last sync time and the last error (if any).

## Sharing the sheet

The sheet itself is just a regular Google Sheet. Share it the normal way:

- **With your CA**: Share → email, give View access.
- **With a partner**: Share → email, give Comment or Edit access.
- **With a payroll consultant**: Share → email, give View access; build a Pivot Table tab with the breakdowns they need.
- **With Power BI / Looker / Metabase**: Use Google Sheets as the data source.

You can also build your own derived tabs that reference the synced tabs — `=QUERY(Orders!A:Q, "select B, SUM(N) where B='today's-date' group by B")` etc. The sync never touches your custom tabs.

## Adding more fields

The script has a `SHEETS` config at the top with the headers per tab. To add a column:

1. Edit `SHEETS.orders.headers` (add the column name).
2. Edit the `projectRow('order', p)` function to project the field from the payload.
3. Edit the ERP push call to include that field in the payload — either `frontend/src/lib/google-sheets.ts` (demo) or `backend/app/services/integrations/google_sheets.py` (live).
4. **Save the Apps Script** and **re-deploy**: Deploy → Manage deployments → pencil icon → New version → Deploy.
5. New URL? Update the URL in Settings.

## Troubleshooting

**"URL must start with https://script.google.com/macros/s/…"**
You copied the wrong URL. The right URL is the Web app URL Google shows you AFTER you click Deploy — not the editor URL (`script.google.com/home/projects/…`).

**Test passes but nothing appears in the sheet**
Check: is the script saved in the same Google account that owns the sheet? An Apps Script bound to Sheet A cannot write to Sheet B.

**"Authorization required"**
You skipped step 4's authorization. Re-open the script, click **Run** on the `doPost` function, authorize. Re-deploy.

**Works in demo, fails in live mode**
Live mode pushes from the backend. If you're behind a strict firewall that blocks outbound HTTPS to `script.google.com`, the backend POST fails. Whitelist that domain on your server.

**Rate limits**
Apps Script web apps have a Google quota: roughly 20,000 requests per day for consumer accounts. Even a busy café won't come close (a 24-hour day, an order every 30 seconds = 2,880 requests).

**I want to disconnect**
Settings → Google Sheets → click **Disconnect**. Pushes stop immediately. The script keeps running in your sheet but doesn't receive anything until you reconnect.

**I want to wipe the synced data**
Right-click the tab in the sheet → Delete. The next sync will recreate the tab with fresh headers. Old historical rows are gone — but the ERP database still has them.

## Security note

The Web app URL is essentially a secret — anyone with it can POST data to your sheet. Treat it like a password:

- Don't paste it into public chats or GitHub issues.
- If it leaks, redeploy: **Deploy → Manage deployments → Archive**, then **New deployment**. You get a new URL; the old one stops working.
- Inside the ERP, the URL is stored in `localStorage` (demo) or the `companies.google_sheets_webhook_url` column (live). Only logged-in owners/managers see it.
