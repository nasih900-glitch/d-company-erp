import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';

import { NotificationProvider } from '@/components/ui/Notifications';
import MenuScreen, { CategoryManagerModal, menuManagementErrorMessage } from './MenuScreen';

describe('MenuScreen operational feedback', () => {
  it('explains how to recover when protected pricing is locked', () => {
    expect(menuManagementErrorMessage(new Error('pricing password unlock required'))).toBe(
      'Pricing is locked. Open Settings, choose Pricing, re-enter your password, then retry this same change.',
    );
  });

  it('labels the icon-only product refresh action', () => {
    const markup = renderToStaticMarkup(
      <NotificationProvider>
        <MenuScreen />
      </NotificationProvider>,
    );

    expect(markup).toContain('aria-label="Refresh products"');
    expect(markup).toContain('title="Refresh products"');
  });

  it('labels category edit and delete actions with the affected category', () => {
    const markup = renderToStaticMarkup(
      <NotificationProvider>
        <CategoryManagerModal
          cats={[{
            id: 'category-1',
            name: 'Drinks & Snacks',
            sort_order: 1,
            is_gaming_centre_catalog: true,
          }]}
          onClose={vi.fn()}
          onChanged={vi.fn()}
        />
      </NotificationProvider>,
    );

    expect(markup).toContain('aria-label="Edit category Drinks &amp; Snacks"');
    expect(markup).toContain('aria-label="Delete category Drinks &amp; Snacks"');
  });
});
