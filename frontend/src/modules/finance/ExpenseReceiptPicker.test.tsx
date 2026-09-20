import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import {
  ExpenseReceiptContentPreview,
  localDateTimeInputValue,
  ReceiptFilePicker,
} from './FinanceScreen';

describe('expense receipt picker', () => {
  it('offers a rear-camera capture and a separate image/PDF file picker', () => {
    const markup = renderToStaticMarkup(
      <ReceiptFilePicker
        selectedFile={null}
        disabled={false}
        onSelect={() => undefined}
        onClear={() => undefined}
      />,
    );

    expect(markup).toContain('Take photo');
    expect(markup).toContain('Choose receipt');
    expect(markup).toContain('capture="environment"');
    expect(markup).toContain('application/pdf');
    expect(markup).toContain('image/jpeg,image/png,image/webp');
    expect(markup).not.toContain('accept="image/*"');
    expect(markup).not.toContain('heic');
    expect(markup).not.toContain('heif');
  });

  it('sandboxes PDF receipt previews without sending a referrer', () => {
    const markup = renderToStaticMarkup(
      <ExpenseReceiptContentPreview preview={{
        url: 'blob:https://erp.test/receipt',
        filename: 'bill.pdf',
        contentType: 'application/pdf',
      }}/>,
    );

    expect(markup).toContain('<iframe');
    expect(markup).toContain('sandbox=""');
    expect(markup).toContain('referrerPolicy="no-referrer"');
  });

  it('formats the payment time for a local datetime input without shifting it twice', () => {
    const value = new Date('2026-09-19T16:30:00.000Z');
    vi.spyOn(value, 'getTimezoneOffset').mockReturnValue(-60);

    expect(localDateTimeInputValue(value)).toBe('2026-09-19T17:30');
  });
});
