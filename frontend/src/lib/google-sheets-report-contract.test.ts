import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';

import { googleSheetsTestMessageTone } from '@/modules/settings/tabs/SheetsTab';

const scripts = [
  resolve(process.cwd(), 'src/modules/settings/apps-script.txt'),
  resolve(process.cwd(), '../integrations/google-sheets/Code.gs'),
];

describe('Google Sheets ERP Mirror v1 sink contract', () => {
  it('ships the identical script in Settings and integrations', () => {
    expect(readFileSync(scripts[0], 'utf8')).toBe(readFileSync(scripts[1], 'utf8'));
  });

  for (const path of scripts) {
    it(`uses the fixed append-only schema in ${path}`, () => {
      const source = readFileSync(path, 'utf8');
      const result = runInNewContext(
        `${source}\n({ headers: MIRROR_HEADERS, row: projectMirrorRow(event, payload), dangerous: safeText(' \\t=IMPORTXML("x")') })`,
        {
          event: {
            configuration_id: 'dddddddd-dddd-4ddd-8ddd-dddddddddddd',
            occurred_at: '2026-09-19T12:30:00Z',
            event_type: 'finance.report.closed',
            event_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
            event_key: 'erp-mirror:v1:key',
            company_id: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
            source_type: 'finance_report',
            source_id: '=malicious-source',
            source_revision: '1',
            schema_version: 1,
            payload_sha256: 'c'.repeat(64),
            payload_json: '{"currency":"GBP","gross_revenue_minor":12345}',
          },
          payload: {
            branch: '=North',
            reference: 'INV-1001',
            description: 'Counter sale',
            customer: '+Customer',
            quantity: '1.500',
            amount_minor: 12345,
            payment_method: 'card',
            payment_breakdown_minor: {
              cash: 345,
              card: 12000,
              upi: 0,
              qr: 0,
              wallet: 0,
            },
            actor: '@cashier',
            status: 'paid',
            period_start: '2026-09-19',
            period_end: '2026-09-19',
            currency: 'GBP',
            gross_revenue_minor: 12345,
            net_revenue_minor: 11000,
            expense_total_minor: 2000,
            net_profit_minor: 9000,
          },
        },
      ) as { headers: string[]; row: unknown[]; dangerous: string };

      expect(result.headers).toHaveLength(33);
      expect(result.headers[3]).toBe('Event ID');
      expect(result.headers[13]).toBe('Cash Minor');
      expect(result.headers[17]).toBe('Wallet Minor');
      expect(result.headers[24]).toBe('Payload SHA256');
      expect(result.row).toHaveLength(result.headers.length);
      expect(result.row[6]).toBe("'=North");
      expect(result.row[7]).toBe('INV-1001');
      expect(result.row[9]).toBe("'+Customer");
      expect(result.row[10]).toBe('1.500');
      expect(result.row[11]).toBe(12345);
      expect(result.row.slice(13, 18)).toEqual([345, 12000, 0, 0, 0]);
      expect(result.row[18]).toBe("'@cashier");
      expect(result.row[21]).toBe("'=malicious-source");
      expect(result.row[28]).toBe(12345);
      expect(result.dangerous).toBe("' \t=IMPORTXML(\"x\")");
      expect(source).toContain('LockService.getScriptLock()');
      expect(source).toContain('D_COMPANY_ERP_MIRROR_HMAC_SECRET');
      expect(source).toContain('"configuration_id", event.configuration_id, 36');
      expect(source).toContain('sheet.appendRow(projectMirrorRow(event, payload))');
      expect(source).not.toMatch(/deleteRow|deleteRows|clearContents|clear\(/);
      expect(source).not.toContain('sheet.getRange(existing, 1');
    });

    it(`rejects a payment breakdown that could misstate revenue in ${path}`, () => {
      const source = readFileSync(path, 'utf8');
      expect(() =>
        runInNewContext(`${source}\noptionalPaymentBreakdown(payload)`, {
          payload: {
            amount_minor: 1000,
            payment_breakdown_minor: {
              cash: 500,
              card: 400,
              upi: 0,
              qr: 0,
              wallet: 0,
            },
          },
        }),
      ).toThrow('payment_breakdown_minor must equal amount_minor');
    });
  }

  it('does not expose an unsigned browser delivery path', () => {
    const pos = readFileSync(
      resolve(process.cwd(), 'src/modules/pos/POSScreen.tsx'),
      'utf8',
    );
    const reports = readFileSync(
      resolve(process.cwd(), 'src/modules/reports/ReportsScreen.tsx'),
      'utf8',
    );

    expect(pos).not.toContain('pushToSheet');
    expect(reports).not.toContain('pushToSheet');
    expect(reports).not.toContain('Push to Sheets');

    const legacySinkPath = resolve(process.cwd(), 'src/lib/google-sheets.ts');
    expect(existsSync(legacySinkPath)).toBe(true);
    const legacySink = readFileSync(legacySinkPath, 'utf8');
    expect(legacySink).toContain(
      'LEGACY_BROWSER_GOOGLE_SHEETS_SINK_ENABLED = false',
    );
    expect(legacySink).not.toContain('pushToSheet');
    expect(legacySink).not.toContain('testConnection');
    expect(legacySink).not.toContain('localStorage');
    expect(legacySink).not.toMatch(/\bfetch\s*\(/);
    expect(legacySink).not.toContain('script.google.com');
  });

  it('does not describe an unverified configuration as connected', () => {
    const settingsTab = readFileSync(
      resolve(process.cwd(), 'src/modules/settings/tabs/SheetsTab.tsx'),
      'utf8',
    );

    expect(settingsTab).toContain('status.connection_verified');
    expect(settingsTab).toContain('Configured · verification pending');
    expect(settingsTab).toContain('Google Sheets transaction mirror');
    expect(settingsTab).toContain('Delivered (all time)');
    expect(settingsTab).toContain('not a database backup or receipt-file backup');
    expect(settingsTab).toContain('queued safely while verification is pending');
    expect(settingsTab).toContain('History from before configuration is not backfilled');
    expect(settingsTab).toContain('Held for verification');
    expect(settingsTab).toContain('safely queued');
    expect(settingsTab).toContain('Rotate the Google Sheets secret?');
    expect(settingsTab).toContain('Queued entries are preserved');
    expect(settingsTab).toContain('Receipt photos and PDFs remain');
    expect(settingsTab).not.toContain('Google Sheets backup mirror');
    expect(settingsTab).not.toContain('Connected. Copy the one-time secret');
  });

  it('renders a queued connection test as pending rather than delivered success', () => {
    expect(googleSheetsTestMessageTone('pending')).toBe('warning');
    expect(googleSheetsTestMessageTone('disabled')).toBe('error');
    expect(googleSheetsTestMessageTone('delivered')).toBe('success');
  });

  it('keeps the archived iOS target read-only against the server mirror', () => {
    const iosSource = readFileSync(
      resolve(process.cwd(), 'ios/App/App/DCompanyNativeApp.swift'),
      'utf8',
    );
    const iosProject = readFileSync(
      resolve(process.cwd(), 'ios/App/App.xcodeproj/project.pbxproj'),
      'utf8',
    );

    expect(iosProject).toContain('DCompanyNativeApp.swift in Sources');
    expect(iosSource).toContain(
      'APIClient.shared.get("settings/google-sheets", token: token)',
    );
    expect(iosSource).toContain('This app never stores or posts to an Apps Script URL.');
    expect(iosSource).not.toContain('GSheetsPusher');
    expect(iosSource).not.toContain('GSheetsStore');
    expect(iosSource).not.toContain('Push to Sheets');
    expect(iosSource).not.toContain('script.google.com');
    expect(iosSource).not.toContain('dcompany.erp.gsheets_webhook_url');
  });
});
