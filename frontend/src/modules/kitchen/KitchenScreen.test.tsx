import { renderToStaticMarkup } from 'react-dom/server';
import { StaticRouter } from 'react-router-dom/server';
import { describe, expect, it } from 'vitest';

import KitchenScreen from './KitchenScreen';

describe('KitchenScreen', () => {
  it('renders an exit control that returns to POS', () => {
    const markup = renderToStaticMarkup(
      <StaticRouter location="/kitchen">
        <KitchenScreen />
      </StaticRouter>,
    );

    expect(markup).toContain('Exit KDS');
    expect(markup).toContain('aria-label="Exit KDS and return to POS"');
    expect(markup).toContain('href="/pos"');
  });
});
