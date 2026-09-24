# Code 30.4 PS5 participant billing patch

Migration 0083 records friends who join a fixed-price PS5 session after Start. Forward migration 0084 is the current head and prevents the session's participant revision from decreasing at the database boundary. The patch does not change the booked billing customer, package price, paid extension ledger, or the session's original `extra_controllers` snapshot.

Each Join creates a tenant-scoped presence interval for one live saved customer. Leave closes that interval once. The live count is derived from open intervals under the locked session row; the physical cap is four players including the booked Single or Dual count and any upfront extra controllers. Join requires an active, unbilled, complete-snapshot PS5 package session and open source shift. Leave also works while paused, using the pause-excluding play meter.

The backend contract is:

- `GET /api/v1/gaming/sessions/{session_id}/participants` returns `session_id`, `participant_revision`, derived `active_friend_count`, `current_player_count`, `max_player_count=4`, and interval rows containing IDs, customer IDs, join/leave times, play meters, and revisions.
- `POST /api/v1/gaming/sessions/{session_id}/participants/join` accepts `expected_participant_revision`, either `customer_id` or `customer_name` plus `customer_phone`, directory fence fields, and the optional all-or-none offline trio `occurred_at`, `play_elapsed_ms`, `expected_pause_version`.
- `POST /api/v1/gaming/sessions/{session_id}/participants/{participant_id}/leave` accepts `expected_participant_revision` and the same optional offline trio.
- `POST /api/v1/gaming/sessions/{session_id}/stop` accepts `expected_participant_revision` and the existing optional offline `ended_at`. Once a roster event exists, the revision is mandatory.

Every roster write carries `expected_participant_revision` and a durable idempotency key. Offline Join/Leave additionally carries `occurred_at`, `play_elapsed_ms`, and `expected_pause_version`, bound to the existing offline provenance headers. Captured events must follow the last accepted participant and pause transitions. Audit and replay payloads contain customer IDs but no names or phone numbers.

The pause clock now freezes play time at the recorded pause boundary and preserves that exact meter when Resume or Stop folds the open pause into `paused_duration_ms`. This prevents a one-millisecond flooring drift from rejecting valid historical offline Join/Leave meters or changing a paused friend's settlement.

Stop requires the exact current participant revision after the first roster event. Under the session lock it closes open intervals, sums each customer's pause-excluded milliseconds across all rejoins, rounds that customer once to started hours, and charges ₹30 per started hour with a ₹30 minimum. The immutable settlement header retains the Stop key, request hash, actor, terminal, amount before, surcharge, and amount after; immutable customer lines retain actual playtime and charge. POS remains one Gaming line whose note shows base plus extensions separately from the friend surcharge. Participant playtime appears in customer history and leaderboard totals with zero qualifying paid minutes and zero draft reward.

Android support is blocked until Room 53 or later implements a per-session FIFO outbox for Join, Leave, and Stop. It must wait for each server acknowledgement, persist the returned participant revision, drain all captured roster actions, and only then enqueue Stop with that acknowledged revision. The backend rejects known revision gaps and out-of-order captured times, but it cannot detect a Join or Leave that still exists only on an offline device. This FIFO and post-ack Stop rule is therefore a release requirement.

The backend phase does not enable Join/Leave for hourly, non-PS5, legacy ambiguous, or incomplete-snapshot sessions. Join while paused is rejected until Resume. Web and Android UI work remains separate.

Rollback before business resumes should restore the pre-0083 database snapshot and matching application build. After participant rows or settlements exist, do not run the predecessor application or downgrade below migration 0084: 0083 stores evidence that the predecessor cannot validate or bill, and 0084 protects its monotonic revision. Retire the feature only through a reviewed, audited migration that preserves all interval and settlement history.
