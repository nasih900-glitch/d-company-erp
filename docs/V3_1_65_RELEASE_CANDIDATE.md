# Build 72 / 3.1.65 candidate

Build 72 extends the released Build 71 source. It targets Android Room 58 and
backend Alembic 0105.

- Staff can pin POS favourites on a shared tablet and hand over the active till
  without closing the shift.
- Staff can report a mistake on a finalized receipt. Confirmed submissions and
  retries keep their original receipt identity across app restarts.
- The protected Owner reviews the exact receipt using the existing correction
  workflow. Reporting a mistake does not make a payment or change stock.
- Schema 0104 preserves UTC receipt snapshot comparisons for stock restoration
  checks. Schema 0105 adds an append-only receipt mistake report table.

The source is a release candidate. Protected signing, signed upgrade tests,
production upgrade admission, and live tablet readiness are required before
an update is offered.

[Build 71 candidate history](V3_1_64_RELEASE_CANDIDATE.md)
