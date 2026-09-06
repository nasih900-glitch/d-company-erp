import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';

import { NotificationProvider } from '@/components/ui/Notifications';
import KitchenScreen from './KitchenScreen';

describe('KitchenScreen', () => {
  it('renders an exit control that returns to POS', () => {
    const markup = renderToStaticMarkup(
      <StaticRouter location="/kitchen">
        <NotificationProvider>
          <KitchenScreen />
        </NotificationProvider>
      </StaticRouter>,
    );

    expect(markup).toContain('Exit KDS');
    expect(markup).toContain('aria-label="Exit KDS and return to POS"');
    expect(markup).toContain('href="/pos"');
  });
});
