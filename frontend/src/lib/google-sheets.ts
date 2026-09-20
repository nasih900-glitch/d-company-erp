/**
 * Inert compatibility marker for the retired browser Google Sheets sink.
 *
 * Delivery is performed by the backend's durable, authenticated outbox. This
 * module deliberately exposes no URL storage, network request, or send API.
 */
export const LEGACY_BROWSER_GOOGLE_SHEETS_SINK_ENABLED = false as const;
