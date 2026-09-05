import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { runInNewContext } from 'node:vm';
import { describe, expect, it } from 'vitest';
import type { ReportSinkKind } from './google-sheets';

const scripts = [
  resolve(process.cwd(), 'src/modules/settings/apps-script.txt'),
  resolve(process.cwd(), '../integrations/google-sheets/Code.gs'),
];
const periods: ReportSinkKind[] = ['daily_report', 'weekly_report', 'monthly_report', 'quarterly_report', 'half_yearly_report', 'yearly_report'];

describe('Google Sheets report adapter contract', () => {
  it('ships the identical script in Settings and integrations', () => {
    expect(readFileSync(scripts[0], 'utf8')).toBe(readFileSync(scripts[1], 'utf8'));
  });
  for (const kind of periods) {
    it(`projects ${kind} without dropping the source report values`, () => {
      for (const path of scripts) {
        const source = readFileSync(path, 'utf8');
        const row = runInNewContext(`${source}\nprojectRow(kind, payload)`, {
          kind,
          payload: { date: '2026-09-05', period_id: 'verified-period', orders_count: 3,
            net_revenue_minor: 37700, gross_revenue_minor: 37700, net_profit_minor: 28700,
            expense_total_minor: 0, cgst_minor: 0, sgst_minor: 0 },
        }) as unknown[];
        expect(row[0]).toBe('2026-09-05');
        expect(row[2]).toMatch(/Report$/);
        expect(row[3]).toBe('verified-period');
        expect(row[6]).toBe(3);
        expect(Number(row[7])).toBe(377);
        expect(Number(row[12])).toBe(377);
      }
    });
  }
});
