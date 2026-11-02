/**
 * D Company ERP Mirror v1 — authenticated, append-only Google Sheets sink.
 *
 * Use a private, Owner-only standalone project. Before deployment, configure
 * D_COMPANY_ERP_SPREADSHEET_ID with the native target spreadsheet ID and
 * D_COMPANY_ERP_MIRROR_HMAC_SECRET with the ERP dispatcher's random secret
 * (at least 32 UTF-8 bytes). Deploy the Web app as the authorized sheet owner.
 * Workbook sharing does not grant access to this separate script project.
 *
 * Legacy mode owns only the "ERP Mirror v1" tab. Optional daily-collections
 * mode also owns a hidden append-only log plus an explicitly configured future
 * range in "SHOP SALES REVENUE". Accepted financial events are never updated
 * or deleted. Keep the workbook's unrelated receipt/OCR script intact.
 */

const MIRROR_SCHEMA = "erp-mirror.v1";
const MIRROR_SCHEMA_VERSION = 1;
const MIRROR_TAB_NAME = "ERP Mirror v1";
const HMAC_SECRET_PROPERTY = "D_COMPANY_ERP_MIRROR_HMAC_SECRET";
const SPREADSHEET_ID_PROPERTY = "D_COMPANY_ERP_SPREADSHEET_ID";
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

function getMirrorSpreadsheet() {
  const id = PropertiesService.getScriptProperties().getProperty(SPREADSHEET_ID_PROPERTY);
  if (typeof id !== "string" || !/^[A-Za-z0-9_-]{20,200}$/.test(id)) {
    throw mirrorError(
      "spreadsheet_not_configured",
      "Configure the native spreadsheet ID in the private receiver project.",
      false
    );
  }
  const spreadsheet = SpreadsheetApp.openById(id);
  if (!spreadsheet || spreadsheet.getId() !== id) {
    throw mirrorError(
      "spreadsheet_binding_mismatch",
      "The receiver could not verify its configured spreadsheet.",
      false
    );
  }
  return spreadsheet;
}

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
  if (isStaffHoursRequest(event, payload)) return appendStaffHoursIdempotently(event, payload);
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
  const spreadsheet = getMirrorSpreadsheet();
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
  const sheet = getMirrorSpreadsheet().getSheetByName(DAILY_PROJECTION_SHEET);
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
  const spreadsheet = getMirrorSpreadsheet();
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
  const sheet = getMirrorSpreadsheet().getSheetByName(DAILY_LOG_TAB_NAME);
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
  const spreadsheet = getMirrorSpreadsheet();
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
  const sheet = getMirrorSpreadsheet().getSheetByName(config.projection_sheet);
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

// Additive staff capability. No existing financial schema or setup is changed.
const STAFF_HOURS_CAPABILITY = "staff_hours.v1";
const STAFF_CONNECTION_EVENT_TYPE = "staff_hours.connection_test";
const STAFF_CONNECTION_SOURCE_TYPE = "staff_hours_configuration";
const STAFF_FACT_EVENT_TYPE = "staff_hours.fact";
const STAFF_CONFIG_PROPERTY = "D_COMPANY_ERP_STAFF_HOURS_CONFIG";
const STAFF_LOG_TAB = "ERP Staff Hours v1";
const STAFF_VIEW_TAB = "STAFF HOURS ERP";
const STAFF_MANUAL_TAB = "STAFF HOURS MANUAL OVERRIDES";
const STAFF_LOG_PROTECTION = "D Company ERP staff hours log v1";
const STAFF_VIEW_PROTECTION = "D Company ERP staff hours projection v1";
const STAFF_MANUAL_PROTECTION = "D Company ERP staff hours manual review v1";
const STAFF_LOG_LIMIT = 20000;
const STAFF_HEADERS = [
  "Record Key", "Source Revision", "Record Kind", "Staff ID", "Staff", "Branch ID",
  "Company ID", "Business Timezone", "Currency", "Clock In", "Clock Out",
  "Actual Break Minutes", "Gross Seconds", "Work Seconds", "Monthly Base Minor",
  "Daily Presence Minutes", "Planned Break Minutes", "Planned Work Minutes",
  "Working Weekday Mask", "Effective From", "Pay Status", "OT Pay Minor",
  "Payable Minor", "Needs Review", "Event ID", "Event Key", "Configuration ID",
  "Payload SHA256", "Occurred At", "Received At", "Payload JSON",
];
const STAFF_VIEW_HEADERS = STAFF_HEADERS.map(function (header, index) {
  const labels = {0: "Attendance / Policy ID", 12: "Gross Hours", 13: "Worked Hours", 14: "Monthly Base",
    15: "Daily Presence Hours", 16: "Planned Break Hours", 17: "Planned Work Hours",
    21: "OT Pay (draft, unset)", 22: "Payable (draft, unset)"};
  return labels[index] || header;
}).concat(["Effective Work Minutes", "Correction Status"]);
const STAFF_MANUAL_HEADERS = [
  "Attendance ID", "ERP Source Revision", "ERP Payload SHA256", "Reviewed Work Minutes", "Reason", "Notes",
];
const STAFF_PAYLOAD_KEYS = [
  "payload_schema", "record_kind", "record_id", "staff_id", "staff_name", "branch_id",
  "business_timezone", "currency", "clock_in_at", "clock_out_at", "actual_break_minutes",
  "gross_seconds", "work_seconds", "monthly_salary_minor", "daily_presence_minutes",
  "planned_break_minutes", "daily_work_minutes", "working_weekday_mask", "effective_from",
  "pay_status", "overtime_pay_minor", "payable_minor", "review_required",
];

function staffError(code, message) { return mirrorError(code, message, false); }
function staffUuid(name, value) {
  requireText(name, value, 36, /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
}
function readStaffHoursConfig() {
  let config;
  try { config = JSON.parse(PropertiesService.getScriptProperties().getProperty(STAFF_CONFIG_PROPERTY) || "null"); }
  catch (_error) { throw staffError("staff_config_invalid", "Staff-hours configuration is invalid."); }
  if (!isObject(config) || !hasExactKeys(config, ["company_id", "projection_end_row"]) ||
      !Number.isSafeInteger(config.projection_end_row) || config.projection_end_row < 2 ||
      config.projection_end_row > STAFF_LOG_LIMIT) {
    throw staffError("staff_config_invalid", "Staff-hours configuration is invalid.");
  }
  staffUuid("company_id", config.company_id);
  return config;
}
function staffResponse(status, event, config) {
  return jsonResponse({ok: true, status: status, event_id: event.event_id,
    payload_sha256: event.payload_sha256, schema: MIRROR_SCHEMA,
    capability: STAFF_HOURS_CAPABILITY, scope: {company_id: config.company_id}});
}
function isStaffHoursRequest(event, payload) {
  return event.event_type.indexOf("staff_hours.") === 0 ||
    event.source_type.indexOf("staff_hours.") === 0 || event.source_type === STAFF_CONNECTION_SOURCE_TYPE ||
    (typeof payload.required_capability === "string" && payload.required_capability.indexOf("staff_hours.") === 0) ||
    (typeof payload.payload_schema === "string" && payload.payload_schema.indexOf("staff_hours.") === 0);
}
function staffTimestampMicros(name, value) {
  if (typeof value !== "string" || !/^(?:20\d{2}|2100)-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$/.test(value)) {
    throw staffError("staff_timestamp_invalid", name + " must be an exact UTC timestamp.");
  }
  const whole = Date.parse(value.slice(0, 19) + "Z");
  if (isNaN(whole) || new Date(whole).toISOString().slice(0, 19) !== value.slice(0, 19)) {
    throw staffError("staff_timestamp_invalid", name + " is invalid.");
  }
  return whole * 1000 + Number(value.slice(20, 26));
}
function validateStaffFact(event, payload, config) {
  if (!hasExactKeys(payload, STAFF_PAYLOAD_KEYS) || payload.payload_schema !== STAFF_HOURS_CAPABILITY ||
      event.event_type !== STAFF_FACT_EVENT_TYPE || event.company_id !== config.company_id ||
      (payload.record_kind !== "attendance" && payload.record_kind !== "policy") ||
      event.source_type !== "staff_hours." + payload.record_kind || event.source_id !== payload.record_id ||
      !/^\d{20}$/.test(event.source_revision) || /^0+$/.test(event.source_revision)) {
    throw staffError("staff_payload_invalid", "Staff-hours fact identity or schema is invalid.");
  }
  staffUuid("record_id", payload.record_id); staffUuid("staff_id", payload.staff_id);
  requirePlainText("staff_name", payload.staff_name, 200);
  requirePlainText("business_timezone", payload.business_timezone, 64);
  requirePlainText("currency", payload.currency, 8);
  if (payload.pay_status !== "draft" || payload.overtime_pay_minor !== null || payload.payable_minor !== null ||
      typeof payload.review_required !== "boolean") {
    throw staffError("staff_pay_not_draft", "Staff-hours facts cannot post pay or overtime amounts.");
  }
  const policyFields = ["monthly_salary_minor", "daily_presence_minutes", "planned_break_minutes",
    "daily_work_minutes", "working_weekday_mask", "effective_from"];
  const attendanceFields = ["branch_id", "clock_in_at", "clock_out_at", "actual_break_minutes", "gross_seconds", "work_seconds"];
  if (payload.record_kind === "attendance") {
    for (let i = 0; i < policyFields.length; i++) {
      if (payload[policyFields[i]] !== null) throw staffError("staff_payload_invalid", "Attendance cannot infer pay policy.");
    }
    staffUuid("branch_id", payload.branch_id);
    const entered = staffTimestampMicros("clock_in_at", payload.clock_in_at);
    if (payload.clock_out_at === null) {
      if (payload.actual_break_minutes !== null || payload.gross_seconds !== null || payload.work_seconds !== null || !payload.review_required) {
        throw staffError("staff_payload_invalid", "Open attendance is provisional.");
      }
    } else {
      const exited = staffTimestampMicros("clock_out_at", payload.clock_out_at);
      const gross = Math.floor((exited - entered) / 1000000);
      if (exited < entered || payload.gross_seconds !== gross || !Number.isSafeInteger(payload.gross_seconds)) {
        throw staffError("staff_payload_invalid", "Attendance duration is inconsistent.");
      }
      if (payload.actual_break_minutes === null) {
        if (payload.work_seconds !== null || !payload.review_required) throw staffError("staff_payload_invalid", "Unknown break must remain unknown.");
      } else {
        const minutes = payload.actual_break_minutes;
        if (!Number.isSafeInteger(minutes) || minutes < 0 || minutes > 1440 || minutes * 60 > gross ||
            payload.work_seconds !== gross - minutes * 60 || payload.review_required) {
          throw staffError("staff_payload_invalid", "Explicit attendance break/work duration is inconsistent.");
        }
      }
    }
  } else {
    for (let i = 0; i < attendanceFields.length; i++) {
      if (payload[attendanceFields[i]] !== null) throw staffError("staff_payload_invalid", "Policy cannot invent attendance.");
    }
    for (let i = 0; i < policyFields.length - 1; i++) requireSafeInteger(policyFields[i], payload[policyFields[i]], false);
    if (payload.monthly_salary_minor > 1000000000000 || payload.daily_presence_minutes < 1 || payload.daily_presence_minutes > 1440 ||
        payload.planned_break_minutes >= payload.daily_presence_minutes ||
        payload.daily_work_minutes !== payload.daily_presence_minutes - payload.planned_break_minutes ||
        payload.working_weekday_mask < 1 || payload.working_weekday_mask > 127 || !payload.review_required) {
      throw staffError("staff_payload_invalid", "Draft work policy is inconsistent.");
    }
    requireIsoDate("effective_from", payload.effective_from);
  }
}
function staffRow(event, payload, receivedAt) {
  const cell = function (value) { return value === null ? "" : value; };
  return [event.source_type + ":" + event.source_id, event.source_revision, payload.record_kind,
    payload.staff_id, safeText(payload.staff_name), cell(payload.branch_id), event.company_id,
    safeText(payload.business_timezone), safeText(payload.currency), cell(payload.clock_in_at), cell(payload.clock_out_at),
    cell(payload.actual_break_minutes), cell(payload.gross_seconds), cell(payload.work_seconds), cell(payload.monthly_salary_minor),
    cell(payload.daily_presence_minutes), cell(payload.planned_break_minutes), cell(payload.daily_work_minutes),
    cell(payload.working_weekday_mask), cell(payload.effective_from), "draft", "", "", payload.review_required,
    event.event_id, safeText(event.event_key), event.configuration_id, event.payload_sha256,
    event.occurred_at, receivedAt, event.payload_json];
}
/** Same decision contract as the projection formula, independently executable in tests. */
function staffEffectiveWork(row, manualRows) {
  if (row[2] !== "attendance") return {minutes: null, status: "Draft policy"};
  const known = row[23] === false && Number.isSafeInteger(row[13]) && row[13] >= 0;
  const fallback = known ? row[13] / 60 : null;
  const id = String(row[0]).slice(-36).toLowerCase();
  const matching = manualRows.filter(function (manual) { return String(manual[0]).toLowerCase() === id; });
  if (matching.length === 0) return {minutes: fallback, status: known ? "ERP fact" : "ERP unknown"};
  const current = matching.filter(function (manual) {
    return String(manual[1]) === String(row[1]) && String(manual[2]).toLowerCase() === String(row[27]);
  });
  if (current.length > 1) return {minutes: fallback, status: "Ambiguous correction"};
  const manual = current.length === 1 ? current[0] : matching[0];
  if (!/^\d{20}$/.test(String(manual[1])) || !/^[a-f0-9]{64}$/.test(String(manual[2]))) {
    return {minutes: fallback, status: "Invalid correction"};
  }
  if (String(manual[1]) !== String(row[1]) || String(manual[2]) !== String(row[27])) {
    return {minutes: fallback, status: "Stale correction"};
  }
  const minutes = manual[3];
  const numeric = typeof minutes === "number" ? Number.isSafeInteger(minutes) :
    typeof minutes === "string" && /^(0|[1-9][0-9]{0,4})$/.test(minutes);
  if (!numeric || Number(minutes) < 0 || Number(minutes) > 10080 || typeof manual[4] !== "string" ||
      manual[4].trim().length === 0 || manual[4].length > 500) {
    return {minutes: fallback, status: "Invalid correction"};
  }
  return {minutes: Number(minutes), status: "Manual correction"};
}
function staffProjectionFormula(config) {
  const log = quoteSheetName(STAFF_LOG_TAB);
  const manual = quoteSheetName(STAFF_MANUAL_TAB);
  const range = manual + "!A2:F" + config.projection_end_row;
  const columns = [];
  const policyUnits = {15: 100, 16: 60, 17: 60, 18: 60};
  for (let column = 1; column <= STAFF_HEADERS.length; column++) {
    const value = "INDEX(latest,0," + column + ")";
    if (column === 1) columns.push("ids");
    else if (column === 22 || column === 23) columns.push('IF(kind<>"", "", "")');
    else if (column >= 15 && column <= 20) columns.push('IF(kind="policy",' + value + (policyUnits[column] ? "/" + policyUnits[column] : "") + ',"")');
    else if (column === 6 || column === 10) columns.push('IF(kind="attendance",' + value + ',"")');
    else if (column === 11) columns.push('IF(closed,' + value + ',"")');
    else if (column === 12) columns.push('IF(known,' + value + ',"")');
    else if (column === 13) columns.push('IF(closed,' + value + '/3600,"")');
    else if (column === 14) columns.push('IF(known,' + value + '/3600,"")');
    else columns.push(value);
  }
  columns.push('IF(kind="attendance",IF(status="Manual correction",minutes,IF(known,work/60,"")),"")');
  columns.push("status");
  const lookup = function (column) {
    return 'IF(cmatches>0,IFERROR(VLOOKUP(keys,manualdata,' + (column + 1) + ',FALSE),""),' +
      'IFERROR(VLOOKUP(ids,' + range + ',' + column + ',FALSE),""))';
  };
  return "=IFNA(ARRAYFORMULA(LET(log,FILTER(" + log + "!A2:AE," + log + "!A2:A<>\"\"," + log +
    "!G2:G=\"" + config.company_id + "\",REGEXMATCH(" + log +
    "!AD2:AD,\"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{3}Z$\"))," +
    "sorted,SORT(log,1,TRUE,2,FALSE),latest,SORTN(sorted,9^9,2,1,TRUE)," +
    'kind,INDEX(latest,0,3),ids,RIGHT(INDEX(latest,0,1),36),revision,TO_TEXT(INDEX(latest,0,2)),' +
    'hash,TO_TEXT(INDEX(latest,0,28)),work,INDEX(latest,0,14),' +
    'known,(kind="attendance")*(INDEX(latest,0,24)=FALSE)*ISNUMBER(work)*(work>=0)*(IFERROR(INT(work),-1)=work),' +
    'closed,(kind="attendance")*REGEXMATCH(TO_TEXT(INDEX(latest,0,11)),"Z$"),' +
    'matches,COUNTIF(' + manual + '!A2:A' + config.projection_end_row + ',ids),' +
    'manualdata,IFERROR(FILTER({LOWER(IFERROR(' + manual + '!A2:A' + config.projection_end_row + ',""))&"|"&IFERROR(' + manual + '!B2:B' + config.projection_end_row + ',"")&"|"&LOWER(IFERROR(' + manual + '!C2:C' + config.projection_end_row + ',"")),IFERROR(' + range + ',"")},IFERROR(' + manual + '!A2:A' + config.projection_end_row + ',"")<>""),{"","","","","","",""}),' +
    'manualkeys,INDEX(manualdata,0,1),keys,LOWER(ids)&"|"&revision&"|"&hash,' +
    'cmatches,MAP(keys,LAMBDA(currentkey,SUM(N(EXACT(manualkeys,currentkey))))),' +
    'mrevision,' + lookup(2) + ',mhash,' + lookup(3) + ',mminutes,' + lookup(4) + ',reason,' + lookup(5) + ',' +
    'proof,REGEXMATCH(TO_TEXT(mrevision),"^[0-9]{20}$")*REGEXMATCH(TO_TEXT(mhash),"^[a-f0-9]{64}$"),' +
    'current,EXACT(TO_TEXT(mrevision),revision)*EXACT(TO_TEXT(mhash),hash),' +
    'minutes,IFERROR(VALUE(mminutes),-1),' +
    'validminutes,IF(ISNUMBER(mminutes),(IFERROR(INT(mminutes),-1)=mminutes),REGEXMATCH(TO_TEXT(mminutes),"^(0|[1-9][0-9]{0,4})$"))*(minutes>=0)*(minutes<=10080),' +
    'validreason,ISTEXT(reason)*(LEN(TRIM(reason))>0)*(LEN(reason)<=500),' +
    'status,IF(kind<>"attendance","Draft policy",IF(matches=0,IF(known,"ERP fact","ERP unknown"),' +
    'IF(cmatches>1,"Ambiguous correction",IF(proof=0,"Invalid correction",IF(current=0,"Stale correction",' +
    'IF(validminutes*validreason,"Manual correction","Invalid correction")))))),' +
    "ARRAY_CONSTRAIN({" + columns.join(",") + "}," + (config.projection_end_row - 1) + ",33))),)";
}
function staffSheet(name, headers, create) {
  const book = getMirrorSpreadsheet();
  let sheet = book.getSheetByName(name);
  if (!sheet && create) {
    sheet = book.insertSheet(name);
    sheet.getRange(1, 1, 1, headers.length).setValues([headers]);
    sheet.setFrozenRows(1);
  }
  if (!sheet) throw staffError("staff_setup_missing", "Run the staff-hours Owner setup first.");
  assertExactHeadersFor(sheet, headers, name);
  return sheet;
}
function staffProtectSheet(sheet, description, hide) {
  const protections = sheet.getProtections(SpreadsheetApp.ProtectionType.SHEET);
  let protection = null;
  for (let i = 0; i < protections.length; i++) if (protections[i].getDescription() === description) protection = protections[i];
  if (!protection) protection = sheet.protect().setDescription(description);
  lockProtectionToOwner(protection);
  if (hide && !sheet.isSheetHidden()) sheet.hideSheet();
}
function assertStaffHoursSetup(config) {
  const log = staffSheet(STAFF_LOG_TAB, STAFF_HEADERS, false);
  const view = staffSheet(STAFF_VIEW_TAB, STAFF_VIEW_HEADERS, false);
  const manual = staffSheet(STAFF_MANUAL_TAB, STAFF_MANUAL_HEADERS, false);
  const range = view.getRange(2, 1, config.projection_end_row - 1, STAFF_VIEW_HEADERS.length);
  if (manual.getLastRow() > config.projection_end_row) throw staffError("staff_manual_capacity_exceeded", "Manual staff corrections exceed the supported range.");
  if (!log.isSheetHidden() || !hasOwnedProtection(log, STAFF_LOG_PROTECTION, null) ||
      !hasOwnedProtection(view, STAFF_VIEW_PROTECTION, range.getA1Notation()) ||
      !hasOwnedProtection(manual, STAFF_MANUAL_PROTECTION, null) ||
      view.getMaxRows() < config.projection_end_row || view.getRange("A2").getFormula() !== staffProjectionFormula(config) ||
      /^#/.test(view.getRange("A2").getDisplayValue())) {
    throw staffError("staff_projection_not_verified", "Staff-hours log/projection/manual-review ownership is not verified.");
  }
  if (log.getLastRow() > STAFF_LOG_LIMIT) throw staffError("staff_capacity_exceeded", "Staff-hours log limit exceeded.");
  if (log.getLastRow() >= 2) {
    const companies = log.getRange(2, 7, log.getLastRow() - 1, 1).getDisplayValues();
    for (let i = 0; i < companies.length; i++) {
      if (String(companies[i][0]) !== config.company_id) throw staffError("staff_scope_mismatch", "Staff-hours log company changed.");
    }
  }
  return log;
}
function setupStaffHoursProjection() {
  const lock = LockService.getScriptLock();
  if (!lock.tryLock(30000)) throw mirrorError("lock_timeout", "The mirror is busy; retry later.", true);
  try {
    const config = readStaffHoursConfig();
    const log = staffSheet(STAFF_LOG_TAB, STAFF_HEADERS, true);
    const view = staffSheet(STAFF_VIEW_TAB, STAFF_VIEW_HEADERS, true);
    const manual = staffSheet(STAFF_MANUAL_TAB, STAFF_MANUAL_HEADERS, true);
    if (manual.getMaxRows() < config.projection_end_row) manual.insertRowsAfter(manual.getMaxRows(), config.projection_end_row - manual.getMaxRows());
    // Preserve exact source-revision/hash text and all existing manual values.
    manual.getRange(2, 1, config.projection_end_row - 1, 3).setNumberFormat("@");
    if (view.getMaxRows() < config.projection_end_row) view.insertRowsAfter(view.getMaxRows(), config.projection_end_row - view.getMaxRows());
    const range = view.getRange(2, 1, config.projection_end_row - 1, STAFF_VIEW_HEADERS.length);
    const formula = view.getRange("A2").getFormula();
    if (!formula) {
      if (!range.isBlank()) throw staffError("staff_projection_collision", "Staff-hours projection contains manual values.");
      view.getRange("A2").setFormula(staffProjectionFormula(config));
      SpreadsheetApp.flush();
    } else if (formula !== staffProjectionFormula(config)) throw staffError("staff_projection_not_verified", "Staff-hours formula changed.");
    staffProtectSheet(log, STAFF_LOG_PROTECTION, true);
    staffProtectSheet(manual, STAFF_MANUAL_PROTECTION, false);
    protectRange(view, range, STAFF_VIEW_PROTECTION);
    assertStaffHoursSetup(config);
    return {ok: true, capability: STAFF_HOURS_CAPABILITY, scope: {company_id: config.company_id}};
  } finally { lock.releaseLock(); }
}
function appendStaffHoursIdempotently(event, payload) {
  const config = readStaffHoursConfig();
  if (event.company_id !== config.company_id) throw staffError("staff_scope_mismatch", "Staff-hours company does not match.");
  const log = assertStaffHoursSetup(config);
  if (event.event_type === STAFF_CONNECTION_EVENT_TYPE) {
    if (event.source_type !== STAFF_CONNECTION_SOURCE_TYPE || event.source_revision !== event.configuration_id ||
        !hasExactKeys(payload, ["required_capability", "scope", "status"]) ||
        payload.required_capability !== STAFF_HOURS_CAPABILITY || payload.status !== "test" ||
        !isObject(payload.scope) || !hasExactKeys(payload.scope, ["company_id"]) || payload.scope.company_id !== config.company_id) {
      throw staffError("staff_connection_invalid", "Staff-hours capability test does not match.");
    }
    staffUuid("source_id", event.source_id);
    return staffResponse("accepted", event, config);
  }
  validateStaffFact(event, payload, config);
  const existing = findTextRow(log, 25, event.event_id);
  if (existing !== null) {
    const stored = log.getRange(existing, 1, 1, STAFF_HEADERS.length).getValues()[0];
    const receivedAt = stored[29];
    if (!isCanonicalIsoTimestamp(receivedAt) || !rowsEqual(stored, staffRow(event, payload, receivedAt))) {
      throw staffError("staff_event_conflict", "The retained staff-hours event differs or is incomplete.");
    }
    return staffResponse("duplicate", event, config);
  }
  const key = event.source_type + ":" + event.source_id;
  const keys = {};
  if (log.getLastRow() >= 2) {
    const previous = log.getRange(2, 1, log.getLastRow() - 1, STAFF_HEADERS.length).getValues();
    for (let i = 0; i < previous.length; i++) {
      if (String(previous[i][6]) !== config.company_id) throw staffError("staff_scope_mismatch", "Staff-hours log company changed.");
      keys[String(previous[i][0])] = true;
      if (String(previous[i][0]) === key && String(previous[i][1]) === event.source_revision) {
        throw staffError("staff_revision_conflict", "This staff-hours source revision already exists.");
      }
    }
  }
  keys[key] = true;
  if (Object.keys(keys).length > config.projection_end_row - 1 || log.getLastRow() >= STAFF_LOG_LIMIT) {
    throw staffError("staff_capacity_exceeded", "The staff-hours log/projection has no remaining capacity.");
  }
  const next = log.getLastRow() + 1;
  if (next > log.getMaxRows()) {
    log.insertRowsAfter(log.getMaxRows(), Math.min(1000, STAFF_LOG_LIMIT - log.getMaxRows()));
  }
  log.getRange(next, 1, 1, 11).setNumberFormat("@");
  log.getRange(next, 20, 1, 2).setNumberFormat("@");
  log.getRange(next, 25, 1, 7).setNumberFormat("@");
  appendVerifiedRow(log, staffRow(event, payload, new Date().toISOString()), STAFF_HEADERS.length);
  return staffResponse("accepted", event, config);
}
