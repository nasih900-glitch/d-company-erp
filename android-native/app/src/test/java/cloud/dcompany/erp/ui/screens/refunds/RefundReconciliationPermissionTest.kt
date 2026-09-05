package cloud.dcompany.erp.ui.screens.refunds

import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.db.RefundState
import cloud.dcompany.erp.core.net.MeResponse
import java.nio.file.Files
import java.nio.file.Paths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RefundReconciliationPermissionTest {
    private val profile = MeResponse(
        userId = "employee", name = "Employee", email = "employee@example.test",
        companyId = "company", effectivePermissions = listOf(ErpPermission.PosRefundReconcile),
    )

    @Test
    fun `financial reconciliation can be granted without audit authority`() {
        assertTrue(canReconcilePosRefund(profile))
        assertFalse(profile.protectedAccess)
        assertFalse(EffectivePermissions.from(profile).has(ErpPermission.AdminAuditRead))
        assertFalse(EffectivePermissions.from(profile).has(ErpPermission.AdminSystem))
    }

    @Test
    fun `protected flag and ordinary refund permission do not invent reconciliation authority`() {
        assertFalse(canReconcilePosRefund(null))
        assertFalse(canReconcilePosRefund(profile.copy(protectedAccess = true, effectivePermissions = emptyList())))
        assertFalse(canReconcilePosRefund(profile.copy(effectivePermissions = listOf(ErpPermission.PosRefund))))
    }

    @Test
    fun `exact reconciler can resolve only failed provider attempt on same open shift`() {
        val task = providerTask()
        val currentShiftIds = setOf("local-shift", "server-shift")

        assertTrue(canResolveFailedProviderPayout(task, true, true, currentShiftIds))
        assertTrue(canResolveFailedProviderPayout(
            task.copy(shiftId = "other", serverShiftId = "server-shift"),
            true,
            true,
            currentShiftIds,
        ))
        assertFalse(canResolveFailedProviderPayout(task, false, true, currentShiftIds))
        assertFalse(canResolveFailedProviderPayout(task, true, false, currentShiftIds))
        assertFalse(canResolveFailedProviderPayout(task, true, true, setOf("another-shift")))
        assertFalse(canResolveFailedProviderPayout(
            task.copy(state = RefundState.ACCEPTED_PROVIDER_DUE),
            true,
            true,
            currentShiftIds,
        ))
    }

    @Test
    fun `refund task shift identities remain explicit`() {
        val task = providerTask()
        assertEquals("local-shift", task.shiftId)
        assertEquals("server-shift", task.serverShiftId)
    }

    @Test
    fun `shift actor bypass is confined to failed provider reconciliation`() {
        val roots = listOf(
            Paths.get("src/main/java"),
            Paths.get("app/src/main/java"),
            Paths.get("android-native/app/src/main/java"),
        )
        val root = roots.firstOrNull(Files::isDirectory)
            ?: error("Could not locate Android main source root")
        val source = Files.newBufferedReader(
            root.resolve("cloud/dcompany/erp/core/sync/SyncEngine.kt"),
        ).use { it.readText() }
        val resolver = source.substring(
            source.indexOf("suspend fun resolvePosRefundProviderPayout("),
            source.indexOf("private suspend fun drainRequestedSyncs("),
        )

        assertTrue("resolver must check the exact permission", "ErpPermission.PosRefundReconcile" in resolver)
        assertTrue("resolver must use the narrow actor exception", "requireShiftActor = false" in resolver)
        assertEquals(
            "No payout-creation, settlement, withdrawal or finalisation path may share the exception",
            1,
            Regex("requireShiftActor\\s*=\\s*false").findAll(source).count(),
        )
        assertTrue(
            "All other callers must remain shift-actor gated by default",
            "requireShiftActor: Boolean = true" in source,
        )
    }

    private fun providerTask() = RefundTask(
        localId = "refund-1",
        orderId = "order-1",
        invoiceNo = "INV-1",
        amountMinor = 2_500,
        reasonCode = "billing_error",
        createdAtMillis = 1_000,
        state = RefundState.PROVIDER_PAYOUT_IN_PROGRESS,
        mode = "original",
        settlementMethod = "upi",
        serverRequestId = "server-refund",
        acceptedAtMillis = 1_100,
        handoffStartedAtMillis = null,
        settledAtMillis = null,
        localPayoutAtMillis = null,
        withdrawalAtMillis = null,
        receiptNo = null,
        externalReference = null,
        acceptedByUserId = "opener",
        acceptedByName = "Rafi",
        moneyStartedByUserId = "opener",
        moneyStartedByName = "Rafi",
        moneyCompletedByUserId = null,
        moneyCompletedByName = null,
        settledByUserId = null,
        settledByName = null,
        withdrawnByUserId = null,
        withdrawnByName = null,
        providerVerificationStatus = null,
        providerVerificationReference = null,
        customerSpendReconciled = null,
        loyaltyReconciliationState = null,
        capturedTimeReconciled = null,
        providerEvidenceReconciled = null,
        payoutConflict = false,
        error = null,
        shiftId = "local-shift",
        serverShiftId = "server-shift",
    )
}
