import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';

import {
  appendBoundedNotification,
  MAX_NOTIFICATIONS,
  NotificationViewport,
} from './Notifications';

describe('notification queue', () => {
  it('keeps the newest notifications and never grows without bound', () => {
    const notifications = Array.from({ length: MAX_NOTIFICATIONS + 2 }, (_, index) => ({
      id: String(index),
      message: `message ${index}`,
      type: 'info' as const,
      critical: false,
    }));

    const result = notifications.reduce(appendBoundedNotification, []);

    expect(result).toHaveLength(MAX_NOTIFICATIONS);
    expect(result.map((item) => item.id)).toEqual(['2', '3', '4', '5', '6']);
  });

  it('renders polite status updates and persistent critical errors accessibly', () => {
    const markup = renderToStaticMarkup(createElement(NotificationViewport, {
      notifications: [
        { id: 'success', message: 'Saved', type: 'success', critical: false },
        { id: 'error', message: 'Payment failed', type: 'error', critical: true },
      ],
      onDismiss: () => {},
    }));

    expect(markup).toContain('role="status"');
    expect(markup).toContain('aria-live="polite"');
    expect(markup).toContain('role="alert"');
    expect(markup).toContain('aria-live="assertive"');
    expect(markup).toContain('aria-label="Dismiss Done notification"');
    expect(markup).toContain('aria-label="Dismiss Could not complete that notification"');
  });
});
