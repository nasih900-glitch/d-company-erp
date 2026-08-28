# Support reporting security and operations

The Help launcher is available to every authenticated ERP user. Reporters can
only list their own requests, see lifecycle status, and read replies that an
authorised system owner deliberately posts as public. The existing internal
resolution note remains owner-only and is never included in reporter DTOs.

## Screenshot privacy

Screenshots are optional and must be explicitly selected by the reporter. The
web client shows a local object-URL preview and asks the reporter to hide
passwords, customer details, and payment information. It does not capture the
screen automatically.

The API accepts static PNG, JPEG, or WebP files only. It fully decodes each
image with bounded dimensions and pixel count, validates the claimed type,
normalises EXIF orientation, strips metadata, and canonically re-encodes the
pixels within the 2 MiB storage cap. At most three screenshots are permitted
per report, with retained screenshot bytes capped at 100 MiB per company.
Downloads require authentication plus either report ownership or the protected
`admin.system` permission. Responses are private, non-cacheable, and never
expose a public object URL.

Screenshot bytes currently live in PostgreSQL so the initial low-volume
implementation is transactional and needs no separate public storage service.
Metadata is retained as immutable support evidence; private bytes expire after
90 days. Install `infra/cron/dcompany-erp-alerts` so the nightly job runs:

```bash
python -m app.workers.support_attachments --batch-size 100 --max-rows 1000
```

For materially higher support volume, move encrypted payloads to a private
object store with short-lived authenticated downloads, additional malware
scanning, lifecycle deletion, and storage alerts. Keep the existing image
decoding, database metadata, tenant checks, per-report limit, per-company quota,
and retention-worker semantics. Do not switch to permanent or guessable public
URLs.

## Operational boundaries

- Web drafts are stored locally until the server confirms report creation. An
  offline draft is not an automatically queued financial operation; the user
  reconnects and taps **Send** again.
- The latest failed web request context is memory-only and contains only the
  HTTP method, a normalized path with identifiers replaced by `:id`, and a
  sanitized error code. Query parameters, request/response bodies, headers,
  credentials, and tokens are never retained.
- Support change notifications use the existing company-scoped realtime
  signal. The current realtime registry is process-local because production is
  configured for one backend process. A multi-worker deployment must first
  move broadcasts to Redis (or another shared broker).
- Public replies and screenshot metadata are immutable. Never use the public
  reply field for private staff notes; use `internal_resolution_note`.
