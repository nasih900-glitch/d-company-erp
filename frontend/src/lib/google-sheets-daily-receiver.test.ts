import { createHash, createHmac } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { createContext, runInContext } from 'node:vm';
import { beforeEach, describe, expect, it } from 'vitest';

const source = readFileSync(
  resolve(process.cwd(), '../integrations/google-sheets/Code.gs'),
  'utf8',
);
const SECRET = 'receiver-contract-secret-with-more-than-32-bytes';
const COMPANY_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb';
const BRANCH_ID = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc';
const CONFIGURATION_ID = 'dddddddd-dddd-4ddd-8ddd-dddddddddddd';
const D2_FORMULA = '=ARRAYFORMULA(IF(A2:A="",,B2:B+C2:C))';

type Cell = { value?: unknown; formula?: string; numberFormat?: string };

class ProtectionMock {
  warningOnly = false;
  description = '';
  domainEdit = false;
  editors = [{ getEmail: () => 'owner@example.test' }];
  targetAudiences: string[] = [];
  unprotectedRanges: RangeMock[] = [];

  constructor(readonly range: RangeMock | null) {}
  setDescription(value: string) { this.description = value; return this; }
  getDescription() { return this.description; }
  setWarningOnly(value: boolean) { this.warningOnly = value; return this; }
  isWarningOnly() { return this.warningOnly; }
  addEditor() { return this; }
  getEditors() { return this.editors; }
  removeEditors() { return this; }
  canDomainEdit() { return this.domainEdit; }
  setDomainEdit(value: boolean) { this.domainEdit = value; return this; }
  getTargetAudiences() { return [...this.targetAudiences]; }
  removeTargetAudience(audienceId: string) {
    this.targetAudiences = this.targetAudiences.filter((value) => value !== audienceId);
    return this;
  }
  getProtectionType() { return this.range ? 'RANGE' : 'SHEET'; }
  getUnprotectedRanges() { return [...this.unprotectedRanges]; }
  setUnprotectedRanges(ranges: RangeMock[]) { this.unprotectedRanges = [...ranges]; return this; }
  getRange() { if (!this.range) throw new Error('sheet protection has no range'); return this.range; }
}

class RangeMock {
  constructor(
    readonly sheet: SheetMock,
    readonly row: number,
    readonly column: number,
    readonly rows = 1,
    readonly columns = 1,
  ) {}

  setValues(values: unknown[][]) {
    for (let r = 0; r < this.rows; r += 1) {
      for (let c = 0; c < this.columns; c += 1) {
        this.sheet.cell(this.row + r, this.column + c).value = values[r]?.[c] ?? '';
      }
    }
    if (this.sheet.workbook.corruptNextTailWrite && this.row > 1) {
      this.sheet.workbook.corruptNextTailWrite = false;
      this.sheet.cell(this.row, this.column + this.columns - 1).value = 'corrupt';
    }
    return this;
  }

  setValue(value: unknown) {
    this.sheet.cell(this.row, this.column).value = value;
    return this;
  }

  getValues() {
    return Array.from({ length: this.rows }, (_, r) =>
      Array.from({ length: this.columns }, (_, c) => {
        const cell = this.sheet.cell(this.row + r, this.column + c);
        return cell.formula ?? cell.value ?? '';
      }),
    );
  }

  getDisplayValues() { return this.getValues().map((row) => row.map(String)); }
  getDisplayValue() { return String(this.getValues()[0]?.[0] ?? ''); }
  setFormula(formula: string) { this.sheet.cell(this.row, this.column).formula = formula; return this; }
  getFormula() { return this.sheet.cell(this.row, this.column).formula ?? ''; }
  setNumberFormat(format: string) {
    this.sheet.cell(this.row, this.column).numberFormat = format;
    return this;
  }
  isBlank() {
    return this.getCells().every((cell) => cell.value === undefined && cell.formula === undefined);
  }
  setFontWeight() { return this; }
  setBackground() { return this; }
  setFontColor() { return this; }
  protect() {
    const protection = new ProtectionMock(this);
    this.sheet.rangeProtections.push(protection);
    return protection;
  }
  createTextFinder(value: string) {
    return {
      matchEntireCell: () => ({
        findNext: () => {
          for (let r = 0; r < this.rows; r += 1) {
            if (String(this.sheet.cell(this.row + r, this.column).value ?? '') === value) {
              return { getRow: () => this.row + r };
            }
          }
          return null;
        },
      }),
    };
  }
  getA1Notation() {
    const start = `${columnName(this.column)}${this.row}`;
    if (this.rows === 1 && this.columns === 1) return start;
    return `${start}:${columnName(this.column + this.columns - 1)}${this.row + this.rows - 1}`;
  }
  private getCells() {
    const result: Cell[] = [];
    for (let r = 0; r < this.rows; r += 1) {
      for (let c = 0; c < this.columns; c += 1) {
        result.push(this.sheet.cell(this.row + r, this.column + c));
      }
    }
    return result;
  }
}

class SheetMock {
  readonly cells = new Map<string, Cell>();
  readonly rangeProtections: ProtectionMock[] = [];
  readonly sheetProtections: ProtectionMock[] = [];
  hidden = false;
  maxRows = 1000;

  constructor(readonly workbook: WorkbookMock, readonly name: string) {}
  cell(row: number, column: number) {
    const key = `${row}:${column}`;
    let cell = this.cells.get(key);
    if (!cell) { cell = {}; this.cells.set(key, cell); }
    return cell;
  }
  getRange(rowOrA1: number | string, column?: number, rows?: number, columns?: number) {
    if (typeof rowOrA1 === 'string') {
      const match = /^([A-Z]+)([1-9]\d*)$/.exec(rowOrA1);
      if (!match) throw new Error(`unsupported A1 notation ${rowOrA1}`);
      return new RangeMock(this, Number(match[2]), columnNumber(match[1]));
    }
    return new RangeMock(this, rowOrA1, column ?? 1, rows ?? 1, columns ?? 1);
  }
  getLastRow() {
    let result = 0;
    for (const [key, cell] of this.cells) {
      if (cell.value !== undefined || cell.formula !== undefined) result = Math.max(result, Number(key.split(':')[0]));
    }
    return result;
  }
  getLastColumn() {
    let result = 0;
    for (const [key, cell] of this.cells) {
      if (cell.value !== undefined || cell.formula !== undefined) result = Math.max(result, Number(key.split(':')[1]));
    }
    return result;
  }
  setFrozenRows() { return this; }
  getMaxRows() { return this.maxRows; }
  insertRowsAfter(_after: number, count: number) { this.maxRows += count; return this; }
  hideSheet() { this.hidden = true; return this; }
  isSheetHidden() { return this.hidden; }
  protect() {
    const protection = new ProtectionMock(null);
    this.sheetProtections.push(protection);
    return protection;
  }
  getProtections(type: string) {
    return type === 'SHEET' ? this.sheetProtections : this.rangeProtections;
  }
  deleteRow(row: number) {
    if (this.workbook.failDelete) throw new Error('simulated cleanup failure');
    for (const key of [...this.cells.keys()]) {
      if (Number(key.split(':')[0]) === row) this.cells.delete(key);
    }
  }
}

class WorkbookMock {
  readonly sheets = new Map<string, SheetMock>();
  corruptNextTailWrite = false;
  failDelete = false;
  failNextResponse = false;
  getSheetByName(name: string) { return this.sheets.get(name) ?? null; }
  insertSheet(name: string) {
    const sheet = new SheetMock(this, name);
    this.sheets.set(name, sheet);
    return sheet;
  }
}

function columnNumber(name: string) {
  return [...name].reduce((value, character) => value * 26 + character.charCodeAt(0) - 64, 0);
}

function columnName(value: number) {
  let result = '';
  while (value > 0) {
    value -= 1;
    result = String.fromCharCode(65 + (value % 26)) + result;
    value = Math.floor(value / 26);
  }
  return result;
}

function digest(value: string) {
  return createHash('sha256').update(value, 'utf8').digest('hex');
}

function loadReceiver(options: { projectionCollision?: boolean; projectionEndRow?: number } = {}) {
  const workbook = new WorkbookMock();
  const projection = workbook.insertSheet('SHOP SALES REVENUE');
  projection.getRange('D2').setFormula(D2_FORMULA);
  if (options.projectionCollision) projection.getRange(94, 2).setValues([['manual']]);
  const config = {
    branch_id: BRANCH_ID,
    business_timezone: 'Asia/Kolkata',
    company_id: COMPANY_ID,
    d2_formula_sha256: digest(D2_FORMULA),
    manual_end_row: 93,
    projection_end_row: options.projectionEndRow ?? 5000,
    projection_sheet: 'SHOP SALES REVENUE',
    start_date: '2026-10-01',
    status_cell: 'E2',
  };
  const properties = new Map([
    ['D_COMPANY_ERP_MIRROR_HMAC_SECRET', SECRET],
    ['D_COMPANY_ERP_DAILY_COLLECTIONS_CONFIG', JSON.stringify(config)],
  ]);
  const context = createContext({
    Date,
    JSON,
    Math,
    Error,
    Array,
    Object,
    String,
    Number,
    RegExp,
    isNaN,
    PropertiesService: {
      getScriptProperties: () => ({ getProperty: (key: string) => properties.get(key) ?? null }),
    },
    LockService: { getScriptLock: () => ({ tryLock: () => true, releaseLock: () => undefined }) },
    SpreadsheetApp: {
      getActiveSpreadsheet: () => workbook,
      flush: () => undefined,
      ProtectionType: { SHEET: 'SHEET', RANGE: 'RANGE' },
    },
    Session: { getEffectiveUser: () => ({ getEmail: () => 'owner@example.test' }) },
    Utilities: {
      DigestAlgorithm: { SHA_256: 'SHA_256' },
      Charset: { UTF_8: 'UTF_8' },
      computeDigest: (_algorithm: string, value: string) => [...createHash('sha256').update(value, 'utf8').digest()],
      computeHmacSha256Signature: (value: string, secret: string) => [...createHmac('sha256', secret).update(value, 'utf8').digest()],
      newBlob: (value: string) => ({ getBytes: () => [...Buffer.from(value, 'utf8')] }),
    },
    ContentService: {
      MimeType: { JSON: 'JSON' },
      createTextOutput: (body: string) => {
        if (workbook.failNextResponse) {
          workbook.failNextResponse = false;
          throw new Error('simulated lost acknowledgement');
        }
        return { body, setMimeType() { return this; } };
      },
    },
  });
  runInContext(`${source}\nthis.receiver = {doPost, setupDailyCollectionsProjection};`, context);
  const receiver = context.receiver as {
    doPost: (request: unknown) => { body: string };
    setupDailyCollectionsProjection: () => unknown;
  };
  return { workbook, projection, receiver, config };
}

function dailyPayload(overrides: Record<string, unknown> = {}) {
  return {
    blockers: [],
    branch: 'Main',
    business_date: '2026-10-10',
    business_timezone: 'Asia/Kolkata',
    cash_minor: 1200,
    currency: 'INR',
    export_mode: 'daily_collections',
    legacy_refund_count: 0,
    movement_count: 2,
    open_shift_count: 0,
    shift_count: 1,
    status: 'ready',
    total_minor: 1500,
    unsupported_rails: [],
    upi_minor: 300,
    ...overrides,
  };
}

function event(payload: Record<string, unknown>, overrides: Record<string, unknown> = {}) {
  const payloadJson = JSON.stringify(payload);
  return {
    company_id: COMPANY_ID,
    configuration_id: CONFIGURATION_ID,
    event_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    event_key: 'daily:test',
    event_type: 'collections.daily_snapshot',
    occurred_at: '2026-10-11T00:00:00Z',
    payload_json: payloadJson,
    payload_sha256: digest(payloadJson),
    schema: 'erp-mirror.v1',
    schema_version: 1,
    source_id: `${BRANCH_ID}:2026-10-10`,
    source_revision: '00000000000000000001',
    source_type: 'daily_collections',
    ...overrides,
  };
}

function eventForDay(
  businessDate: string,
  sourceRevision: string,
  eventId: string,
  payloadOverrides: Record<string, unknown> = {},
) {
  const payload = dailyPayload({ business_date: businessDate, ...payloadOverrides });
  return event(payload, {
    event_id: eventId,
    event_key: `daily:${businessDate}:${sourceRevision}`,
    source_id: `${BRANCH_ID}:${businessDate}`,
    source_revision: sourceRevision,
  });
}

function send(receiver: ReturnType<typeof loadReceiver>['receiver'], signedEvent: Record<string, unknown>) {
  const signedPayload = JSON.stringify(signedEvent);
  const request = {
    schema: 'erp-mirror.v1',
    signature: createHmac('sha256', SECRET).update(signedPayload, 'utf8').digest('hex'),
    signature_algorithm: 'HMAC-SHA256',
    signed_payload: signedPayload,
  };
  return JSON.parse(receiver.doPost({ postData: { contents: JSON.stringify(request) } }).body) as Record<string, unknown>;
}

describe('daily collections Apps Script receiver', () => {
  let loaded: ReturnType<typeof loadReceiver>;

  beforeEach(() => {
    loaded = loadReceiver();
    loaded.receiver.setupDailyCollectionsProjection();
  });

  it('accepts a signed scoped row and returns the exact capability acknowledgement', () => {
    const response = send(loaded.receiver, event(dailyPayload()));
    expect(response).toMatchObject({
      ok: true,
      status: 'accepted',
      capability: 'daily_collections.v1',
      scope: { branch_id: BRANCH_ID, business_timezone: 'Asia/Kolkata', start_date: '2026-10-01' },
    });
    const log = loaded.workbook.getSheetByName('ERP Daily Collections v1');
    expect(log?.hidden).toBe(true);
    expect(log?.getRange(2, 1, 1, 25).getDisplayValues()[0]?.slice(0, 8)).toEqual([
      '2026-10-10', '00000000000000000001', 'ready', '1200', '300', '1500', COMPANY_ID, BRANCH_ID,
    ]);
  });

  it('returns duplicate only for the same immutable event and rejects a revision conflict', () => {
    expect(send(loaded.receiver, event(dailyPayload())).status).toBe('accepted');
    expect(send(loaded.receiver, event(dailyPayload())).status).toBe('duplicate');
    expect(send(loaded.receiver, event(dailyPayload(), { event_key: 'daily:changed' })))
      .toMatchObject({ ok: false, error_code: 'event_identity_conflict' });
    const conflict = event(dailyPayload({ cash_minor: 1300, total_minor: 1600 }), {
      event_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    });
    expect(send(loaded.receiver, conflict)).toMatchObject({ ok: false, error_code: 'source_revision_conflict' });
  });

  it('rejects a tampered signed payload before touching the workbook', () => {
    const signedEvent = event(dailyPayload());
    const signedPayload = JSON.stringify(signedEvent);
    const request = {
      schema: 'erp-mirror.v1',
      signature: createHmac('sha256', SECRET).update(signedPayload, 'utf8').digest('hex'),
      signature_algorithm: 'HMAC-SHA256',
      signed_payload: signedPayload.replace('1200', '1201'),
    };
    const response = JSON.parse(loaded.receiver.doPost({ postData: { contents: JSON.stringify(request) } }).body);
    expect(response).toMatchObject({ ok: false, error_code: 'signature_invalid', retryable: false });
    expect(loaded.workbook.getSheetByName('ERP Daily Collections v1')?.getLastRow()).toBe(1);
  });

  it('rejects the wrong company or branch scope', () => {
    expect(send(loaded.receiver, event(dailyPayload(), { company_id: 'ffffffff-ffff-4fff-8fff-ffffffffffff' })))
      .toMatchObject({ ok: false, error_code: 'daily_scope_mismatch' });
    expect(send(loaded.receiver, event(dailyPayload(), { source_id: 'ffffffff-ffff-4fff-8fff-ffffffffffff:2026-10-10' })))
      .toMatchObject({ ok: false, error_code: 'daily_scope_mismatch' });
  });

  it('fails closed when the log header or owned projection formula changes', () => {
    const log = loaded.workbook.getSheetByName('ERP Daily Collections v1');
    log?.getRange(1, 1).setValues([['Wrong']]);
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({ ok: false, error_code: 'header_mismatch' });

    loaded = loadReceiver();
    loaded.receiver.setupDailyCollectionsProjection();
    loaded.projection.getRange(94, 1).setFormula('=1');
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({ ok: false, error_code: 'projection_not_verified' });
  });

  it('clears sheet holes and target audiences during setup, then rejects either grant at runtime', () => {
    const log = loaded.workbook.getSheetByName('ERP Daily Collections v1');
    const logProtection = log?.sheetProtections[0];
    const projectionProtection = loaded.projection.rangeProtections.find(
      (protection) => protection.description === 'D Company ERP daily collections projection',
    );
    expect(logProtection).toBeDefined();
    expect(projectionProtection).toBeDefined();

    logProtection?.unprotectedRanges.push(log!.getRange(2, 1));
    logProtection?.targetAudiences.push('audience-log');
    projectionProtection?.targetAudiences.push('audience-projection');
    loaded.receiver.setupDailyCollectionsProjection();
    expect(logProtection?.unprotectedRanges).toEqual([]);
    expect(logProtection?.targetAudiences).toEqual([]);
    expect(projectionProtection?.targetAudiences).toEqual([]);

    logProtection?.unprotectedRanges.push(log!.getRange(2, 1));
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'daily_log_not_protected', retryable: false,
    });
    logProtection!.unprotectedRanges = [];
    projectionProtection?.targetAudiences.push('audience-projection');
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'projection_not_verified', retryable: false,
    });
  });

  it('refuses owner setup when the future projection range contains a manual value', () => {
    const collision = loadReceiver({ projectionCollision: true });
    expect(() => collision.receiver.setupDailyCollectionsProjection()).toThrow('future A:C range');
  });

  it('preserves out-of-order fixed-width revisions and lets the formula select lexically', () => {
    expect(send(loaded.receiver, event(dailyPayload(), { source_revision: '00000000000000000003' })).status).toBe('accepted');
    expect(send(loaded.receiver, event(dailyPayload({ cash_minor: 1100, total_minor: 1400 }), {
      event_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      source_revision: '00000000000000000002',
    })).status).toBe('accepted');
    const log = loaded.workbook.getSheetByName('ERP Daily Collections v1');
    expect(log?.getRange(2, 2, 2, 1).getDisplayValues()).toEqual([
      ['00000000000000000003'], ['00000000000000000002'],
    ]);
    const formula = loaded.projection.getRange(94, 1).getFormula();
    expect(formula.indexOf('SORT(log,1,TRUE,2,FALSE)')).toBeLessThan(formula.indexOf('SORTN(sorted,9^9,2,1,TRUE)'));
    expect(formula).toContain("REGEXMATCH('ERP Daily Collections v1'!W2:W");
    expect(loaded.projection.getRange('E2').getFormula()).toContain(
      "REGEXMATCH('ERP Daily Collections v1'!W2:W",
    );
  });

  it('appends a blocked revision so it supersedes the prior ready day', () => {
    expect(send(loaded.receiver, event(dailyPayload())).status).toBe('accepted');
    const blocked = dailyPayload({ blockers: ['open_shift'], status: 'blocked' });
    const response = send(loaded.receiver, event(blocked, {
      event_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      source_revision: '00000000000000000002',
    }));
    expect(response.status).toBe('accepted');
    expect(loaded.workbook.getSheetByName('ERP Daily Collections v1')?.getRange(3, 3).getDisplayValue()).toBe('blocked');
    expect(loaded.projection.getRange('E2').getFormula()).toContain('Needs review: ');
  });

  it('removes an unverified partial tail write so the same event can retry', () => {
    loaded.workbook.corruptNextTailWrite = true;
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'write_verification_failed', retryable: true,
    });
    expect(loaded.workbook.getSheetByName('ERP Daily Collections v1')?.getLastRow()).toBe(1);
    expect(send(loaded.receiver, event(dailyPayload())).status).toBe('accepted');
  });

  it('never acknowledges a retained partial row when cleanup also fails', () => {
    loaded.workbook.corruptNextTailWrite = true;
    loaded.workbook.failDelete = true;
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'write_verification_failed', retryable: true,
    });
    expect(loaded.workbook.getSheetByName('ERP Daily Collections v1')?.getLastRow()).toBe(2);
    expect(loaded.workbook.getSheetByName('ERP Daily Collections v1')?.getRange(2, 23).getDisplayValue()).toBe('');
    expect(loaded.projection.getRange(94, 1).getFormula()).toContain(
      "REGEXMATCH('ERP Daily Collections v1'!W2:W",
    );

    loaded.workbook.failDelete = false;
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'event_row_conflict', retryable: false,
    });
    expect(loaded.workbook.getSheetByName('ERP Daily Collections v1')?.getLastRow()).toBe(2);
  });

  it('commits Received At last and safely deduplicates when the acknowledgement is lost', () => {
    loaded.workbook.failNextResponse = true;
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'internal_error', retryable: true,
    });
    const receivedAt = loaded.workbook
      .getSheetByName('ERP Daily Collections v1')
      ?.getRange(2, 23)
      .getDisplayValue();
    expect(receivedAt).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$/);
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: true, status: 'duplicate',
    });
  });

  it('excludes an invalid acceptance marker and refuses to acknowledge that row on retry', () => {
    expect(send(loaded.receiver, event(dailyPayload())).status).toBe('accepted');
    const log = loaded.workbook.getSheetByName('ERP Daily Collections v1');
    log?.getRange(2, 23).setValue('2026-10-11');
    expect(send(loaded.receiver, event(dailyPayload()))).toMatchObject({
      ok: false, error_code: 'event_row_conflict', retryable: false,
    });
    expect(loaded.projection.getRange(94, 1).getFormula()).toContain(
      '^[0-9]{4}-[0-9]{2}-[0-9]{2}T',
    );
  });

  it('rejects a second ready date before a one-row projection can truncate it', () => {
    const capacityOne = loadReceiver({ projectionEndRow: 94 });
    capacityOne.receiver.setupDailyCollectionsProjection();
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-10', '00000000000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    )).status).toBe('accepted');
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-11', '00000000000000000001', 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
    ))).toMatchObject({
      ok: false, error_code: 'projection_capacity_exceeded', retryable: false,
    });
  });

  it('lets a newer blocked revision free capacity and ignores an older ready revision', () => {
    const capacityOne = loadReceiver({ projectionEndRow: 94 });
    capacityOne.receiver.setupDailyCollectionsProjection();
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-10', '00000000000000000001', 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    )).status).toBe('accepted');
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-10', '00000000000000000003', 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      { blockers: ['open_shift'], status: 'blocked' },
    )).status).toBe('accepted');
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-10', '00000000000000000002', 'ffffffff-ffff-4fff-8fff-ffffffffffff',
    )).status).toBe('accepted');
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-11', '00000000000000000001', '11111111-1111-4111-8111-111111111111',
    )).status).toBe('accepted');
    expect(send(capacityOne.receiver, eventForDay(
      '2026-10-12', '00000000000000000001', '22222222-2222-4222-8222-222222222222',
    ))).toMatchObject({ ok: false, error_code: 'projection_capacity_exceeded' });
  });

  it('acknowledges a daily connection test only after exact scope and setup verification', () => {
    const payload = {
      currency: 'INR',
      description: 'D Company ERP mirror connection test',
      export_mode: 'daily_collections',
      required_capability: 'daily_collections.v1',
      status: 'test',
      summary_scope: { branch_id: BRANCH_ID, business_timezone: 'Asia/Kolkata', start_date: '2026-10-01' },
    };
    const response = send(loaded.receiver, event(payload, {
      event_type: 'mirror.connection_test',
      source_type: 'mirror_configuration',
      source_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      source_revision: CONFIGURATION_ID,
    }));
    expect(response).toMatchObject({ ok: true, capability: 'daily_collections.v1', scope: payload.summary_scope });

    const wrongScope = { ...payload, summary_scope: { ...payload.summary_scope, branch_id: 'ffffffff-ffff-4fff-8fff-ffffffffffff' } };
    expect(send(loaded.receiver, event(wrongScope, {
      event_type: 'mirror.connection_test', source_type: 'mirror_configuration',
      source_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', source_revision: CONFIGURATION_ID,
    }))).toMatchObject({ ok: false, error_code: 'daily_scope_mismatch' });

    loaded.projection.getRange(94, 1).setFormula('=1');
    expect(send(loaded.receiver, event(payload, {
      event_type: 'mirror.connection_test', source_type: 'mirror_configuration',
      source_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee', source_revision: CONFIGURATION_ID,
    }))).toMatchObject({ ok: false, error_code: 'projection_not_verified' });
  });

  it('keeps the default legacy connection test on the unchanged 33-column mirror', () => {
    const payload = {
      currency: 'INR',
      description: 'D Company ERP mirror connection test',
      export_mode: 'legacy_transactions',
      status: 'test',
    };
    const response = send(loaded.receiver, event(payload, {
      event_type: 'mirror.connection_test',
      source_type: 'mirror_configuration',
      source_id: 'eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee',
      source_revision: CONFIGURATION_ID,
    }));
    expect(response).toMatchObject({ ok: true, status: 'accepted' });
    expect(response).not.toHaveProperty('capability');
    const mirror = loaded.workbook.getSheetByName('ERP Mirror v1');
    expect(mirror?.getRange(1, 1, 1, 33).getDisplayValues()[0]).toHaveLength(33);
    expect(mirror?.getRange(2, 3).getDisplayValue()).toBe('mirror.connection_test');
  });
});
