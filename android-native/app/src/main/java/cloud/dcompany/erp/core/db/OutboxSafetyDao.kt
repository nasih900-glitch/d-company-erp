package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Query

/**
 * One row per unresolved outbox state, used by the account-safety gate.
 *
 * This intentionally enumerates every writable `local_*` header table in a
 * single read-only query. Child rows (`local_order_lines` and
 * `local_grn_lines`) are owned by their header and must not be counted twice.
 * Read caches are not outboxes.
 *
 * This is a single-user-at-a-time safety net, not row-level attribution. It
 * prevents an already-captured write from being replayed with a different
 * user's bearer token, but it does not make one shared Room database a safe
 * multi-user queue. True multi-user offline support still requires owner
 * columns on every outbox row and owner-filtered pushes.
 */
data class UnresolvedOutboxGroup(
    val resource: String,
    val state: String,
    val count: Int,
)

@Dao
interface OutboxSafetyDao {

    @Query(
        """
        SELECT resource, state, COUNT(*) AS count
        FROM (
            SELECT 'pos_orders' AS resource, syncState AS state
              FROM local_orders WHERE syncState != 'synced'
            UNION ALL
            SELECT 'shifts', state
              FROM local_shifts
             WHERE state IN ('open_pending', 'close_pending', 'open_rejected', 'close_rejected')
            UNION ALL
            SELECT 'gaming_sessions', state
              FROM local_gaming_sessions
             WHERE state IN (
                 'start_pending', 'stop_pending', 'ended_unbilled', 'send_pending',
                 'start_rejected', 'stop_rejected', 'send_rejected',
                 -- Quarantine a restored legacy row before sign-in/sync gets
                 -- a chance to run the idempotent v17 recovery classifier.
                 'rejected'
             )
            UNION ALL
            SELECT 'gaming_package_extensions', state
              FROM local_gaming_package_extensions
             WHERE state NOT IN ('confirmed', 'discarded')
            UNION ALL
            SELECT 'kitchen_advances', state
              FROM local_kitchen_advances WHERE state IN ('pending', 'rejected')
            UNION ALL
            SELECT 'table_orders', state
              FROM local_table_orders WHERE state IN ('pending', 'rejected')
            UNION ALL
            SELECT 'cafe_actions', state
              FROM local_cafe_actions WHERE state IN ('pending', 'conflict', 'rejected')
            UNION ALL
            SELECT 'kitchen_cancellation_acks', state
              FROM local_kitchen_cancellation_acks WHERE state IN ('pending', 'rejected')
            UNION ALL
            SELECT 'refunds', state
              FROM local_refunds
             WHERE state IN (
                 'request_pending', 'request_rejected', 'accepted_cash_due',
                 'cash_handoff_in_progress', 'cash_settle_pending', 'cash_settle_rejected',
                 'withdrawal_pending', 'withdrawal_rejected',
                 'legacy_reconciliation_required'
             )
            UNION ALL
            SELECT 'customers', state
              FROM local_customers WHERE state != 'synced'
            UNION ALL
            SELECT 'menu_categories', state
              FROM local_menu_categories WHERE state != 'synced'
            UNION ALL
            SELECT 'menu_items', state
              FROM local_menu_items WHERE state != 'synced'
            UNION ALL
            SELECT 'staff', state
              FROM local_staff WHERE state != 'synced'
            UNION ALL
            SELECT 'ingredients', state
              FROM local_ingredients WHERE state != 'synced'
            UNION ALL
            SELECT 'suppliers', state
              FROM local_suppliers WHERE state != 'synced'
            UNION ALL
            SELECT 'grns', syncState
              FROM local_grns WHERE syncState != 'synced'
            UNION ALL
            SELECT 'adjustments', syncState
              FROM local_adjustments WHERE syncState != 'synced'
            UNION ALL
            SELECT 'expenses', syncState
              FROM local_expenses WHERE syncState != 'synced'
            UNION ALL
            SELECT 'assets', syncState
              FROM local_assets WHERE syncState != 'synced'
            UNION ALL
            SELECT 'capital_entries', syncState
              FROM local_capital_entries WHERE syncState != 'synced'
            UNION ALL
            SELECT 'ticket_sales', syncState
              FROM local_ticket_sales WHERE syncState != 'synced'
            UNION ALL
            SELECT 'check_ins', syncState
              FROM local_check_ins WHERE syncState != 'synced'
            UNION ALL
            SELECT 'subscriptions', syncState
              FROM local_subscriptions WHERE syncState NOT IN ('synced', 'migrated_v21')
            UNION ALL
            SELECT 'membership_payment_actions', state
              FROM local_membership_payment_actions WHERE state != 'synced'
            UNION ALL
            SELECT 'membership_cancellations', syncState
              FROM local_membership_cancellations WHERE syncState != 'synced'
            UNION ALL
            SELECT 'membership_refunds', syncState
              FROM local_membership_refunds WHERE syncState NOT IN ('synced', 'withdrawn', 'migrated_v22')
            UNION ALL
            SELECT 'membership_refund_actions', state
              FROM local_membership_refund_actions WHERE state != 'synced'
            UNION ALL
            SELECT 'company_edits', syncState
              FROM local_company_edits WHERE syncState != 'synced'
            UNION ALL
            SELECT 'branches', syncState
              FROM local_branches WHERE syncState != 'synced'
            UNION ALL
            SELECT 'terminals', syncState
              FROM local_terminals WHERE syncState != 'synced'
            UNION ALL
            SELECT 'held_order_payments', syncState
              FROM local_held_order_payments WHERE syncState != 'synced'
        ) unresolved
        GROUP BY resource, state
        ORDER BY resource, state
        """,
    )
    suspend fun unresolvedGroups(): List<UnresolvedOutboxGroup>
}
