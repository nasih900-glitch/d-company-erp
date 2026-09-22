\set ON_ERROR_STOP on

-- One repeatable, global snapshot. No company or branch predicate is allowed:
-- a production migration must not hide active work in another tenant. The
-- application writers are checked once while live and again after they stop.
BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;

SELECT
    count(*) FILTER (WHERE status = 'open') AS open_shifts,
    count(*) FILTER (
        WHERE status NOT IN ('open', 'closed', 'reconciled')
    ) AS invalid_shift_statuses
FROM shifts
\gset business_

SELECT
    count(*) FILTER (WHERE status IN ('open', 'held')) AS open_or_held_orders,
    count(*) FILTER (
        WHERE status NOT IN ('open', 'paid', 'void', 'refunded', 'held')
    ) AS invalid_order_statuses
FROM orders
\gset business_

SELECT count(*) AS unacknowledged_kitchen_cancellations
FROM order_lines AS line
JOIN orders AS order_row ON order_row.id = line.order_id
WHERE line.kitchen_released_at IS NOT NULL
  AND line.voided_at IS NOT NULL
  AND line.kitchen_void_acknowledged_at IS NULL
\gset business_

SELECT
    count(*) FILTER (
        WHERE status IN ('active', 'paused')
    ) AS active_or_paused_gaming_sessions,
    count(*) FILTER (
        WHERE status = 'ended' AND order_id IS NULL
    ) AS ended_gaming_awaiting_pos_or_void,
    count(*) FILTER (
        WHERE status NOT IN ('active', 'paused', 'ended', 'cancelled')
    ) AS invalid_gaming_session_statuses
FROM gaming_sessions
\gset business_

SELECT count(*) AS unresolved_pos_refund_requests
FROM pos_refund_requests AS request
WHERE NOT EXISTS (
        SELECT 1 FROM refunds AS refund WHERE refund.request_id = request.id
    )
  AND NOT EXISTS (
        SELECT 1
        FROM pos_refund_withdrawals AS withdrawal
        WHERE withdrawal.refund_request_id = request.id
    )
\gset business_

SELECT count(*) AS unresolved_membership_payment_requests
FROM membership_payment_requests AS request
WHERE NOT EXISTS (
        SELECT 1
        FROM membership_payments AS payment
        WHERE payment.request_id = request.id
    )
  AND NOT EXISTS (
        SELECT 1
        FROM membership_payment_request_resolutions AS resolution
        WHERE resolution.request_id = request.id
    )
\gset business_

SELECT count(*) AS unresolved_membership_refunds
FROM membership_refunds AS refund
WHERE NOT EXISTS (
        SELECT 1
        FROM membership_refund_settlements AS settlement
        WHERE settlement.refund_id = refund.id
    )
  AND NOT EXISTS (
        SELECT 1
        FROM membership_refund_resolutions AS resolution
        WHERE resolution.refund_id = refund.id
    )
\gset business_

SELECT count(*) AS unresolved_membership_refund_recoveries
FROM membership_refund_attempt_recoveries AS recovery
WHERE NOT EXISTS (
    SELECT 1
    FROM membership_refund_attempt_resolutions AS resolution
    WHERE resolution.recovery_id = recovery.id
)
\gset business_

-- The Sheets outbox was introduced at 0075. A verified older release has no
-- such table and therefore no Sheets work; psql's conditional avoids parsing
-- a relation that legitimately does not exist on the supported legacy heads.
SELECT to_regclass('public.google_sheets_deliveries') IS NOT NULL
    AS sheets_delivery_table_present
\gset
\if :sheets_delivery_table_present
SELECT
    count(*) FILTER (
        WHERE status = 'pending'
    ) AS google_sheets_pending_deliveries,
    count(*) FILTER (
        WHERE status = 'leased'
    ) AS google_sheets_leased_deliveries,
    count(*) FILTER (
        WHERE status = 'quarantined'
    ) AS google_sheets_quarantined_deliveries
FROM google_sheets_deliveries
\gset business_
\else
\set business_google_sheets_pending_deliveries 0
\set business_google_sheets_leased_deliveries 0
\set business_google_sheets_quarantined_deliveries 0
\endif

SELECT jsonb_build_object(
    'schema_version', 1,
    'open_shifts', :'business_open_shifts'::bigint,
    'invalid_shift_statuses', :'business_invalid_shift_statuses'::bigint,
    'open_or_held_orders', :'business_open_or_held_orders'::bigint,
    'invalid_order_statuses', :'business_invalid_order_statuses'::bigint,
    'unacknowledged_kitchen_cancellations',
        :'business_unacknowledged_kitchen_cancellations'::bigint,
    'active_or_paused_gaming_sessions',
        :'business_active_or_paused_gaming_sessions'::bigint,
    'ended_gaming_awaiting_pos_or_void',
        :'business_ended_gaming_awaiting_pos_or_void'::bigint,
    'invalid_gaming_session_statuses',
        :'business_invalid_gaming_session_statuses'::bigint,
    'unresolved_pos_refund_requests',
        :'business_unresolved_pos_refund_requests'::bigint,
    'unresolved_membership_payment_requests',
        :'business_unresolved_membership_payment_requests'::bigint,
    'unresolved_membership_refunds',
        :'business_unresolved_membership_refunds'::bigint,
    'unresolved_membership_refund_recoveries',
        :'business_unresolved_membership_refund_recoveries'::bigint,
    'google_sheets_pending_deliveries',
        :'business_google_sheets_pending_deliveries'::bigint,
    'google_sheets_leased_deliveries',
        :'business_google_sheets_leased_deliveries'::bigint,
    'google_sheets_quarantined_deliveries',
        :'business_google_sheets_quarantined_deliveries'::bigint
);

COMMIT;
