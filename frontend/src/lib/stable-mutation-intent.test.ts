import { describe, expect, it } from 'vitest';

import {
  StableMutationIntent,
  type MutationIntentStorage,
} from './stable-mutation-intent';

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

function sequentialKeys(...keys: string[]): (prefix: string) => string {
  let index = 0;
  return (prefix) => `${prefix}:${keys[index++]}`;
}

describe('stable mutation intents', () => {
  it('retries an ambiguous report submission with the same key and byte-equivalent body', () => {
    const controller = new StableMutationIntent<{ occurred_at: string; title: string }>({
      prefix: 'bug-report:web',
      keyFactory: sequentialKeys('first', 'second'),
    });
    const first = controller.resolve('same-report', () => ({
      occurred_at: '2026-08-28T12:00:00.000Z',
      title: 'Payment timed out',
    }));
    const retry = controller.resolve('same-report', () => ({
      occurred_at: '2026-08-28T12:05:00.000Z',
      title: 'This replacement must never be used',
    }));

    expect(retry.idempotencyKey).toBe(first.idempotencyKey);
    expect(JSON.stringify(retry.payload)).toBe(JSON.stringify(first.payload));
    expect(retry.payload.occurred_at).toBe('2026-08-28T12:00:00.000Z');
  });

  it('restores the frozen report operation after reload and clears it only after success', () => {
    const storage = new MemoryStorage();
    const firstPage = new StableMutationIntent<{ title: string }>({
      prefix: 'bug-report:web',
      storage,
      storageKey: 'support:user-1',
      keyFactory: sequentialKeys('before-reload'),
    });
    const first = firstPage.resolve('report-a', () => ({ title: 'Original title' }));

    const reloadedPage = new StableMutationIntent<{ title: string }>({
      prefix: 'bug-report:web',
      storage,
      storageKey: 'support:user-1',
      keyFactory: sequentialKeys('must-not-be-used'),
      isPayload: (value): value is { title: string } => (
        typeof value === 'object'
        && value !== null
        && typeof (value as { title?: unknown }).title === 'string'
      ),
    });
    const retry = reloadedPage.resolve('report-a', () => ({ title: 'Changed by time' }));

    expect(retry).toEqual(first);
    reloadedPage.confirmSuccess();
    expect(storage.getItem('support:user-1')).toBeNull();
  });

  it('rotates for an intentional edit and for the next operation after success', () => {
    const controller = new StableMutationIntent<{ message: string }>({
      prefix: 'bug-report-reply:web',
      keyFactory: sequentialKeys('first', 'edited', 'after-success'),
    });
    const first = controller.resolve('report-1:original', () => ({ message: 'Original' }));
    controller.invalidate();
    const edited = controller.resolve('report-1:edited', () => ({ message: 'Edited' }));
    controller.confirmSuccess();
    const next = controller.resolve('report-1:edited', () => ({ message: 'Edited' }));

    expect(new Set([
      first.idempotencyKey,
      edited.idempotencyKey,
      next.idempotencyKey,
    ]).size).toBe(3);
  });

  it('uses one public-reply key per unchanged report/message and rotates on report switch', () => {
    const controller = new StableMutationIntent<{ reportId: string; message: string }>({
      prefix: 'bug-report-reply:web',
      keyFactory: sequentialKeys('report-one', 'report-two'),
    });
    const first = controller.resolve('report-1\u0000Please retry.', () => ({
      reportId: 'report-1',
      message: 'Please retry.',
    }));
    const retry = controller.resolve('report-1\u0000Please retry.', () => ({
      reportId: 'report-1',
      message: 'A changed builder must not affect a retry.',
    }));
    controller.invalidate();
    const switched = controller.resolve('report-2\u0000Please retry.', () => ({
      reportId: 'report-2',
      message: 'Please retry.',
    }));

    expect(retry).toEqual(first);
    expect(switched.idempotencyKey).not.toBe(first.idempotencyKey);
    expect(switched.payload.reportId).toBe('report-2');
  });

  it('keeps one attachment key for retry and rotates on replacement', () => {
    const controller = new StableMutationIntent<{ reportId: string; filename: string }>({
      prefix: 'bug-report-attachment:web',
      keyFactory: sequentialKeys('screen-one', 'screen-two'),
    });
    const first = controller.resolve('report-1:screen-one', () => ({
      reportId: 'report-1',
      filename: 'screen-one.png',
    }));
    const retry = controller.resolve('report-1:screen-one', () => ({
      reportId: 'wrong-report',
      filename: 'wrong-file.png',
    }));
    controller.invalidate();
    const replacement = controller.resolve('report-1:screen-two', () => ({
      reportId: 'report-1',
      filename: 'screen-two.png',
    }));

    expect(retry).toEqual(first);
    expect(replacement.idempotencyKey).not.toBe(first.idempotencyKey);
  });

  it('does not let a late success clear a newer operation', () => {
    const controller = new StableMutationIntent<{ message: string }>({
      prefix: 'bug-report-reply:web',
      keyFactory: sequentialKeys('old', 'new'),
    });
    const oldIntent = controller.resolve('old', () => ({ message: 'Old reply' }));
    controller.invalidate();
    const newIntent = controller.resolve('new', () => ({ message: 'New reply' }));

    controller.confirmSuccess(oldIntent);

    expect(controller.peek()).toEqual(newIntent);
  });
});
