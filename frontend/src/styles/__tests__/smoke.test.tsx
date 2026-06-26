import { describe, it, expect } from 'vitest';

describe('frontend smoke', () => {
  it('does basic arithmetic (vitest is wired)', () => {
    expect(1 + 1).toBe(2);
  });
});
