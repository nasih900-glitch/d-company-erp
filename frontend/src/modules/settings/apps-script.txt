/**
 * D Company ERP Mirror v1 — authenticated, append-only Google Sheets sink.
 *
 * Before deployment, add a Script Property named
 * D_COMPANY_ERP_MIRROR_HMAC_SECRET containing the same random secret used by
 * the ERP dispatcher (at least 32 UTF-8 bytes). Deploy this bound script as a
 * Web app that executes as the sheet owner.
 *
 * Legacy mode owns only the "ERP Mirror v1" tab. Optional daily-collections
 * mode also owns a hidden append-only log plus an explicitly configured future
 * range in "SHOP SALES REVENUE". Accepted financial events are never updated
 * or deleted. Keep unrelated Apps Script files in the bound project intact.
 */

const MIRROR_SCHEMA = "erp-mirror.v1";
const MIRROR_SCHEMA_VERSION = 1;
const MIRROR_TAB_NAME = "ERP Mirror v1";
const HMAC_SECRET_PROPERTY = "D_COMPANY_ERP_MIRROR_HMAC_SECRET";
const EVENT_ID_COLUMN = 4;
const PAYLOAD_HASH_COLUMN = 25;
const MAX_SIGNED_PAYLOAD_CHARS = 131072;
const PAYMENT_RAILS = ["cash", "card", "upi", "qr", "wallet"];
const DAILY_COLLECTIONS_CAPABILITY = "daily_collections.v1";
const DAILY_COLLECTIONS_MODE = "daily_collections";
const DAILY_COLLECTIONS_EVENT_TYPE = "collections.daily_snapshot";
const DAILY_COLLECTIONS_SOURCE_TYPE = "daily_collections";
const CONNECTION_TEST_EVENT_TYPE = "mirror.connection_test";
const CONNECTION_TEST_SOURCE_TYPE = "mirror_configuration";
const DAILY_CONFIG_PROPERTY = "D_COMPANY_ERP_DAILY_COLLECTIONS_CONFIG";
const DAILY_LOG_TAB_NAME = "ERP Daily Collections v1";
const DAILY_LOG_PROTECTION = "D Company ERP daily collections log";
const DAILY_PROJECTION_PROTECTION = "D Company ERP daily collections projection";
const DAILY_STATUS_PROTECTION = "D Company ERP daily collections status";
const DAILY_PROJECTION_SHEET = "SHOP SALES REVENUE";
const DAILY_MANUAL_END_ROW = 93;
const DAILY_PAYLOAD_KEYS = [
  "blockers", "branch", "business_date", "business_timezone", "cash_minor",
  "currency", "export_mode", "legacy_refund_count", "movement_count",
  "open_shift_count", "shift_count", "status", "total_minor",
  "unsupported_rails", "upi_minor",
];
const DAILY_CONNECTION_PAYLOAD_KEYS = [
  "currency", "description", "export_mode", "required_capability", "status",
  "summary_scope",
];
const DAILY_SCOPE_KEYS = ["branch_id", "business_timezone", "start_date"];
const DAILY_CONFIG_KEYS = [
  "branch_id", "business_timezone", "company_id", "d2_formula_sha256",
  "manual_end_row", "projection_end_row", "projection_sheet", "start_date",
  "status_cell",
];
const DAILY_LOG_HEADERS = [
  "Business Date", "Source Revision", "Status", "Cash Minor", "UPI Minor",
  "Total Minor", "Company ID", "Branch ID", "Branch", "Business Timezone",
  "Currency", "Blockers JSON", "Unsupported Rails JSON", "Shift Count",
  "Open Shift Count", "Movement Count", "Legacy Refund Count", "Event ID",
  "Event Key", "Configuration ID", "Payload SHA256", "Occurred At",
  "Received At", "Source ID", "Payload JSON",
];
const DAILY_EVENT_ID_COLUMN = 18;
const DAILY_EVENT_KEY_COLUMN = 19;
const DAILY_CONFIGURATION_ID_COLUMN = 20;
const DAILY_SOURCE_REVISION_COLUMN = 2;
const DAILY_SOURCE_ID_COLUMN = 24;
const DAILY_PAYLOAD_HASH_COLUMN = 21;
const DAILY_OCCURRED_AT_COLUMN = 22;
const DAILY_RECEIVED_AT_COLUMN = 23;

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
  if (
    event.event_type === CONNECTION_TEST_EVENT_TYPE &&
    event.source_type === CONNECTION_TEST_SOURCE_TYPE &&
    payload.export_mode === DAILY_COLLECTIONS_MODE
  ) {
    return acknowledgeDailyCollectionsConnection(event, payload);
  }
  if (
    event.event_type === DAILY_COLLECTIONS_EVENT_TYPE ||
    event.source_type === DAILY_COLLECTIONS_SOURCE_TYPE
  ) {
    return appendDailyCollectionIdempotently(event, payload);
  }

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

  appendVerifiedRow(sheet, projectMirrorRow(event, payload), MIRROR_HEADERS.length);
  return jsonResponse({
    ok: true,
    status: "accepted",
    event_id: event.event_id,
    payload_sha256: event.payload_sha256,
    schema: MIRROR_SCHEMA,
  });
}

function appendVerifiedRow(sheet, row, width) {
  if (!Array.isArray(row) || row.length !== width) {
    throw mirrorError("row_shape_invalid", "The projected row shape is invalid.", false);
  }
  const rowNumber = sheet.getLastRow() + 1;
  const range = sheet.getRange(rowNumber, 1, 1, width);
  try {
    range.setValues([row]);
    SpreadsheetApp.flush();
    const stored = range.getValues()[0];
    if (!rowsEqual(stored, row)) {
      throw mirrorError(
        "write_verification_failed",
        "Google Sheets did not preserve the complete event row.",
        true
      );
    }
    return rowNumber;
  } catch (err) {
    // A failed, unacknowledged tail write is not an accepted event. Remove only
    // that newly allocated row so an automatic retry can safely write it again.
    if (sheet.getLastRow() === rowNumber) {
      try { sheet.deleteRow(rowNumber); } catch (_cleanupError) { /* retry remains safe */ }
    }
    throw err;
  }
}

function rowsEqual(stored, expected) {
  if (!Array.isArray(stored) || stored.length !== expected.length) return false;
  for (let i = 0; i < expected.length; i++) {
    const left = stored[i] instanceof Date ? stored[i].toISOString() : String(stored[i]);
    const right = expected[i] instanceof Date ? expected[i].toISOString() : String(expected[i]);
    if (left !== right) return false;
  }
  return true;
}

function acknowledgeDailyCollectionsConnection(event, payload) {
  const config = readDailyCollectionsConfig();
  validateDailyConnectionPayload(event, payload, config);
  assertDailyCollectionsSetup(config);
  return jsonResponse({
    ok: true,
    status: "accepted",
    event_id: event.event_id,
    payload_sha256: event.payload_sha256,
    schema: MIRROR_SCHEMA,
    capability: DAILY_COLLECTIONS_CAPABILITY,
    scope: {
      branch_id: config.branch_id,
      business_timezone: config.business_timezone,
      start_date: config.start_date,
    },
  });
}

function validateDailyConnectionPayload(event, payload, config) {
  if (!hasExactKeys(payload, DAILY_CONNECTION_PAYLOAD_KEYS)) {
    throw mirrorError("daily_connection_shape_invalid", "The daily connection test is invalid.", false);
  }
  if (
    event.company_id !== config.company_id ||
    event.source_revision !== event.configuration_id ||
    payload.export_mode !== DAILY_COLLECTIONS_MODE ||
    payload.required_capability !== DAILY_COLLECTIONS_CAPABILITY ||
    payload.status !== "test" ||
    !isObject(payload.summary_scope) ||
    !hasExactKeys(payload.summary_scope, DAILY_SCOPE_KEYS)
  ) {
    throw mirrorError("daily_scope_mismatch", "The daily collection scope does not match.", false);
  }
  requireText(
    "source_id", event.source_id, 36,
    /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
  );
  requirePlainText("currency", payload.currency, 8);
  requirePlainText("description", payload.description, 200);
  if (
    payload.summary_scope.branch_id !== config.branch_id ||
    payload.summary_scope.business_timezone !== config.business_timezone ||
    payload.summary_scope.start_date !== config.start_date
  ) {
    throw mirrorError("daily_scope_mismatch", "The daily collection scope does not match.", false);
  }
}

function appendDailyCollectionIdempotently(event, payload) {
  const config = readDailyCollectionsConfig();
  const normalized = validateDailyCollectionPayload(event, payload, config);
  assertDailyCollectionsSetup(config);
  const sheet = getDailyLogSheet();
  assertExactHeadersFor(sheet, DAILY_LOG_HEADERS, DAILY_LOG_TAB_NAME);
  assertDailyLogOwnership(sheet);

  const existingEvent = findTextRow(sheet, DAILY_EVENT_ID_COLUMN, event.event_id);
  if (existingEvent !== null) {
    const existingRow = sheet
      .getRange(existingEvent, 1, 1, DAILY_LOG_HEADERS.length)
      .getValues()[0];
    const existingHash = String(
      existingRow[DAILY_PAYLOAD_HASH_COLUMN - 1]
    );
    const existingSourceId = String(existingRow[DAILY_SOURCE_ID_COLUMN - 1]);
    const existingRevision = String(existingRow[DAILY_SOURCE_REVISION_COLUMN - 1]);
    const existingEventKey = String(existingRow[DAILY_EVENT_KEY_COLUMN - 1]);
    const existingConfiguration = String(existingRow[DAILY_CONFIGURATION_ID_COLUMN - 1]);
    const existingOccurredAt = String(existingRow[DAILY_OCCURRED_AT_COLUMN - 1]);
    if (
      !timingSafeEqual(existingHash, event.payload_sha256) ||
      existingSourceId !== event.source_id ||
      existingRevision !== event.source_revision ||
      existingEventKey !== safeText(event.event_key) ||
      existingConfiguration !== event.configuration_id ||
      existingOccurredAt !== event.occurred_at
    ) {
      throw mirrorError(
        "event_identity_conflict",
        "This event ID already exists with different immutable identity.",
        false
      );
    }
    const receivedAt = existingRow[DAILY_RECEIVED_AT_COLUMN - 1];
    if (
      !isCanonicalIsoTimestamp(receivedAt) ||
      !rowsEqual(
        existingRow,
        projectDailyCollectionRow(event, normalized, config, receivedAt)
      )
    ) {
      throw mirrorError(
        "event_row_conflict",
        "This event ID exists, but its canonical daily row is incomplete or changed.",
        false
      );
    }
    return dailyCollectionResponse("duplicate", event, config);
  }

  const existingRevision = findDailySourceRevisionRow(
    sheet, event.source_id, event.source_revision
  );
  if (existingRevision !== null) {
    throw mirrorError(
      "source_revision_conflict",
      "This daily collection revision already exists with a different event.",
      false
    );
  }

  assertDailyProjectionCapacity(sheet, event, normalized, config);
  const row = projectDailyCollectionRow(event, normalized, config, "");
  const nextRow = sheet.getLastRow() + 1;
  sheet.getRange(nextRow, 1, 1, 3).setNumberFormat("@");
  sheet.getRange(nextRow, 7, 1, 7).setNumberFormat("@");
  sheet.getRange(nextRow, 18, 1, 8).setNumberFormat("@");
  const stagedRow = appendVerifiedRow(sheet, row, DAILY_LOG_HEADERS.length);
  const receivedAt = new Date().toISOString();
  const acceptedRow = projectDailyCollectionRow(
    event, normalized, config, receivedAt
  );
  sheet.getRange(stagedRow, DAILY_RECEIVED_AT_COLUMN).setValue(receivedAt);
  SpreadsheetApp.flush();
  const stored = sheet
    .getRange(stagedRow, 1, 1, DAILY_LOG_HEADERS.length)
    .getValues()[0];
  if (!rowsEqual(stored, acceptedRow)) {
    throw mirrorError(
      "write_verification_failed",
      "Google Sheets did not preserve the accepted daily row.",
      true
    );
  }
  return dailyCollectionResponse("accepted", event, config);
}

function dailyCollectionResponse(status, event, config) {
  return jsonResponse({
    ok: true,
    status: status,
    event_id: event.event_id,
    payload_sha256: event.payload_sha256,
    schema: MIRROR_SCHEMA,
    capability: DAILY_COLLECTIONS_CAPABILITY,
    scope: {
      branch_id: config.branch_id,
      business_timezone: config.business_timezone,
      start_date: config.start_date,
    },
  });
}

function validateDailyCollectionPayload(event, payload, config) {
  if (
    event.event_type !== DAILY_COLLECTIONS_EVENT_TYPE ||
    event.source_type !== DAILY_COLLECTIONS_SOURCE_TYPE
  ) {
    throw mirrorError("daily_event_identity_invalid", "The daily event identity is invalid.", false);
  }
  if (!hasExactKeys(payload, DAILY_PAYLOAD_KEYS)) {
    throw mirrorError("daily_payload_shape_invalid", "The daily payload shape is invalid.", false);
  }
  if (event.company_id !== config.company_id) {
    throw mirrorError("daily_scope_mismatch", "The daily collection company does not match.", false);
  }
  requireIsoDate("business_date", payload.business_date);
  if (
    payload.business_date < config.start_date ||
    payload.business_timezone !== config.business_timezone ||
    event.source_id !== config.branch_id + ":" + payload.business_date ||
    !/^\d{20}$/.test(event.source_revision)
  ) {
    throw mirrorError("daily_scope_mismatch", "The daily collection scope does not match.", false);
  }
  if (payload.export_mode !== DAILY_COLLECTIONS_MODE) {
    throw mirrorError("daily_payload_invalid", "The daily export mode is invalid.", false);
  }
  requirePlainText("branch", payload.branch, 200);
  requirePlainText("currency", payload.currency, 8);
  if (payload.status !== "ready" && payload.status !== "blocked") {
    throw mirrorError("daily_payload_invalid", "The daily status is invalid.", false);
  }
  const signedAmounts = ["cash_minor", "upi_minor", "total_minor"];
  for (let i = 0; i < signedAmounts.length; i++) {
    requireSafeInteger(signedAmounts[i], payload[signedAmounts[i]], true);
  }
  if (payload.cash_minor + payload.upi_minor !== payload.total_minor) {
    throw mirrorError("daily_payload_invalid", "The daily collection total is inconsistent.", false);
  }
  const counts = [
    "legacy_refund_count", "movement_count", "open_shift_count", "shift_count",
  ];
  for (let i = 0; i < counts.length; i++) {
    requireSafeInteger(counts[i], payload[counts[i]], false);
  }
  if (!Array.isArray(payload.blockers) || !Array.isArray(payload.unsupported_rails)) {
    throw mirrorError("daily_payload_invalid", "The daily blocker state is invalid.", false);
  }
  for (let i = 0; i < payload.blockers.length; i++) {
    requirePlainText("blocker", payload.blockers[i], 80);
  }
  const seenRails = {};
  for (let i = 0; i < payload.unsupported_rails.length; i++) {
    const rail = payload.unsupported_rails[i];
    if (!isObject(rail) || !hasExactKeys(rail, ["net_minor", "rail"])) {
      throw mirrorError("daily_payload_invalid", "An unsupported rail entry is invalid.", false);
    }
    requirePlainText("unsupported rail", rail.rail, 40);
    requireSafeInteger("unsupported rail net_minor", rail.net_minor, true);
    if (seenRails[rail.rail]) {
      throw mirrorError("daily_payload_invalid", "Unsupported rails must be unique.", false);
    }
    seenRails[rail.rail] = true;
  }
  if (
    (payload.status === "ready" && payload.blockers.length !== 0) ||
    (payload.status === "blocked" && payload.blockers.length === 0)
  ) {
    throw mirrorError("daily_payload_invalid", "The daily readiness state is inconsistent.", false);
  }
  return payload;
}

function projectDailyCollectionRow(event, payload, config, receivedAt) {
  return [
    payload.business_date,
    event.source_revision,
    payload.status,
    payload.cash_minor,
    payload.upi_minor,
    payload.total_minor,
    event.company_id,
    config.branch_id,
    safeText(payload.branch),
    payload.business_timezone,
    safeText(payload.currency),
    JSON.stringify(payload.blockers),
    JSON.stringify(payload.unsupported_rails),
    payload.shift_count,
    payload.open_shift_count,
    payload.movement_count,
    payload.legacy_refund_count,
    event.event_id,
    safeText(event.event_key),
    event.configuration_id,
    event.payload_sha256,
    event.occurred_at,
    receivedAt,
    event.source_id,
    event.payload_json,
  ];
}

function isCanonicalIsoTimestamp(value) {
  if (typeof value !== "string") return false;
  const parsed = new Date(value);
  return !isNaN(parsed.getTime()) && parsed.toISOString() === value;
}

function assertDailyProjectionCapacity(sheet, event, payload, config) {
  const latest = {};
  const lastRow = sheet.getLastRow();
  if (lastRow >= 2) {
    const rows = sheet
      .getRange(2, 1, lastRow - 1, DAILY_LOG_HEADERS.length)
      .getValues();
    for (let i = 0; i < rows.length; i++) {
      const row = rows[i];
      const businessDate = String(row[0]);
      const revision = String(row[DAILY_SOURCE_REVISION_COLUMN - 1]);
      const status = String(row[2]);
      if (
        !isCanonicalIsoTimestamp(row[DAILY_RECEIVED_AT_COLUMN - 1]) ||
        !/^\d{4}-\d{2}-\d{2}$/.test(businessDate) ||
        businessDate < config.start_date ||
        !/^\d{20}$/.test(revision) ||
        (status !== "ready" && status !== "blocked") ||
        String(row[6]) !== config.company_id ||
        String(row[7]) !== config.branch_id ||
        String(row[DAILY_SOURCE_ID_COLUMN - 1]) !== config.branch_id + ":" + businessDate
      ) continue;
      if (!latest[businessDate] || revision > latest[businessDate].revision) {
        latest[businessDate] = { revision: revision, status: status };
      }
    }
  }

  const current = latest[payload.business_date];
  if (!current || event.source_revision > current.revision) {
    latest[payload.business_date] = {
      revision: event.source_revision,
      status: payload.status,
    };
  }
  let readyDates = 0;
  const dates = Object.keys(latest);
  for (let i = 0; i < dates.length; i++) {
    if (latest[dates[i]].status === "ready") readyDates += 1;
  }
  const capacity = config.projection_end_row - config.manual_end_row;
  if (readyDates > capacity) {
    throw mirrorError(
      "projection_capacity_exceeded",
      "The configured daily projection range has no remaining date rows.",
      false
    );
  }
}

function findDailySourceRevisionRow(sheet, sourceId, revision) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const values = sheet.getRange(2, 1, lastRow - 1, DAILY_LOG_HEADERS.length).getDisplayValues();
  for (let i = 0; i < values.length; i++) {
    if (
      String(values[i][DAILY_SOURCE_ID_COLUMN - 1]) === sourceId &&
      String(values[i][DAILY_SOURCE_REVISION_COLUMN - 1]) === revision
    ) return i + 2;
  }
  return null;
}

function findTextRow(sheet, column, value) {
  const lastRow = sheet.getLastRow();
  if (lastRow < 2) return null;
  const match = sheet.getRange(2, column, lastRow - 1, 1)
    .createTextFinder(value).matchEntireCell(true).findNext();
  return match ? match.getRow() : null;
}

function requireIsoDate(name, value) {
  if (typeof value !== "string" || !/^\d{4}-\d{2}-\d{2}$/.test(value)) {
    throw mirrorError(name + "_invalid", name + " is invalid.", false);
  }
  const parsed = new Date(value + "T00:00:00Z");
  if (isNaN(parsed.getTime()) || parsed.toISOString().slice(0, 10) !== value) {
    throw mirrorError(name + "_invalid", name + " is invalid.", false);
  }
}

function requirePlainText(name, value, maxLength) {
  if (typeof value !== "string" || value.length === 0 || value.length > maxLength) {
    throw mirrorError("daily_payload_invalid", name + " is invalid.", false);
  }
}

function requireSafeInteger(name, value, signed) {
  if (!Number.isSafeInteger(value) || (!signed && value < 0)) {
    throw mirrorError("daily_payload_invalid", name + " must be a safe integer.", false);
  }
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

/** Owner-run setup. It never edits A2:C93, D2, or unrelated script files. */
function setupDailyCollectionsProjection() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) {
    throw mirrorError("lock_timeout", "The mirror is busy; retry later.", true);
  }
  try {
    const config = readDailyCollectionsConfig();
    const logSheet = getOrCreateDailyLogSheet();
    installDailyLogOwnership(logSheet);
    installDailyProjection(config);
    assertDailyCollectionsSetup(config);
    return {
      ok: true,
      capability: DAILY_COLLECTIONS_CAPABILITY,
      scope: {
        branch_id: config.branch_id,
        business_timezone: config.business_timezone,
        start_date: config.start_date,
      },
    };
  } finally {
    lock.releaseLock();
  }
}

/** Read-only helper used by the owner before setting d2_formula_sha256. */
function getDailyCollectionsD2Fingerprint() {
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(DAILY_PROJECTION_SHEET);
  if (!sheet) throw new Error(DAILY_PROJECTION_SHEET + " does not exist.");
  const formula = sheet.getRange("D2").getFormula();
  if (!formula) throw new Error("D2 must contain the existing ARRAYFORMULA.");
  const fingerprint = sha256Hex(formula);
  console.log("D2 formula SHA-256: " + fingerprint);
  return fingerprint;
}

function readDailyCollectionsConfig() {
  const raw = PropertiesService.getScriptProperties().getProperty(DAILY_CONFIG_PROPERTY);
  if (!raw) {
    throw mirrorError(
      "daily_config_missing",
      "Daily collections is not configured in Apps Script.",
      false
    );
  }
  let config;
  try { config = JSON.parse(raw); } catch (_err) {
    throw mirrorError("daily_config_invalid", "The daily collections config is invalid.", false);
  }
  if (!isObject(config) || !hasExactKeys(config, DAILY_CONFIG_KEYS)) {
    throw mirrorError("daily_config_invalid", "The daily collections config is invalid.", false);
  }
  const uuidPattern = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
  requireText("company_id", config.company_id, 36, uuidPattern);
  requireText("branch_id", config.branch_id, 36, uuidPattern);
  requirePlainText("business_timezone", config.business_timezone, 64);
  requireIsoDate("start_date", config.start_date);
  if (
    config.projection_sheet !== DAILY_PROJECTION_SHEET ||
    config.manual_end_row !== DAILY_MANUAL_END_ROW ||
    !Number.isSafeInteger(config.projection_end_row) ||
    config.projection_end_row < DAILY_MANUAL_END_ROW + 1 ||
    config.projection_end_row > 5000 ||
    typeof config.status_cell !== "string" ||
    !/^[A-Z]+[1-9]\d*$/.test(config.status_cell) ||
    a1ColumnNumber(config.status_cell) <= 4 ||
    typeof config.d2_formula_sha256 !== "string" ||
    !/^[0-9a-f]{64}$/.test(config.d2_formula_sha256)
  ) {
    throw mirrorError("daily_config_invalid", "The daily collections config is invalid.", false);
  }
  return config;
}

function a1ColumnNumber(a1Notation) {
  const letters = String(a1Notation).match(/^[A-Z]+/)[0];
  let value = 0;
  for (let i = 0; i < letters.length; i++) {
    value = value * 26 + letters.charCodeAt(i) - 64;
  }
  return value;
}

function getOrCreateDailyLogSheet() {
  const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  let sheet = spreadsheet.getSheetByName(DAILY_LOG_TAB_NAME);
  if (!sheet) {
    sheet = spreadsheet.insertSheet(DAILY_LOG_TAB_NAME);
    sheet.getRange(1, 1, 1, DAILY_LOG_HEADERS.length).setValues([DAILY_LOG_HEADERS]);
    sheet.getRange(1, 1, 1, DAILY_LOG_HEADERS.length)
      .setFontWeight("bold").setBackground("#1e2a4d").setFontColor("#ffffff");
    sheet.setFrozenRows(1);
  }
  assertExactHeadersFor(sheet, DAILY_LOG_HEADERS, DAILY_LOG_TAB_NAME);
  return sheet;
}

function getDailyLogSheet() {
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(DAILY_LOG_TAB_NAME);
  if (!sheet) {
    throw mirrorError("daily_setup_missing", "Run the daily collections owner setup first.", false);
  }
  return sheet;
}

function installDailyLogOwnership(sheet) {
  let protections = sheet.getProtections(SpreadsheetApp.ProtectionType.SHEET);
  let protection = null;
  for (let i = 0; i < protections.length; i++) {
    if (protections[i].getDescription() === DAILY_LOG_PROTECTION) protection = protections[i];
  }
  if (!protection) protection = sheet.protect().setDescription(DAILY_LOG_PROTECTION);
  lockProtectionToOwner(protection);
  if (!sheet.isSheetHidden()) sheet.hideSheet();
}

function installDailyProjection(config) {
  const spreadsheet = SpreadsheetApp.getActiveSpreadsheet();
  const sheet = spreadsheet.getSheetByName(config.projection_sheet);
  if (!sheet) {
    throw mirrorError("projection_sheet_missing", "The configured collections sheet is missing.", false);
  }
  assertD2Fingerprint(sheet, config);
  if (sheet.getMaxRows() < config.projection_end_row) {
    sheet.insertRowsAfter(
      sheet.getMaxRows(),
      config.projection_end_row - sheet.getMaxRows()
    );
  }
  const startRow = config.manual_end_row + 1;
  const rowCount = config.projection_end_row - startRow + 1;
  const projectionRange = sheet.getRange(startRow, 1, rowCount, 3);
  const anchor = sheet.getRange(startRow, 1);
  const projectionFormula = buildDailyProjectionFormula(config);
  const currentFormula = anchor.getFormula();
  if (!currentFormula) {
    if (!projectionRange.isBlank()) {
      throw mirrorError(
        "projection_collision",
        "The configured future A:C range contains manual values.",
        false
      );
    }
    anchor.setFormula(projectionFormula);
    SpreadsheetApp.flush();
  } else if (currentFormula !== projectionFormula) {
    throw mirrorError(
      "projection_formula_mismatch",
      "The daily collections projection formula is not owned by this receiver.",
      false
    );
  }
  if (anchor.getDisplayValue() === "#REF!") {
    throw mirrorError("projection_collision", "The daily projection cannot expand safely.", false);
  }
  protectRange(sheet, projectionRange, DAILY_PROJECTION_PROTECTION);

  const statusRange = sheet.getRange(config.status_cell);
  const statusFormula = buildDailyStatusFormula(config);
  const currentStatusFormula = statusRange.getFormula();
  if (!currentStatusFormula) {
    if (!statusRange.isBlank()) {
      throw mirrorError("status_collision", "The configured status cell is not empty.", false);
    }
    statusRange.setFormula(statusFormula);
    SpreadsheetApp.flush();
  } else if (currentStatusFormula !== statusFormula) {
    throw mirrorError(
      "status_formula_mismatch",
      "The daily collections status formula is not owned by this receiver.",
      false
    );
  }
  protectRange(sheet, statusRange, DAILY_STATUS_PROTECTION);
}

function assertDailyCollectionsSetup(config) {
  const logSheet = getDailyLogSheet();
  assertExactHeadersFor(logSheet, DAILY_LOG_HEADERS, DAILY_LOG_TAB_NAME);
  assertDailyLogOwnership(logSheet);
  const sheet = SpreadsheetApp.getActiveSpreadsheet().getSheetByName(config.projection_sheet);
  if (!sheet) {
    throw mirrorError("projection_sheet_missing", "The configured collections sheet is missing.", false);
  }
  assertD2Fingerprint(sheet, config);
  if (sheet.getMaxRows() < config.projection_end_row) {
    throw mirrorError(
      "projection_not_verified",
      "The daily collections projection range is incomplete.",
      false
    );
  }
  const startRow = config.manual_end_row + 1;
  const rowCount = config.projection_end_row - startRow + 1;
  const projectionRange = sheet.getRange(startRow, 1, rowCount, 3);
  const anchor = sheet.getRange(startRow, 1);
  if (
    anchor.getFormula() !== buildDailyProjectionFormula(config) ||
    anchor.getDisplayValue() === "#REF!" ||
    !hasOwnedProtection(sheet, DAILY_PROJECTION_PROTECTION, projectionRange.getA1Notation())
  ) {
    throw mirrorError(
      "projection_not_verified",
      "The daily collections projection is not verified.",
      false
    );
  }
  const statusRange = sheet.getRange(config.status_cell);
  if (
    statusRange.getFormula() !== buildDailyStatusFormula(config) ||
    !hasOwnedProtection(sheet, DAILY_STATUS_PROTECTION, statusRange.getA1Notation())
  ) {
    throw mirrorError(
      "projection_not_verified",
      "The daily collections status warning is not verified.",
      false
    );
  }
}

function assertD2Fingerprint(sheet, config) {
  const formula = sheet.getRange("D2").getFormula();
  if (!formula || !timingSafeEqual(sha256Hex(formula), config.d2_formula_sha256)) {
    throw mirrorError(
      "d2_formula_mismatch",
      "The existing D2 ARRAYFORMULA fingerprint does not match.",
      false
    );
  }
}

function buildDailyProjectionFormula(config) {
  const log = quoteSheetName(DAILY_LOG_TAB_NAME);
  const rows = config.projection_end_row - config.manual_end_row;
  return "=IFNA(LET(log,FILTER({" + log + "!A2:A," + log + "!B2:B," +
    log + "!C2:C," + log + "!D2:D," + log + "!E2:E}," + log +
    "!A2:A<>\"\"," + log + "!A2:A>=\"" + config.start_date + "\"," +
    log + "!G2:G=\"" + config.company_id + "\"," + log + "!H2:H=\"" +
    config.branch_id + "\",REGEXMATCH(" + log +
    "!W2:W,\"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$\"))," +
    "sorted,SORT(log,1,TRUE,2,FALSE),latest," +
    "SORTN(sorted,9^9,2,1,TRUE),ready,FILTER(latest,INDEX(latest,0,3)=\"ready\")," +
    "ARRAY_CONSTRAIN(ARRAYFORMULA({DATE(VALUE(LEFT(INDEX(ready,0,1),4))," +
    "VALUE(MID(INDEX(ready,0,1),6,2)),VALUE(RIGHT(INDEX(ready,0,1),2)))," +
    "INDEX(ready,0,5)/100,INDEX(ready,0,4)/100})," + rows + ",3)),)";
}

function buildDailyStatusFormula(config) {
  const log = quoteSheetName(DAILY_LOG_TAB_NAME);
  return "=IFNA(LET(log,FILTER({" + log + "!A2:A," + log + "!B2:B," +
    log + "!C2:C}," + log + "!A2:A<>\"\"," + log + "!A2:A>=\"" +
    config.start_date + "\"," + log + "!G2:G=\"" + config.company_id +
    "\"," + log + "!H2:H=\"" + config.branch_id +
    "\",REGEXMATCH(" + log +
    "!W2:W,\"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$\"))," +
    "sorted,SORT(log,1,TRUE,2,FALSE),latest,SORTN(sorted,9^9,2,1,TRUE)," +
    "blocked,FILTER(latest,INDEX(latest,0,3)=\"blocked\")," +
    "\"Needs review: \"&TEXTJOIN(\", \",TRUE,INDEX(blocked,0,1)))," +
    "\"Daily collections verified: no received day is blocked\")";
}

function quoteSheetName(name) {
  return "'" + String(name).replace(/'/g, "''") + "'";
}

function protectRange(sheet, range, description) {
  const protections = sheet.getProtections(SpreadsheetApp.ProtectionType.RANGE);
  for (let i = 0; i < protections.length; i++) {
    if (
      protections[i].getDescription() === description &&
      protections[i].getRange().getA1Notation() === range.getA1Notation()
    ) {
      lockProtectionToOwner(protections[i]);
      return;
    }
  }
  lockProtectionToOwner(range.protect().setDescription(description));
}

function lockProtectionToOwner(protection) {
  protection.setWarningOnly(false);
  const owner = Session.getEffectiveUser();
  protection.addEditor(owner);
  const editors = protection.getEditors();
  const ownerEmail = owner.getEmail();
  const remove = editors.filter(function (editor) { return editor.getEmail() !== ownerEmail; });
  if (remove.length) protection.removeEditors(remove);
  if (protection.canDomainEdit()) protection.setDomainEdit(false);
  const audiences = protection.getTargetAudiences();
  for (let i = 0; i < audiences.length; i++) {
    protection.removeTargetAudience(audiences[i]);
  }
  if (protection.getProtectionType() === SpreadsheetApp.ProtectionType.SHEET) {
    protection.setUnprotectedRanges([]);
  }
}

function assertDailyLogOwnership(sheet) {
  if (!sheet.isSheetHidden() || !hasOwnedProtection(sheet, DAILY_LOG_PROTECTION, null)) {
    throw mirrorError(
      "daily_log_not_protected",
      "The daily collections log is not hidden and owner-protected.",
      false
    );
  }
}

function hasOwnedProtection(sheet, description, a1Notation) {
  const type = a1Notation === null
    ? SpreadsheetApp.ProtectionType.SHEET
    : SpreadsheetApp.ProtectionType.RANGE;
  const protections = sheet.getProtections(type);
  for (let i = 0; i < protections.length; i++) {
    if (
      protections[i].getDescription() === description &&
      protections[i].isWarningOnly() === false &&
      (a1Notation === null || protections[i].getRange().getA1Notation() === a1Notation) &&
      protectionIsOwnerOnly(protections[i])
    ) return true;
  }
  return false;
}

function protectionIsOwnerOnly(protection) {
  if (protection.canDomainEdit()) return false;
  if (protection.getTargetAudiences().length !== 0) return false;
  if (protection.getUnprotectedRanges().length !== 0) return false;
  const ownerEmail = Session.getEffectiveUser().getEmail();
  const editors = protection.getEditors();
  for (let i = 0; i < editors.length; i++) {
    if (editors[i].getEmail() !== ownerEmail) return false;
  }
  return true;
}

function assertExactHeadersFor(sheet, expected, tabName) {
  if (sheet.getLastColumn() !== expected.length || sheet.getLastRow() < 1) {
    throw mirrorError("header_mismatch", tabName + " headers do not match.", false);
  }
  const actual = sheet.getRange(1, 1, 1, expected.length).getDisplayValues()[0];
  for (let i = 0; i < expected.length; i++) {
    if (String(actual[i]) !== expected[i]) {
      throw mirrorError("header_mismatch", tabName + " headers do not match.", false);
    }
  }
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
