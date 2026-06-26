/**
 * D Company ERP — Google Sheets sink (single-tab mode).
 *
 * INSTALLATION (only needed once):
 *   1. Open the Google Sheet you want to use.
 *   2. Extensions → Apps Script. A new tab opens.
 *   3. Replace the empty Code.gs content with EVERYTHING in this file.
 *   4. Click the disk icon (save).
 *   5. Click Deploy → New deployment → Type: Web app.
 *        • Description:     "D Company ERP webhook"
 *        • Execute as:      Me
 *        • Who has access:  Anyone with the link
 *   6. Click Deploy. Authorize when prompted (the "this app isn't verified"
 *      warning is normal for personal scripts — click Advanced → Go to ...
 *      (unsafe), then Allow).
 *   7. Copy the Web app URL it gives you.
 *   8. In D Company ERP open Settings → Google Sheets and paste the URL.
 *
 * BEHAVIOR
 *   • Every order, ticket, event, and report is appended as ONE row in the
 *     single tab named "Operations" (the name is configurable below).
 *   • Your existing tabs in this Sheet are NEVER touched.
 *   • Rows are idempotent on the unique id column: if the same invoice/ticket
 *     id arrives twice, the existing row is overwritten in place — no dupes.
 *
 * COLUMN LAYOUT (one row per ERP entry)
 *   Date | Time | Type | ID | Description | Customer | Qty | Taxable | CGST |
 *   SGST | IGST | Round-off | Total | Method | Cashier | GSTIN | Place of supply
 *
 *   "Type" is one of: Order, Ticket, Event, Daily Report, Monthly Report,
 *   Quarterly Report, Yearly Report.
 */

const TAB_NAME = "Operations";

const HEADERS = [
  "Date", "Time", "Type", "ID", "Description", "Customer",
  "Qty", "Taxable (₹)", "CGST (₹)", "SGST (₹)", "IGST (₹)",
  "Round-off (₹)", "Total (₹)", "Method", "Cashier",
  "GSTIN", "Place of supply",
];

const TYPE_LABEL = {
  order:             "Order",
  ticket:            "Ticket",
  event:             "Event",
  daily_report:      "Daily Report",
  monthly_report:    "Monthly Report",
  quarterly_report:  "Quarterly Report",
  yearly_report:     "Yearly Report",
  ping:              "Ping",
};

// =============================================================================
// Entrypoints
// =============================================================================
function doPost(e) {
  try {
    const body = JSON.parse(e.postData.contents);
    const { kind, payload } = body;
    if (kind === "ping") {
      return json({ ok: true, kind: "ping", tab: TAB_NAME });
    }
    if (!(kind in TYPE_LABEL)) {
      return json({ ok: false, error: "unknown kind: " + kind }, 400);
    }
    const sheet = ensureSheet(TAB_NAME, HEADERS);
    const row = projectRow(kind, payload);
    upsertRow(sheet, /* idColumn = */ 4, row);  // column D = ID
    return json({ ok: true, kind, id: row[3] });
  } catch (err) {
    return json({ ok: false, error: String(err) }, 500);
  }
}

function doGet() {
  return json({
    ok: true,
    service: "D Company ERP webhook",
    mode: "single-tab",
    tab: TAB_NAME,
  });
}

// =============================================================================
// Row projection — keep every row the same width so SUM/QUERY/AVERAGE work.
// =============================================================================
function projectRow(kind, p) {
  const date  = p.date  || "";
  const time  = p.time  || "";
  const label = TYPE_LABEL[kind];

  // Orders, tickets, events all share most columns
  if (kind === "order") {
    return [
      date, time, label, p.invoice_no || "",
      p.items_text || "", p.customer_name || "",
      p.items_count || 0,
      money(p.taxable_minor), money(p.cgst_minor), money(p.sgst_minor),
      money(p.igst_minor || 0), money(p.round_off_minor || 0),
      money(p.total_minor),
      p.method || "", p.cashier || "",
      p.gstin || "", p.place_of_supply || "",
    ];
  }

  if (kind === "ticket") {
    return [
      date, time, label, p.ticket_no || "",
      p.event_name || "", p.customer_name || "",
      1,
      money(p.taxable_minor), money(p.cgst_minor), money(p.sgst_minor),
      money(0), money(0), money(p.price_paid_minor),
      "", "", "", "",
    ];
  }

  if (kind === "event") {
    return [
      date, time, label, p.id || "",
      p.name || "", "",
      p.capacity || 0,
      money(0), money(0), money(0),
      money(0), money(0), money(p.base_ticket_price_minor),
      "", "", "", "",
    ];
  }

  // Reports use the Description column for the period summary and the
  // money columns for revenue / GST / total.
  if (kind === "daily_report" || kind === "monthly_report" ||
      kind === "quarterly_report" || kind === "yearly_report") {
    const sub = subRevenueText(p);
    return [
      date, time, label, p.period_id || p.label || "",
      sub,
      "",                                  // customer
      p.orders_count || 0,                 // qty = order count
      money(p.net_revenue_minor || 0),     // taxable = net revenue
      money(p.cgst_minor),
      money(p.sgst_minor),
      money(p.igst_minor || 0),
      money(0),
      money(p.gross_revenue_minor),
      p.expense_label || ("Expenses: " + money(p.expense_total_minor || 0)),
      "",                                  // cashier (n/a)
      "",                                  // gstin
      "",                                  // place of supply
    ];
  }

  throw new Error("unknown kind: " + kind);
}

function subRevenueText(p) {
  const parts = [];
  if (p.food_minor)              parts.push("Food " + money(p.food_minor));
  if (p.gaming_minor)            parts.push("Gaming " + money(p.gaming_minor));
  if (p.event_tickets_minor)     parts.push("Events " + money(p.event_tickets_minor));
  if (p.delivery_minor)          parts.push("Delivery " + money(p.delivery_minor));
  const profit = p.net_profit_minor;
  if (profit !== undefined && profit !== null) parts.push("Net " + money(profit));
  return parts.join(" · ");
}

// =============================================================================
// Sheet helpers
// =============================================================================
function ensureSheet(name, headers) {
  const ss = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = ss.getSheetByName(name);

  if (!sheet) {
    // Tab doesn't exist — create with full ERP headers
    sheet = ss.insertSheet(name);
    writeHeaderRow(sheet, headers);
    return sheet;
  }

  // Tab exists — make sure row 1 matches our headers. If the first cell is
  // empty, OR holds a placeholder (e.g. "OPERATIONS COST"), OR the row has
  // fewer columns than we need, we (re)write the header row.
  const firstCell = sheet.getRange(1, 1).getValue();
  const isPlaceholder =
    !firstCell ||
    String(firstCell).trim().toUpperCase() === "OPERATIONS COST" ||
    sheet.getLastColumn() < headers.length;

  if (isPlaceholder && headers[0] && String(firstCell) !== headers[0]) {
    writeHeaderRow(sheet, headers);
  }
  return sheet;
}

function writeHeaderRow(sheet, headers) {
  sheet.getRange(1, 1, 1, headers.length).setValues([headers])
    .setFontWeight("bold").setBackground("#1e2a4d").setFontColor("#ffffff");
  sheet.setFrozenRows(1);
  sheet.setColumnWidth(3, 90);    // Type
  sheet.setColumnWidth(4, 170);   // ID
  sheet.setColumnWidth(5, 240);   // Description
}

function upsertRow(sheet, idColumn, row) {
  const id = row[idColumn - 1];
  if (!id) {
    sheet.appendRow(row);
    return;
  }
  const lastRow = sheet.getLastRow();
  if (lastRow > 1) {
    const ids = sheet.getRange(2, idColumn, lastRow - 1, 1).getValues();
    for (let i = 0; i < ids.length; i++) {
      if (String(ids[i][0]) === String(id)) {
        sheet.getRange(i + 2, 1, 1, row.length).setValues([row]);
        return;
      }
    }
  }
  sheet.appendRow(row);
}

function money(minor) {
  return ((minor || 0) / 100);
}

function json(obj, code) {
  const out = ContentService.createTextOutput(JSON.stringify(obj));
  out.setMimeType(ContentService.MimeType.JSON);
  return out;
}
