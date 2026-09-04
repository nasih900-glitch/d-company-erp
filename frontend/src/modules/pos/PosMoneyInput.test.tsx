import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import { POS_MONEY_INPUT_LABELS, PosMoneyInput } from './PosMoneyInput';

describe('PosMoneyInput', () => {
  it.each(Object.entries(POS_MONEY_INPUT_LABELS))(
    'gives the %s spinbutton a precise accessible name',
    (purpose, accessibleName) => {
      const markup = renderToStaticMarkup(
        <PosMoneyInput
          purpose={purpose as keyof typeof POS_MONEY_INPUT_LABELS}
          value=""
          onChange={() => undefined}
        />,
      );

      expect(markup).toContain('type="number"');
      expect(markup).toContain(`aria-label="${accessibleName}"`);
    },
  );
});
