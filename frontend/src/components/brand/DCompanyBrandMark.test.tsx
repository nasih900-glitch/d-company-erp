import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import DCompanyBrandMark, { DCOMPANY_APP_MARK_PATH } from './DCompanyBrandMark';

describe('D Company shared brand mark', () => {
  it('uses the approved native artwork inside the safe circular crop', () => {
    const markup = renderToStaticMarkup(<DCompanyBrandMark/>);
    expect(DCOMPANY_APP_MARK_PATH).toBe('/brand/dcompany-app-mark.png');
    expect(markup).toContain('rounded-full');
    expect(markup).toContain('scale-150');
    expect(markup).toContain('src="/brand/dcompany-app-mark.png"');
  });
});
