package cloud.dcompany.erp.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface RefundDao {

    // ------------------------------------------------------------ order cache
    @Query("SELECT * FROM refund_order_cache WHERE status IN ('paid', 'refunded') ORDER BY invoiceNo DESC")
    fun observeRefundableOrders(): Flow<List<RefundOrderCacheEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertOrderCache(rows: List<RefundOrderCacheEntity>)

    @Query("DELETE FROM refund_order_cache WHERE id NOT IN (:keepIds)")
    suspend fun deleteOrderCacheNotIn(keepIds: List<String>)

    @Transaction
    suspend fun replaceOrderCache(rows: List<RefundOrderCacheEntity>) {
        upsertOrderCache(rows)
        deleteOrderCacheNotIn(rows.map { it.id }.ifEmpty { listOf("") })
    }

    /** Hide terminal refund results until the next authoritative order pull. */
    @Query("DELETE FROM refund_order_cache WHERE id = :orderId")
    suspend fun deleteOrderCacheById(orderId: String): Int

    // --------------------------------------------------------- local refunds
    @Insert
    suspend fun insertLocalRefund(refund: LocalRefundEntity)

    /** Adopt an unresolved request recovered from this exact branch/terminal. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertRecoveredRefund(refund: LocalRefundEntity): Long

    @Query(
        "SELECT * FROM local_refunds WHERE state IN (" +
            "'request_pending', 'request_rejected', 'accepted_cash_due', 'accepted_provider_due', " +
            "'cash_handoff_in_progress', 'cash_settle_pending', 'cash_settle_rejected', " +
            "'cash_handed_over_pending_accounting', 'cash_finalize_rejected', " +
            "'provider_payout_in_progress', 'provider_completion_pending', " +
            "'provider_completion_rejected', 'provider_completed_pending_accounting', " +
            "'provider_finalize_rejected', " +
            "'withdrawal_pending', 'withdrawal_rejected', " +
            "'legacy_reconciliation_required') OR (state = 'withdrawn' AND payoutConflict = 1) " +
            "ORDER BY CASE state " +
            "WHEN 'cash_handoff_in_progress' THEN 0 " +
            "WHEN 'provider_payout_in_progress' THEN 0 " +
            "WHEN 'accepted_cash_due' THEN 1 " +
            "WHEN 'accepted_provider_due' THEN 1 " +
            "WHEN 'cash_settle_pending' THEN 2 " +
            "WHEN 'cash_handed_over_pending_accounting' THEN 2 " +
            "WHEN 'provider_completion_pending' THEN 2 " +
            "WHEN 'provider_completed_pending_accounting' THEN 2 " +
            "WHEN 'withdrawal_pending' THEN 2 " +
            "WHEN 'legacy_reconciliation_required' THEN 3 " +
            "WHEN 'cash_settle_rejected' THEN 4 " +
            "WHEN 'cash_finalize_rejected' THEN 4 " +
            "WHEN 'provider_completion_rejected' THEN 4 " +
            "WHEN 'provider_finalize_rejected' THEN 4 " +
            "WHEN 'withdrawal_rejected' THEN 4 " +
            "WHEN 'request_rejected' THEN 5 ELSE 6 END, createdAtMillis DESC",
    )
    fun observeUnresolvedRefunds(): Flow<List<LocalRefundEntity>>

    @Query(
        "SELECT * FROM local_refunds WHERE state = 'settled' " +
            "OR (state = 'withdrawn' AND payoutConflict = 0) " +
            "ORDER BY COALESCE(settledAtMillis, withdrawalAtMillis, acceptedAtMillis, createdAtMillis) DESC " +
            "LIMIT :limit",
    )
    fun observeRecentCompletedRefunds(limit: Int = 50): Flow<List<LocalRefundEntity>>

    @Query("SELECT * FROM local_refunds WHERE localId = :localId LIMIT 1")
    suspend fun refundById(localId: String): LocalRefundEntity?

    @Query("SELECT * FROM local_refunds WHERE clientActionId = :clientActionId LIMIT 1")
    suspend fun refundByClientActionId(clientActionId: String): LocalRefundEntity?

    @Query("SELECT * FROM local_refunds WHERE serverRequestId = :serverRequestId LIMIT 1")
    suspend fun refundByServerRequestId(serverRequestId: String): LocalRefundEntity?

    @Query(
        "SELECT COUNT(*) FROM local_refunds WHERE orderId = :orderId AND (state IN (" +
            "'request_pending', 'request_rejected', 'accepted_cash_due', 'accepted_provider_due', " +
            "'cash_handoff_in_progress', 'cash_settle_pending', 'cash_settle_rejected', " +
            "'cash_handed_over_pending_accounting', 'cash_finalize_rejected', " +
            "'provider_payout_in_progress', 'provider_completion_pending', " +
            "'provider_completion_rejected', 'provider_completed_pending_accounting', " +
            "'provider_finalize_rejected', " +
            "'withdrawal_pending', 'withdrawal_rejected', 'legacy_reconciliation_required') " +
            "OR (state = 'withdrawn' AND payoutConflict = 1))",
    )
    suspend fun unresolvedRefundCountForOrder(orderId: String): Int

    /** Serializes stale-dialog and rapid-tap duplicate capture on this device. */
    @Transaction
    suspend fun captureIfNoUnresolved(refund: LocalRefundEntity): Boolean {
        if (unresolvedRefundCountForOrder(refund.orderId) != 0) return false
        insertLocalRefund(refund)
        return true
    }

    @Query(
        "SELECT COUNT(*) FROM local_refunds WHERE state IN (" +
            "'request_rejected', 'cash_settle_rejected', 'cash_finalize_rejected', " +
            "'provider_completion_rejected', " +
            "'provider_finalize_rejected', 'withdrawal_rejected', " +
            "'legacy_reconciliation_required') OR (state = 'withdrawn' AND payoutConflict = 1)",
    )
    fun observeRejectedCount(): Flow<Int>

    @Query("SELECT * FROM local_refunds WHERE state = 'request_pending' ORDER BY createdAtMillis ASC")
    suspend fun pushableRefundRequests(): List<LocalRefundEntity>

    @Query("SELECT * FROM local_refunds WHERE state = 'cash_settle_pending' ORDER BY settledAtMillis ASC")
    suspend fun pushableCashSettlements(): List<LocalRefundEntity>

    @Query(
        "SELECT * FROM local_refunds WHERE state = 'cash_handed_over_pending_accounting' " +
            "ORDER BY settledAtMillis ASC",
    )
    suspend fun pushableCashFinalizations(): List<LocalRefundEntity>

    @Query(
        "SELECT * FROM local_refunds WHERE state = 'provider_completion_pending' " +
            "ORDER BY providerSettledAtMillis ASC",
    )
    suspend fun pushableProviderCompletions(): List<LocalRefundEntity>

    @Query(
        "SELECT * FROM local_refunds WHERE state = 'provider_completed_pending_accounting' " +
            "ORDER BY providerSettledAtMillis ASC",
    )
    suspend fun pushableProviderFinalizations(): List<LocalRefundEntity>

    @Query("SELECT * FROM local_refunds WHERE state = 'withdrawal_pending' ORDER BY withdrawalAtMillis ASC")
    suspend fun pushableWithdrawals(): List<LocalRefundEntity>

    @Query(
        "SELECT * FROM local_refunds WHERE state NOT IN ('settled', 'withdrawn', 'synced', 'cancelled') " +
            "AND clientActionId IS NOT NULL ORDER BY createdAtMillis ASC",
    )
    suspend fun reconcilableRefunds(): List<LocalRefundEntity>

    @Query(
        "UPDATE local_refunds SET state = :state, serverRequestId = :serverRequestId, " +
            "serverRefundId = :serverRefundId, serverShiftId = :serverShiftId, " +
            "branchId = COALESCE(branchId, :branchId), terminalId = COALESCE(terminalId, :terminalId), " +
            "settlementMethod = :settlementMethod, acceptedAtMillis = :acceptedAtMillis, " +
            "acceptedByUserId = COALESCE(:acceptedByUserId, acceptedByUserId), " +
            "acceptedByName = COALESCE(:acceptedByName, acceptedByName), " +
            "cashHandoffStartedAtMillis = COALESCE(:cashHandoffStartedAtMillis, cashHandoffStartedAtMillis), " +
            "cashHandoffStartedByUserId = COALESCE(:cashHandoffStartedByUserId, cashHandoffStartedByUserId), " +
            "cashHandoffStartedByName = COALESCE(:cashHandoffStartedByName, cashHandoffStartedByName), " +
            "cashHandedOverAtMillis = COALESCE(:cashHandedOverAtMillis, cashHandedOverAtMillis), " +
            "cashHandedOverRecordedAtMillis = COALESCE(:cashHandedOverRecordedAtMillis, cashHandedOverRecordedAtMillis), " +
            "cashHandedOverByUserId = COALESCE(:cashHandedOverByUserId, cashHandedOverByUserId), " +
            "cashHandedOverByName = COALESCE(:cashHandedOverByName, cashHandedOverByName), " +
            "providerPayoutStartedAtMillis = COALESCE(:providerPayoutStartedAtMillis, providerPayoutStartedAtMillis), " +
            "providerPayoutStartedByUserId = COALESCE(:providerPayoutStartedByUserId, providerPayoutStartedByUserId), " +
            "providerPayoutStartedByName = COALESCE(:providerPayoutStartedByName, providerPayoutStartedByName), " +
            "providerSettledAtMillis = COALESCE(:providerSettledAtMillis, providerSettledAtMillis), " +
            "providerCompletionRecordedAtMillis = COALESCE(:providerCompletionRecordedAtMillis, providerCompletionRecordedAtMillis), " +
            "providerCompletedByUserId = COALESCE(:providerCompletedByUserId, providerCompletedByUserId), " +
            "providerCompletedByName = COALESCE(:providerCompletedByName, providerCompletedByName), " +
            "settledAtMillis = COALESCE(:settledAtMillis, settledAtMillis), " +
            "settledByUserId = COALESCE(:settledByUserId, settledByUserId), " +
            "settledByName = COALESCE(:settledByName, settledByName), " +
            "clientOccurredAtMillis = COALESCE(:clientOccurredAtMillis, clientOccurredAtMillis), " +
            "capturedTimeReconciled = COALESCE(:capturedTimeReconciled, capturedTimeReconciled), " +
            "providerEvidenceReconciled = COALESCE(:providerEvidenceReconciled, providerEvidenceReconciled), " +
            "payoutConflict = CASE WHEN :payoutConflict THEN 1 ELSE payoutConflict END, " +
            "withdrawalAtMillis = COALESCE(:withdrawalAtMillis, withdrawalAtMillis), " +
            "withdrawnByUserId = COALESCE(:withdrawnByUserId, withdrawnByUserId), " +
            "withdrawnByName = COALESCE(:withdrawnByName, withdrawnByName), " +
            "providerVerificationStatus = COALESCE(:providerVerificationStatus, providerVerificationStatus), " +
            "providerVerificationReference = COALESCE(:providerVerificationReference, providerVerificationReference), " +
            "providerVerifiedAtMillis = COALESCE(:providerVerifiedAtMillis, providerVerifiedAtMillis), " +
            "customerSpendReconciled = COALESCE(:customerSpendReconciled, customerSpendReconciled), " +
            "loyaltyReconciliationState = COALESCE(:loyaltyReconciliationState, loyaltyReconciliationState), " +
            "externalReference = COALESCE(:externalReference, externalReference), " +
            "receiptNo = :receiptNo, lastError = :lastError " +
            "WHERE localId = :localId AND state != 'legacy_reconciliation_required'",
    )
    suspend fun applyServerState(
        localId: String,
        state: String,
        serverRequestId: String,
        serverRefundId: String?,
        serverShiftId: String,
        branchId: String,
        terminalId: String,
        settlementMethod: String,
        acceptedAtMillis: Long,
        acceptedByUserId: String?,
        acceptedByName: String?,
        cashHandoffStartedAtMillis: Long?,
        cashHandoffStartedByUserId: String?,
        cashHandoffStartedByName: String?,
        cashHandedOverAtMillis: Long?,
        cashHandedOverRecordedAtMillis: Long?,
        cashHandedOverByUserId: String?,
        cashHandedOverByName: String?,
        providerPayoutStartedAtMillis: Long?,
        providerPayoutStartedByUserId: String?,
        providerPayoutStartedByName: String?,
        providerSettledAtMillis: Long?,
        providerCompletionRecordedAtMillis: Long?,
        providerCompletedByUserId: String?,
        providerCompletedByName: String?,
        settledAtMillis: Long?,
        settledByUserId: String?,
        settledByName: String?,
        clientOccurredAtMillis: Long?,
        capturedTimeReconciled: Boolean?,
        providerEvidenceReconciled: Boolean?,
        payoutConflict: Boolean,
        withdrawalAtMillis: Long?,
        withdrawnByUserId: String?,
        withdrawnByName: String?,
        providerVerificationStatus: String?,
        providerVerificationReference: String?,
        providerVerifiedAtMillis: Long?,
        customerSpendReconciled: Boolean?,
        loyaltyReconciliationState: String?,
        externalReference: String?,
        receiptNo: String?,
        lastError: String?,
    ): Int

    @Query(
        "UPDATE local_refunds SET state = 'request_rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'request_pending'",
    )
    suspend fun markRequestRejected(localId: String, error: String): Int

    @Query(
        "UPDATE local_refunds SET lastError = :error WHERE localId = :localId " +
            "AND state IN ('request_pending', 'accepted_cash_due', 'accepted_provider_due', " +
            "'provider_payout_in_progress')",
    )
    suspend fun noteAmbiguousServerResult(localId: String, error: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'request_pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'request_rejected'",
    )
    suspend fun retryRejectedRequest(localId: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'cash_settle_pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'cash_settle_rejected'",
    )
    suspend fun retryRejectedSettlement(localId: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'cash_handed_over_pending_accounting', lastError = NULL " +
            "WHERE localId = :localId AND state = 'cash_finalize_rejected'",
    )
    suspend fun retryRejectedCashFinalization(localId: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'provider_completion_pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'provider_completion_rejected'",
    )
    suspend fun retryRejectedProviderCompletion(localId: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'provider_completed_pending_accounting', lastError = NULL " +
            "WHERE localId = :localId AND state = 'provider_finalize_rejected'",
    )
    suspend fun retryRejectedProviderFinalization(localId: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'withdrawal_pending', lastError = NULL " +
            "WHERE localId = :localId AND state = 'withdrawal_rejected'",
    )
    suspend fun retryRejectedWithdrawal(localId: String): Int

    @Transaction
    suspend fun retryRejected(localId: String): Int {
        if (retryRejectedRequest(localId) == 1) return 1
        if (retryRejectedSettlement(localId) == 1) return 1
        if (retryRejectedCashFinalization(localId) == 1) return 1
        if (retryRejectedProviderCompletion(localId) == 1) return 1
        if (retryRejectedProviderFinalization(localId) == 1) return 1
        return retryRejectedWithdrawal(localId)
    }

    /** Safe only after a definitive request refusal: the server reserved nothing. */
    @Query(
        "UPDATE local_refunds SET state = 'cancelled', lastError = NULL " +
            "WHERE localId = :localId AND state = 'request_rejected'",
    )
    suspend fun cancelRejectedRequest(localId: String): Int

    /** Server must confirm the guarded handover window before this can run. */
    @Query(
        "UPDATE local_refunds SET state = 'cash_settle_pending', settledAtMillis = :settledAtMillis, " +
            "lastError = NULL WHERE localId = :localId " +
            "AND state = 'cash_handoff_in_progress' AND serverRequestId IS NOT NULL " +
            "AND settlementMethod = 'cash'",
    )
    suspend fun confirmCashHandedOver(localId: String, settledAtMillis: Long): Int

    /** Server must open the provider payout before immutable completion evidence is accepted. */
    @Query(
        "UPDATE local_refunds SET state = 'provider_completion_pending', " +
            "externalReference = :externalReference, " +
            "providerSettledAtMillis = :providerSettledAtMillis, lastError = NULL " +
            "WHERE localId = :localId AND state = 'provider_payout_in_progress' " +
            "AND serverRequestId IS NOT NULL AND settlementMethod != 'cash'",
    )
    suspend fun confirmProviderCompleted(
        localId: String,
        externalReference: String,
        providerSettledAtMillis: Long,
    ): Int

    @Query(
        "UPDATE local_refunds SET state = 'withdrawal_pending', withdrawalReason = :reason, " +
            "withdrawalAtMillis = :withdrawalAtMillis, lastError = NULL " +
            "WHERE localId = :localId AND state = 'accepted_cash_due' " +
            "AND serverRequestId IS NOT NULL AND settlementMethod = 'cash'",
    )
    suspend fun requestWithdrawal(localId: String, reason: String, withdrawalAtMillis: Long): Int

    @Query(
        "UPDATE local_refunds SET state = 'cash_settle_rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'cash_settle_pending'",
    )
    suspend fun markCashSettlementRejected(localId: String, error: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'cash_finalize_rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'cash_handed_over_pending_accounting'",
    )
    suspend fun markCashFinalizationRejected(localId: String, error: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'provider_completion_rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'provider_completion_pending'",
    )
    suspend fun markProviderCompletionRejected(localId: String, error: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'provider_finalize_rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'provider_completed_pending_accounting'",
    )
    suspend fun markProviderFinalizationRejected(localId: String, error: String): Int

    @Query(
        "UPDATE local_refunds SET state = 'withdrawal_rejected', lastError = :error " +
            "WHERE localId = :localId AND state = 'withdrawal_pending'",
    )
    suspend fun markWithdrawalRejected(localId: String, error: String): Int

    /** Exact-shift gate; unrelated completed shifts do not block this one. */
    @Query(
        "SELECT COUNT(*) FROM local_refunds WHERE (state IN (" +
            "'request_pending', 'request_rejected', 'accepted_cash_due', 'accepted_provider_due', " +
            "'cash_handoff_in_progress', 'cash_settle_pending', 'cash_settle_rejected', " +
            "'cash_handed_over_pending_accounting', 'cash_finalize_rejected', " +
            "'provider_payout_in_progress', 'provider_completion_pending', " +
            "'provider_completion_rejected', 'provider_completed_pending_accounting', " +
            "'provider_finalize_rejected', " +
            "'withdrawal_pending', 'withdrawal_rejected') " +
            "OR (state = 'withdrawn' AND payoutConflict = 1)) " +
            "AND (shiftId = :localShiftId OR (:serverShiftId IS NOT NULL AND serverShiftId = :serverShiftId) " +
            "OR (:serverShiftId IS NOT NULL AND shiftId = :serverShiftId))",
    )
    suspend fun unresolvedMoneyCountForShift(localShiftId: String, serverShiftId: String?): Int

    /** Unknown-shift v19 history is a separate, explicit reconciliation gate. */
    @Query("SELECT COUNT(*) FROM local_refunds WHERE state = 'legacy_reconciliation_required'")
    suspend fun legacyReconciliationCount(): Int
}
