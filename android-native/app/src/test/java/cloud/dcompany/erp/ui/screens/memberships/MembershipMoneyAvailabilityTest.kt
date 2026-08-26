package cloud.dcompany.erp.ui.screens.memberships

import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MembershipMoneyAvailabilityTest {

    @Test
    fun liveConnectionAllowsPaidMembershipInitiation() {
        assertNull(
            membershipMoneyOfflineMessage(
                online = true,
                operation = MembershipMoneyOperation.SALE,
            ),
        )
        assertNull(
            membershipMoneyOfflineMessage(
                online = true,
                operation = MembershipMoneyOperation.REFUND,
            ),
        )
    }

    @Test
    fun offlineSaleCopyPreventsCollectingMoney() {
        val message = membershipMoneyOfflineMessage(
            online = false,
            operation = MembershipMoneyOperation.SALE,
        ).orEmpty()
        assertTrue(message.contains("live ERP connection"))
        assertTrue(message.contains("Do not collect cash"))
    }

    @Test
    fun offlineRefundCopyPreventsCashOrProviderPayout() {
        val message = membershipMoneyOfflineMessage(
            online = false,
            operation = MembershipMoneyOperation.REFUND,
        ).orEmpty()
        assertTrue(message.contains("must start online"))
        assertTrue(message.contains("Do not hand over cash"))
        assertTrue(message.contains("provider refund"))
    }

    @Test
    fun unknownServerStagesFailClosedWithAPlainMoneyWarning() {
        val payment = membershipPaymentStageMessage("new_server_payment_state")
        val refund = membershipRefundStageMessage("new_server_refund_state")

        assertTrue(payment.contains("Unknown payment state"))
        assertTrue(payment.contains("do not move money"))
        assertTrue(refund.contains("Unknown refund state"))
        assertTrue(refund.contains("do not move money"))
    }

    @Test
    fun completedButUnpostedStagesExplicitlyForbidDuplicateMovement() {
        assertTrue(
            membershipPaymentStageMessage(
                MembershipPaymentTaskStatus.PAYMENT_COMPLETED_PENDING_POSTING,
            ).contains("Do not collect again"),
        )
        assertTrue(
            membershipRefundStageMessage(
                MembershipRefundTaskStatus.PAYOUT_COMPLETED_PENDING_POSTING,
            ).contains("Do not pay again"),
        )
    }

    @Test
    fun moneyActionsRequireBothProtectedScopeAndAdminSystemPermission() {
        val unprotectedAdmin = profile(
            protectedAccess = false,
            effectivePermissions = listOf(ErpPermission.AdminSystem),
        )
        val protectedWithoutPermission = profile(
            protectedAccess = true,
            effectivePermissions = emptyList(),
        )
        val protectedAdmin = profile(
            protectedAccess = true,
            effectivePermissions = listOf(ErpPermission.AdminSystem),
        )

        assertFalse(canManageMembershipMoney(unprotectedAdmin))
        assertFalse(canManageMembershipMoney(protectedWithoutPermission))
        assertTrue(canManageMembershipMoney(protectedAdmin))
    }

    private fun profile(
        protectedAccess: Boolean,
        effectivePermissions: List<String>,
    ) = MeResponse(
        userId = "owner-1",
        email = "owner@example.com",
        name = "Owner",
        roles = listOf("co_owner"),
        protectedAccess = protectedAccess,
        companyId = "company-1",
        branchId = "branch-1",
        effectivePermissions = effectivePermissions,
    )
}
