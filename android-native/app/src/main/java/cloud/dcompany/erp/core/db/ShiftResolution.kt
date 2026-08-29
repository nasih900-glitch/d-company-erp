package cloud.dcompany.erp.core.db

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Identity and operational override returned by the authenticated profile. */
data class ShiftActor(
    val userId: String,
    val protectedAccess: Boolean,
)

enum class ShiftSource { LOCAL_OUTBOX, SERVER_CACHE }

/** A complete, server-asserted collection/refund breakdown for one shift. */
data class ShiftAccountingBreakdown(
    val posCollectionsMinor: Long,
    val membershipCollectionsMinor: Long,
    val grossCollectionsMinor: Long,
    val cashCollectionsMinor: Long,
    val cardCollectionsMinor: Long,
    val upiCollectionsMinor: Long,
    val otherCollectionsMinor: Long,
    val settledPosRefundsMinor: Long,
    val settledMembershipRefundsMinor: Long,
    val totalRefundsMinor: Long,
    val netCollectionsMinor: Long,
)

/**
 * One resolved shift for all native screens.
 *
 * Tables and Gaming consume [shiftId] regardless of opener: they create
 * operational work, not a cash settlement. POS collection and shift close use
 * [canManageMoney] and therefore fail closed when opener identity is unknown.
 */
data class ResolvedOpenShift(
    val shiftId: String,
    val source: ShiftSource,
    val local: LocalShiftEntity? = null,
    val server: ServerOpenShiftEntity? = null,
    val openedAtMillis: Long,
    val openingFloatMinor: Long,
    val expectedMinor: Long?,
    val posCollectionsMinor: Long? = null,
    val membershipCollectionsMinor: Long? = null,
    val grossCollectionsMinor: Long? = null,
    val cashCollectionsMinor: Long? = null,
    val cardCollectionsMinor: Long? = null,
    val upiCollectionsMinor: Long? = null,
    val otherCollectionsMinor: Long? = null,
    val settledPosRefundsMinor: Long? = null,
    val settledMembershipRefundsMinor: Long? = null,
    val totalRefundsMinor: Long? = null,
    val netCollectionsMinor: Long? = null,
    val openedByUserId: String?,
    val openedByName: String?,
    val openedByEmail: String?,
) {
    /**
     * Do not manufacture missing accounting values. Older servers provide
     * POS receipts but not the audited gross/refund/net contract; the UI must
     * hide that breakdown until every value is explicitly available.
     */
    fun accountingBreakdownOrNull(): ShiftAccountingBreakdown? {
        return ShiftAccountingBreakdown(
            posCollectionsMinor = posCollectionsMinor ?: return null,
            membershipCollectionsMinor = membershipCollectionsMinor ?: return null,
            grossCollectionsMinor = grossCollectionsMinor ?: return null,
            cashCollectionsMinor = cashCollectionsMinor ?: return null,
            cardCollectionsMinor = cardCollectionsMinor ?: return null,
            upiCollectionsMinor = upiCollectionsMinor ?: return null,
            otherCollectionsMinor = otherCollectionsMinor ?: return null,
            settledPosRefundsMinor = settledPosRefundsMinor ?: return null,
            settledMembershipRefundsMinor = settledMembershipRefundsMinor ?: return null,
            totalRefundsMinor = totalRefundsMinor ?: return null,
            netCollectionsMinor = netCollectionsMinor ?: return null,
        )
    }

    fun canManageMoney(actor: ShiftActor?): Boolean = actor != null && (
        actor.protectedAccess || openedByUserId == actor.userId
    )

    fun moneyAccessMessage(actor: ShiftActor?): String? {
        if (canManageMoney(actor)) return null
        val who = openedByName?.takeIf(String::isNotBlank)
            ?: openedByEmail?.takeIf(String::isNotBlank)
            ?: "another staff member"
        return if (openedByUserId == null) {
            "The shift opener has not been verified on this tablet. Reconnect before taking payment or closing the shift."
        } else {
            "Shift opened by $who. Tables and Gaming can continue, but only that opener or a protected owner can collect POS payment or close it."
        }
    }
}

/** Pure overlay policy, split out for unit tests and shared by every screen. */
object ShiftResolutionPolicy {
    fun resolve(
        local: LocalShiftEntity?,
        server: ServerOpenShiftEntity?,
        includeClosingIntent: Boolean = false,
    ): ResolvedOpenShift? {
        // Closing is an immediate local stop signal. A stale server cache may
        // still say open until the close leg reaches the backend; never let it
        // silently re-enable billing in that window.
        if (local?.state == ShiftState.CLOSE_PENDING && !includeClosingIntent) return null

        if (
            local != null && local.state in setOf(
                ShiftState.OPEN_PENDING,
                ShiftState.OPEN_SYNCED,
                ShiftState.CLOSE_PENDING,
                ShiftState.CLOSE_REJECTED,
            )
        ) {
            val matchingServer = server?.takeIf { cached ->
                local.serverShiftId != null && cached.serverShiftId == local.serverShiftId
            }
            return ResolvedOpenShift(
                shiftId = local.serverShiftId ?: local.localId,
                source = ShiftSource.LOCAL_OUTBOX,
                local = local,
                server = matchingServer,
                openedAtMillis = matchingServer?.openedAtMillis ?: local.openedAtMillis,
                openingFloatMinor = matchingServer?.openingFloatMinor ?: local.openingFloatMinor,
                expectedMinor = matchingServer?.expectedMinor,
                posCollectionsMinor = matchingServer?.posCollectionsMinor,
                membershipCollectionsMinor = matchingServer?.membershipCollectionsMinor,
                grossCollectionsMinor = matchingServer?.grossCollectionsMinor,
                cashCollectionsMinor = matchingServer?.cashCollectionsMinor,
                cardCollectionsMinor = matchingServer?.cardCollectionsMinor,
                upiCollectionsMinor = matchingServer?.upiCollectionsMinor,
                otherCollectionsMinor = matchingServer?.otherCollectionsMinor,
                settledPosRefundsMinor = matchingServer?.settledPosRefundsMinor,
                settledMembershipRefundsMinor = matchingServer?.settledMembershipRefundsMinor,
                totalRefundsMinor = matchingServer?.totalRefundsMinor,
                netCollectionsMinor = matchingServer?.netCollectionsMinor,
                openedByUserId = matchingServer?.openedByUserId ?: local.openedByUserId,
                openedByName = matchingServer?.openedByName ?: local.openedByName,
                openedByEmail = matchingServer?.openedByEmail ?: local.openedByEmail,
            )
        }

        return server?.let {
            ResolvedOpenShift(
                shiftId = it.serverShiftId,
                source = ShiftSource.SERVER_CACHE,
                server = it,
                openedAtMillis = it.openedAtMillis,
                openingFloatMinor = it.openingFloatMinor,
                expectedMinor = it.expectedMinor,
                posCollectionsMinor = it.posCollectionsMinor,
                membershipCollectionsMinor = it.membershipCollectionsMinor,
                grossCollectionsMinor = it.grossCollectionsMinor,
                cashCollectionsMinor = it.cashCollectionsMinor,
                cardCollectionsMinor = it.cardCollectionsMinor,
                upiCollectionsMinor = it.upiCollectionsMinor,
                otherCollectionsMinor = it.otherCollectionsMinor,
                settledPosRefundsMinor = it.settledPosRefundsMinor,
                settledMembershipRefundsMinor = it.settledMembershipRefundsMinor,
                totalRefundsMinor = it.totalRefundsMinor,
                netCollectionsMinor = it.netCollectionsMinor,
                openedByUserId = it.openedByUserId,
                openedByName = it.openedByName,
                openedByEmail = it.openedByEmail,
            )
        }
    }
}

/** One shared reactive resolver so POS, Tables, Gaming and Shift cannot drift. */
fun ShiftDao.observeResolvedOpenShift(terminalId: String?): Flow<ResolvedOpenShift?> =
    if (terminalId == null) {
        // No X-Terminal-Id means the backend cannot accept shift-scoped work.
        // A legitimately offline tablet retains its durable terminal id.
        flowOf(null)
    } else {
        combine(observeCurrentForTerminal(terminalId), observeServerOpen(terminalId)) { local, server ->
            ShiftResolutionPolicy.resolve(local, server)
        }
    }

/** Activity-scoped ViewModels survive sign-out, so terminal assignment is live. */
@OptIn(ExperimentalCoroutinesApi::class)
fun ShiftDao.observeResolvedOpenShift(terminalIds: Flow<String?>): Flow<ResolvedOpenShift?> =
    terminalIds.distinctUntilChanged().flatMapLatest { terminalId ->
        observeResolvedOpenShift(terminalId)
    }

/** Shift management still displays a queued close while operations are blocked. */
fun ShiftDao.observeShiftForManagement(terminalId: String?): Flow<ResolvedOpenShift?> =
    if (terminalId == null) {
        flowOf(null)
    } else {
        combine(observeCurrentForTerminal(terminalId), observeServerOpen(terminalId)) { local, server ->
            ShiftResolutionPolicy.resolve(local, server, includeClosingIntent = true)
        }
    }

@OptIn(ExperimentalCoroutinesApi::class)
fun ShiftDao.observeShiftForManagement(terminalIds: Flow<String?>): Flow<ResolvedOpenShift?> =
    terminalIds.distinctUntilChanged().flatMapLatest { terminalId ->
        observeShiftForManagement(terminalId)
    }
