# Code 24 backend and finance audit

This records local source and PostgreSQL evidence, not production deployment or
physical-device acceptance. Production business data was not changed by this
audit. Code 23 remains an immutable earlier release; these fixes belong to the
Code 24 candidate.

## Reproduced defects and corrections

### Valid tip payout rejected by the database

At migration 0066, a valid tip payout raised PostgreSQL `UndefinedColumn`:
`record "new" has no field "source_kind"`. The shared finance-source trigger
referenced a manual-collection-only field in a boolean expression. PostgreSQL
resolved that field against the tip-payout record before the expression could
exclude the other table.

Additive migration 0067 dispatches table-specific validation through separate
PL/pgSQL branches. Tenant, actor, positive amount, settlement rail, source
identity, and immutability protections remain. Regression tests prove valid
tip, capital, and manual-collection inserts, rejection of a foreign-tenant actor,
and rejection of deletion. Downgrading to 0066 retains the compatible corrected
function and preserves source rows; it deliberately does not restore the defect.

### Concurrent payouts could spend the same outstanding tips twice

After correcting the trigger, a controlled concurrent HTTP reproduction let two
distinct requests each pay out 1,000 minor units against one 1,000-minor-unit
Tips Payable balance. Both originally returned 201, saving 2,000 minor units.

Tip payouts now acquire a company-wide `FOR NO KEY UPDATE` row lock before
reading the ledger. This serializes all branches sharing that balance without
blocking the ordinary foreign-key key-share locks used by new sales. Refunds of
orders containing tips acquire the same lock before reducing the liability.
The regression now observes one 201, one explanatory 422, and exactly 1,000
minor units paid out. A later refund can legitimately make the payable balance
negative if tips were already physically distributed; the refund must not be
discarded or denied merely to conceal that liability.

Payout timestamps must include a timezone and cannot be future-dated. Otherwise
a future payout would be excluded from the current ledger and allow the same
current balance to be spent again. Rejections explain how to correct the entry;
timestamps are not silently rewritten. Timezone-aware payloads still use the
normal idempotent replay before timestamp/balance revalidation.

### Financial reports combined different committed database states

A sale committed between report queries produced the impossible combination of
zero orders, 1,000 minor units of revenue, 1,000 minor units of cash payments,
and a zero average ticket.

GET endpoints in the audited reports, finance, accounting, analytics, and
insights modules now begin a read-only repeatable-read transaction before their
first authentication/data query. All queries use one normal pooled connection
and one committed snapshot. Mutating endpoints and operational POS reads retain
their existing isolation/locking behavior. A fresh request obtains fresh data;
this is not a cache or a last-write-wins change.

The actual HTTP regression commits a sale between the report's order-count and
revenue queries. The first response remains consistently zero; the next response
contains one order and exactly 1,000 minor units for revenue, cash, and average
ticket. Compiled-route tests verify all five reporting modules and verify that
POS and mutating routes do not accidentally enter read-only transactions.
Scheduled P&L emails begin the same snapshot before their first query.

## Gaming, permissions, and release-fixture safeguards

The printed 17-package tariff catalogue was preserved. The golden Gaming/POS
HTTP suite covers package and extension amounts, controller additions, discounts,
cash and UPI, idempotent payment replay, branch isolation, concurrent starts,
legacy hourly compatibility, and a different co-owner completing another
employee's shift without Audit Log access.

That suite previously required a dated, exact local database name and was
skipped on other safe test databases. It now also permits the deterministic
`dcompany_audit_test` name and the exact `erp_test` PostgreSQL service database
used by both GitHub CI workflows; the explicit allowlist remains in place. An
unexpected database in GitHub Actions now fails the gate visibly rather than
silently skipping it. One old
assertion expected pre-Code-23 shift messages/response shapes. It now checks the
structured blocker issue, opener identity, actionable guidance, and exact
authenticated closer attribution instead of obsolete wording.

Full-suite database setup must be fresh. The release-registry tests intentionally
commit fixed, globally unique immutable release fixtures. Reusing a completed
test database produces duplicate fixture errors; those are not a reason to
weaken production uniqueness or immutability constraints. Earlier failing logs
were retained and a newly created, migrated database was used for final proof.

## Verification boundary

The counts and migration revision in this section are the dated 4 September
backend checkpoint, not the final frozen Code 24 gate. Later migrations and
cross-client recovery work are recorded in
[`../../docs/CODE24_PRODUCTION_AUDIT.md`](../../docs/CODE24_PRODUCTION_AUDIT.md)
and must be validated together on the final source before push or deployment.

The browser/Android agents completed a shared synthetic-tenant shift against the
local HTTP API. Independent PostgreSQL reconciliation found three paid orders
totaling 37,700 minor units: 20,000 cash plus 17,700 UPI. Opening float was 50,000;
expected and counted cash were both 70,000, with zero variance. The authenticated
closer differed from the opener. There were no running/paused sessions, no ended
sessions awaiting POS handoff, and no open/held orders. Two ended gaming sessions
remained correctly linked to paid history rather than being deleted.

Focused tests passed for the trigger migration, parallel payouts, payout dates,
actual HTTP report snapshot, refund/tip reconciliation, and the cross-user
Gaming/POS/close-shift flow. The application-source full unit/integration run passed **1,314
tests with zero failures and zero skips in 447.23 seconds** against the newly
created `dcompany_audit_test` database migrated to 0067. Command, from `backend/`:

```sh
ENV=test \
DATABASE_URL="$AUDIT_TEST_DATABASE_URL" \
REDIS_URL=redis://127.0.0.1:6379/14 \
/private/tmp/dcompany-backend-venv2/bin/python -m pytest \
  tests/unit tests/integration -q --no-cov
```

`AUDIT_TEST_DATABASE_URL` was the local PostgreSQL DSN for the newly created
`dcompany_audit_test` database; it must never point to production.

The retained local log is
`/tmp/dcompany-code24-backend-final-clean-20260904.log`. Fatal Python lint checks
and `git diff --check` also passed. After coordinated completion of the browser
and Android flows, the local HTTP API was restarted on final Code 24 source;
startup reported 3.1.13 and `/healthz` returned `{"status":"ok"}`.
An authenticated TCP HTTP report after that restart returned 200, three orders,
37,700 minor units of revenue, and matching 20,000 cash plus 17,700 UPI.

### Test-only CI gate follow-up, 2026-09-05

Review found that both `ci.yml` and `release.yml` provision `erp_test`, but the
golden Gaming/POS allowlist did not include that exact name. The 18 golden cases
would therefore have skipped in CI despite passing locally. The guard now
supports that specific disposable service database, still rejects arbitrary
names (including similar `_test` names), and turns any unexpected CI database
into a visible test failure. New unit tests check both workflow service names
and matching test DSNs against the guard.

After this test-only correction, the **13 new guard/workflow tests plus all 18
golden Gaming/POS HTTP tests passed: 31 passed, zero skipped, in 8.94 seconds**.
Focused command was `pytest tests/unit/test_gaming_tariff_ci_guard.py
tests/integration/test_gaming_tariff_pos_e2e.py -q --no-cov` on the isolated audit
database. Evidence: `/tmp/dcompany-code24-ci-gaming-guard-20260905.log`.
No production code changed, and the full immutable-release-fixture suite was
not rerun against its already-used database. The preceding 1,314-test full result
and this additional focused result are reported separately.

Three dependency deprecation warnings were observed
for Python `crypt`, Passlib/Argon2 version access, and Starlette/httpx integration;
they are not silent test failures and should be addressed in a separately tested
dependency update.

Migration 0067 was applied only to disposable/local audit databases. The shared
local HTTP sandbox used synthetic users and a single Hybrid workspace. No
production migration, tariff mutation, financial cleanup, commit, tag, or release
publication was performed by this backend audit.
