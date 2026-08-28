import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import {
  StableMutationIntent,
  type MutationIntentStorage,
} from '@/lib/stable-mutation-intent';
import {
  PendingScreenshotRetryNotice,
  SupportScreenshotPreview,
  supportCreateIntentStorageKey,
  validateSupportScreenshot,
} from './SupportLauncher';

function fakeFile(type: string, size: number): File {
  return { type, size } as File;
}

class MemoryStorage implements MutationIntentStorage {
  private readonly values = new Map<string, string>();

  getItem(key: string): string | null {
    return this.values.get(key) ?? null;
  }

  setItem(key: string, value: string): void {
    this.values.set(key, value);
  }

  removeItem(key: string): void {
    this.values.delete(key);
  }
}

describe('support screenshot validation', () => {
  it('isolates persisted report intents by company, branch, and user', () => {
    const branchA = supportCreateIntentStorageKey({
      companyId: 'company-1',
      branchId: 'branch-a',
      userId: 'user-1',
    });
    const branchB = supportCreateIntentStorageKey({
      companyId: 'company-1',
      branchId: 'branch-b',
      userId: 'user-1',
    });

    expect(branchA).not.toBe(branchB);
    expect(branchA).toContain(':company-1:branch-a:user-1');
    expect(branchA).not.toBe(
      supportCreateIntentStorageKey({
        companyId: 'company-1',
        branchId: 'branch-a',
        userId: 'user-2',
      }),
    );

    const storage = new MemoryStorage();
    const firstBranchIntent = new StableMutationIntent<{ branchId: string }>({
      prefix: 'bug-report:web',
      storage,
      storageKey: branchA,
      keyFactory: () => 'bug-report:web:branch-a-operation',
    });
    const firstOperation = firstBranchIntent.resolve('unchanged-draft', () => ({
      branchId: 'branch-a',
    }));
    const switchedBranchIntent = new StableMutationIntent<{ branchId: string }>({
      prefix: 'bug-report:web',
      storage,
      storageKey: branchB,
      keyFactory: () => 'bug-report:web:branch-b-operation',
    });

    expect(switchedBranchIntent.peek()).toBeNull();
    const switchedOperation = switchedBranchIntent.resolve('unchanged-draft', () => ({
      branchId: 'branch-b',
    }));
    expect(switchedOperation.idempotencyKey).not.toBe(firstOperation.idempotencyKey);
    expect(switchedOperation.payload.branchId).toBe('branch-b');
  });

  it('accepts only bounded raster screenshots selected by the user', () => {
    expect(validateSupportScreenshot(null)).toBeNull();
    expect(validateSupportScreenshot(fakeFile('image/png', 1_024))).toBeNull();
    expect(validateSupportScreenshot(fakeFile('image/jpeg', 2 * 1024 * 1024))).toBeNull();
    expect(validateSupportScreenshot(fakeFile('image/svg+xml', 1_024))).toContain('PNG');
    expect(validateSupportScreenshot(fakeFile('image/png', 2 * 1024 * 1024 + 1))).toContain('2 MB');
    expect(validateSupportScreenshot(fakeFile('image/webp', 0))).toContain('empty');
  });

  it('shows the explicitly selected image with a clear remove action', () => {
    const file = { name: 'payment-screen.png', type: 'image/png', size: 12_345 } as File;
    const markup = renderToStaticMarkup(
      createElement(SupportScreenshotPreview, {
        file,
        previewUrl: 'blob:https://erp.local/private-preview',
        onRemove: () => undefined,
      }),
    );

    expect(markup).toContain('src="blob:https://erp.local/private-preview"');
    expect(markup).toContain('Screenshot selected for this support request');
    expect(markup).toContain('payment-screen.png');
    expect(markup).toContain('Remove');
    expect(markup).not.toContain('data:image');
  });

  it('keeps an unconfirmed screenshot visibly retryable without resending the report', () => {
    const markup = renderToStaticMarkup(
      createElement(PendingScreenshotRetryNotice, {
        error: 'The response was interrupted.',
        uploading: false,
        invalid: false,
        onRetry: () => undefined,
        onRemove: () => undefined,
      }),
    );

    expect(markup).toContain('The report is sent');
    expect(markup).toContain('The response was interrupted.');
    expect(markup).toContain('Retry screenshot');
    expect(markup).toContain('Remove screenshot');
    expect(markup).toContain('role="alert"');
  });
});
