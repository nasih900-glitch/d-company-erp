# Code30.4 app health capture

The Android app already captures fatal Java crashes, Android process exits
(including ANR, native crash, and low-memory reasons where the OS provides
them), API failures, and sync stalls through its existing bounded diagnostic
outbox. Authorized owners review those incidents in Web ERP System Health.

Code30.4 adds an **opt-in, five-minute local performance capture** under
Android Settings → App health. It records only fixed screen names, rendered
frame-duration buckets, missed frame deadlines, dropped metric reports, and
fixed sync-resource names with refresh time/outcome buckets. It records no
customer names, phone numbers, receipts, URLs, credentials, or request bodies.
The capture stops when the app backgrounds, the account changes, the user
stops it, or five minutes elapse. At most three captures remain for 24 hours;
the user can delete them immediately. These performance summaries stay on the
tablet and are not uploaded to the automatic incident outbox.

The summary can distinguish a slow Gaming refresh from slow screen rendering
and gives a specific place to investigate next. It is **not a bug scanner**:
it cannot prove why a frame was slow, detect every unresponsive control, or
verify financial correctness. Those require a reproducible case, logs or a
profiler trace, and transaction/regression tests. A software-rendered emulator
is suitable for checking that capture starts, persists, expires, and deletes;
its frame numbers do not represent the physical tablet. Reproduce any reported
lag on the actual tablet before changing additional performance-sensitive code.

The narrow Code30.4 runtime change fetches Gaming station, package, and
session lists concurrently and caps session add-on fetches at three in flight.
It retains the previous all-or-nothing cache commit. This removes a known
serial request chain during Gaming refresh without changing unrelated module
refreshes or billing semantics.

Release evidence must include Android unit tests, an isolated emulator capture
with reconnect, a signed upgrade that preserves the prior local database and
pending actions, and a physical tablet capture during real operating conditions.
None of those local results alone proves production deployment or tablet
acceptance.
