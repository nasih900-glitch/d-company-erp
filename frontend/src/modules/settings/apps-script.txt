/**
 * D Company ERP Mirror v1 — authenticated, append-only Google Sheets sink.
 *
 * Before deployment, add a Script Property named
 * D_COMPANY_ERP_MIRROR_HMAC_SECRET containing the same random secret used by
 * the ERP dispatcher (at least 32 UTF-8 bytes). Deploy this bound script as a
 * Web app that executes as the sheet owner.
 *
 * This script owns only the "ERP Mirror v1" tab. Accepted financial events are
 * never updated or deleted. Retries return "duplicate" when event_id and
 * payload hash match; reusing an event_id with different bytes fails closed.
 */

const MIRROR_SCHEMA = "erp-mirror.v1";
const MIRROR_SCHEMA_VERSION = 1;
const MIRROR_TAB_NAME = "ERP Mirror v1";
const HMAC_SECRET_PROPERTY = "D_COMPANY_ERP_MIRROR_HMAC_SECRET";
const EVENT_ID_COLUMN = 4;
const PAYLOAD_HASH_COLUMN = 25;
const MAX_SIGNED_PAYLOAD_CHARS = 131072;
const PAYMENT_RAILS = ["cash", "card", "upi", "qr", "wallet"];

const MIRROR_HEADERS = [
  "Received At",
  "Occurred At",
  "Event Type",
  "Event ID",
  "Event Key",
  "Company ID",
  "Branch",
  "Reference / Invoice",
  "Description",
  "Customer",
  "Quantity",
  "Amount Minor",
  "Payment Method",
  "Cash Minor",
  "Card Minor",
  "UPI Minor",
  "QR Minor",
  "Wallet Minor",
  "Actor / Cashier",
  "Status",
  "Source Type",
  "Source ID",
  "Source Revision",
  "Schema Version",
  "Payload SHA256",
  "Period Start",
  "Period End",
  "Currency",
  "Gross Revenue Minor",
  "Net Revenue Minor",
  "Expense Total Minor",
  "Net Profit Minor",
  "Payload JSON",
];

const OUTER_KEYS = [
  "schema", "signature", "signature_algorithm", "signed_payload",
];
const EVENT_KEYS = [
  "company_id", "configuration_id", "event_id", "event_key", "event_type", "occurred_at",
  "payload_json", "payload_sha256", "schema", "schema_version",
  "source_id", "source_revision", "source_type",
];

function doPost(e) {
  try {
    const outer = parseRequest(e);
    const secret = PropertiesService.getScriptProperties()
      .getProperty(HMAC_SECRET_PROPERTY);
    if (!secret || utf8Length(secret) < 32) {
      throw mirrorError(
        "secret_not_configured",
        "The ERP Mirror HMAC secret is not configured.",
        false
      );
    }
    const expectedSignature = hmacSha256Hex(outer.signed_payload, secret);
    if (!timingSafeEqual(expectedSignature, outer.signature)) {
      throw mirrorError("signature_invalid", "The request signature is invalid.", false);
    }

    const event = parseSignedEvent(outer.signed_payload);
    const payload = parsePayload(event);
    const lock = LockService.getScriptLock();
    if (!lock.tryLock(30000)) {
      throw mirrorError("lock_timeout", "The mirror is busy; retry later.", true);
    }
    try {
      return appendIdempotently(event, payload);
    } finally {
      lock.releaseLock();
    }
  } catch (err) {
    const known = err && err.mirrorError === true;
    return jsonResponse({
      ok: false,
      retryable: known ? err.retryable === true : true,
      error_code: known ? err.errorCode : "internal_error",
      error: known ? err.publicMessage : "The ERP Mirror could not process the request.",
      schema: MIRROR_SCHEMA,
    });
  }
}

function doGet() {
  return jsonResponse({
    ok: true,
    service: "D Company ERP Mirror",
    schema: MIRROR_SCHEMA,
    schema_version: MIRROR_SCHEMA_VERSION,
    mode: "append-only",
    tab: MIRROR_TAB_NAME,
    hmac_configured: Boolean(
      PropertiesService.getScriptProperties().getProperty(HMAC_SECRET_PROPERTY)
    ),
  });
}

function parseRequest(e) {
  if (!e || !e.postData || typeof e.postData.contents !== "string") {
    throw mirrorError("request_missing", "The request body is missing.", false);
  }
  let outer;
  try {
    outer = JSON.parse(e.postData.contents);
  } catch (_err) {
    throw mirrorError("request_json_invalid", "The request body is not valid JSON.", false);
  }
  if (!isObject(outer) || !hasExactKeys(outer, OUTER_KEYS)) {
    throw mirrorError("envelope_invalid", "The request envelope is invalid.", false);
  }
  if (outer.schema !== MIRROR_SCHEMA || outer.signature_algorithm !== "HMAC-SHA256") {
    throw mirrorError("schema_unsupported", "The request schema is unsupported.", false);
  }
  if (
    typeof outer.signed_payload !== "string" ||
    outer.signed_payload.length === 0 ||
    outer.signed_payload.length > MAX_SIGNED_PAYLOAD_CHARS
  ) {
    throw mirrorError("signed_payload_invalid", "The signed payload is invalid.", false);
  }
  if (typeof outer.signature !== "string" || !/^[0-9a-f]{64}$/.test(outer.signature)) {
    throw mirrorError("signature_invalid", "The request signature is invalid.", false);
  }
  return outer;
}

function parseSignedEvent(signedPayload) {
  let event;
  try {
    event = JSON.parse(signedPayload);
  } catch (_err) {
    throw mirrorError("event_json_invalid", "The signed event is not valid JSON.", false);
  }
  if (!isObject(event) || !hasExactKeys(event, EVENT_KEYS)) {
    throw mirrorError("event_shape_invalid", "The signed event shape is invalid.", false);
  }
  if (event.schema !== MIRROR_SCHEMA || event.schema_version !== MIRROR_SCHEMA_VERSION) {
    throw mirrorError("schema_unsupported", "The signed event schema is unsupported.", false);
  }
  requireText(
    "event_id", event.event_id, 36,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  );
  requireText(
    "company_id", event.company_id, 36,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  );
  requireText(
    "configuration_id", event.configuration_id, 36,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  );
  requireText("event_key", event.event_key, 96);
  requireText("event_type", event.event_type, 80);
  requireText("source_type", event.source_type, 80);
  requireText("source_id", event.source_id, 200);
  requireText("source_revision", event.source_revision, 100);
  requireText("occurred_at", event.occurred_at, 40);
  if (isNaN(Date.parse(event.occurred_at))) {
    throw mirrorError("occurred_at_invalid", "The event timestamp is invalid.", false);
  }
  if (typeof event.payload_json !== "string" || event.payload_json.length > 46080) {
    throw mirrorError("payload_invalid", "The canonical payload is invalid.", false);
  }
  if (
    typeof event.payload_sha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(event.payload_sha256)
  ) {
    throw mirrorError("payload_hash_invalid", "The payload hash is invalid.", false);
  }
  return event;
}

function parsePayload(event) {
  if (!timingSafeEqual(sha256Hex(event.payload_json), event.payload_sha256)) {
    throw mirrorError("payload_hash_mismatch", "The payload hash does not match.", false);
  }
  let payload;
  try {
    payload = JSON.parse(event.payload_json);
  } catch (_err) {
    throw mirrorError("payload_json_invalid", "The payload is not valid JSON.", false);
  }
  if (!isObject(payload)) {
    throw mirrorError("payload_shape_invalid", "The payload must be an object.", false);
  }
  return payload;
}

function appendIdempotently(event, payload) {
  const sheet = getOrCreateMirrorSheet();
  assertExactHeaders(sheet);
  const existing = findEventRow(sheet, event.event_id);
  if (existing !== null) {
    const existingHash = String(
      sheet.getRange(existing, PAYLOAD_HASH_COLUMN).getDisplayValue()
    );
    if (!timingSafeEqual(existingHash, event.payload_sha256)) {
      throw mirrorError(
        "event_hash_conflict",
        "This event ID already exists with a different payload hash.",
        false
      );
    }
    return jsonResponse({
      ok: true,
      status: "duplicate",
      event_id: event.event_id,
      payload_sha256: event.payload_sha256,
      schema: MIRROR_SCHEMA,
    });
  }

  sheet.appendRow(projectMirrorRow(event, payload));
  return jsonResponse({
    ok: true,
    status: "accepted",
    event_id: event.event_id,
    payload_sha256: event.payload_sha256,
    schema: MIRROR_SCHEMA,
  });
}

function getOrCreateMirrorSheet() {
  const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = spreadsheet.getSheetByName(MIRROR_TAB_NAME);
  if (sheet) return sheet;
  sheet = spreadsheet.insertSheet(MIRROR_TAB_NAME);
  sheet.getRange(1, 1, 1, MIRROR_HEADERS.length).setValues([MIRROR_HEADERS]);
  sheet.getRange(1, 1, 1, MIRROR_HEADERS.length)
    .setFontWeight("bold")
    .setBackground("#1e2a4d")
    .setFontColor("#ffffff");
  sheet.setFrozenRows(1);
  return sheet;
}

function assertExactHeaders(sheet) {
  if (sheet.getLastColumn() !== MIRROR_HEADERS.length || sheet.getLastRow() < 1) {
    throw mirrorError(
      "header_mismatch",
      "The ERP Mirror v1 header layout does not match the required schema.",
      false
    );
  }
  const actual = sheet.getRange(1, 1, 1, MIRROR_HEADERS.length).getDisplayValues()[0];
  for (let i = 0; i < MIRROR_HEADERS.length; i++) {
    if (String(actual[i]) !== MIRROR_HEADERS[i]) {
      throw mirrorError(
        "header_mismatch",
        "The ERP Mirror v1 header layout does not match the required schema.",
        false
      );
    }
  }
}

function findEventRow(sheet, eventId) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const match = sheet
    .getRange(2, EVENT_ID_COLUMN, lastRow - 1, 1)
    .createTextFinder(eventId)
    .matchEntireCell(true)
    .findNext();
  return match ? match.getRow() : null;
}

function projectMirrorRow(event, payload) {
  const paymentBreakdown = optionalPaymentBreakdown(payload);
  return [
    new Date(),
    safeText(event.occurred_at),
    safeText(event.event_type),
    safeText(event.event_id),
    safeText(event.event_key),
    safeText(event.company_id),
    optionalText(payload, "branch"),
    optionalText(payload, "reference"),
    optionalText(payload, "description"),
    optionalText(payload, "customer"),
    optionalQuantity(payload, "quantity"),
    optionalInteger(payload, "amount_minor"),
    optionalText(payload, "payment_method"),
    paymentBreakdown === null ? "" : paymentBreakdown.cash,
    paymentBreakdown === null ? "" : paymentBreakdown.card,
    paymentBreakdown === null ? "" : paymentBreakdown.upi,
    paymentBreakdown === null ? "" : paymentBreakdown.qr,
    paymentBreakdown === null ? "" : paymentBreakdown.wallet,
    optionalText(payload, "actor"),
    optionalText(payload, "status"),
    safeText(event.source_type),
    safeText(event.source_id),
    safeText(event.source_revision),
    event.schema_version,
    safeText(event.payload_sha256),
    optionalText(payload, "period_start"),
    optionalText(payload, "period_end"),
    optionalText(payload, "currency"),
    optionalInteger(payload, "gross_revenue_minor"),
    optionalInteger(payload, "net_revenue_minor"),
    optionalInteger(payload, "expense_total_minor"),
    optionalInteger(payload, "net_profit_minor"),
    safeText(event.payload_json),
  ];
}

function optionalText(payload, key) {
  if (payload[key] === undefined || payload[key] === null) return "";
  if (typeof payload[key] !== "string") {
    throw mirrorError("payload_field_invalid", key + " must be text.", false);
  }
  return safeText(payload[key]);
}

function optionalInteger(payload, key) {
  if (payload[key] === undefined || payload[key] === null) return "";
  if (!Number.isSafeInteger(payload[key])) {
    throw mirrorError("payload_field_invalid", key + " must be a safe integer.", false);
  }
  return payload[key];
}

function optionalPaymentBreakdown(payload) {
  const value = payload.payment_breakdown_minor;
  if (value === undefined || value === null) return null;
  if (!isObject(value) || !hasExactKeys(value, PAYMENT_RAILS)) {
    throw mirrorError(
      "payload_field_invalid",
      "payment_breakdown_minor must contain exactly the supported payment rails.",
      false
    );
  }
  let total = 0;
  for (let i = 0; i < PAYMENT_RAILS.length; i++) {
    const rail = PAYMENT_RAILS[i];
    if (!Number.isSafeInteger(value[rail]) || value[rail] < 0) {
      throw mirrorError(
        "payload_field_invalid",
        "payment_breakdown_minor values must be non-negative safe integers.",
        false
      );
    }
    total += value[rail];
  }
  if (!Number.isSafeInteger(total) || total !== payload.amount_minor) {
    throw mirrorError(
      "payload_field_invalid",
      "payment_breakdown_minor must equal amount_minor.",
      false
    );
  }
  return value;
}

function optionalQuantity(payload, key) {
  if (payload[key] === undefined || payload[key] === null) return "";
  if (Number.isSafeInteger(payload[key])) return payload[key];
  if (typeof payload[key] === "string" && /^\d+(\.\d{1,3})?$/.test(payload[key])) {
    return safeText(payload[key]);
  }
  throw mirrorError(
    "payload_field_invalid",
    key + " must be a non-negative integer or decimal text with up to 3 places.",
    false
  );
}

function safeText(value) {
  const text = String(value === undefined || value === null ? "" : value);
  return /^\s*[=+\-@]/.test(text) ? "'" + text : text;
}

function requireText(name, value, maxLength, pattern) {
  if (
    typeof value !== "string" || value.length === 0 || value.length > maxLength ||
    (pattern && !pattern.test(value))
  ) {
    throw mirrorError(name + "_invalid", name + " is invalid.", false);
  }
}

function hasExactKeys(object, expected) {
  const actual = Object.keys(object).sort();
  const wanted = expected.slice().sort();
  if (actual.length !== wanted.length) return false;
  for (let i = 0; i < wanted.length; i++) {
    if (actual[i] !== wanted[i]) return false;
  }
  return true;
}

function isObject(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function sha256Hex(value) {
  return bytesToHex(
    Utilities.computeDigest(
      Utilities.DigestAlgorithm.SHA_256,
      value,
      Utilities.Charset.UTF_8
    )
  );
}

function hmacSha256Hex(value, secret) {
  return bytesToHex(
    Utilities.computeHmacSha256Signature(value, secret, Utilities.Charset.UTF_8)
  );
}

function bytesToHex(bytes) {
  return bytes.map(function (byte) {
    return ((byte + 256) % 256).toString(16).padStart(2, "0");
  }).join("");
}

function timingSafeEqual(left, right) {
  const a = String(left);
  const b = String(right);
  let difference = a.length ^ b.length;
  const length = Math.max(a.length, b.length);
  for (let i = 0; i < length; i++) {
    difference |= (a.charCodeAt(i) || 0) ^ (b.charCodeAt(i) || 0);
  }
  return difference === 0;
}

function utf8Length(value) {
  return Utilities.newBlob(String(value)).getBytes().length;
}

function mirrorError(errorCode, publicMessage, retryable) {
  const error = new Error(publicMessage);
  error.mirrorError = true;
  error.errorCode = errorCode;
  error.publicMessage = publicMessage;
  error.retryable = retryable;
  return error;
}

function jsonResponse(body) {
  return ContentService
    .createTextOutput(JSON.stringify(body))
    .setMimeType(ContentService.MimeType.JSON);
}
