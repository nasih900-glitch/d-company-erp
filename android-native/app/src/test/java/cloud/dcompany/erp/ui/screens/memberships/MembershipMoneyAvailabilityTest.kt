package cloud.dcompany.erp.ui.screens.memberships

import cloud.dcompany.erp.core.auth.ErpPermission
import cloud.dcompany.erp.core.db.MembershipPaymentTaskStatus
import cloud.dcompany.erp.core.db.MembershipRefundTaskStatus
import cloud.dcompany.erp.core.net.MeResponse
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MembershipMoneyAvailabilityTest {

    @Test
    fun liveConnectionAllowsPaidMembershipInitiation() {
        assertNull(
            membershipMoneyOfflineMessage(
                online = true,
                operation = MembershipMoneyOperation.PREPARE,
            ),
        )
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
    fun offlinePreparationIsAllowedBecauseItCannotMoveMoney() {
        assertNull(
            membershipMoneyOfflineMessage(
                online = false,
                operation = MembershipMoneyOperation.PREPARE,
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
    fun moneyActionsRequireBothProtectedScopeAndMembershipPermission() {
        val unprotectedAdmin = profile(
            protectedAccess = false,
            effectivePermissions = listOf(ErpPermission.MembershipsManage),
        )
        val protectedWithoutPermission = profile(
            protectedAccess = true,
            effectivePermissions = emptyList(),
        )
        val protectedAdmin = profile(
            protectedAccess = true,
            effectivePermissions = listOf(ErpPermission.MembershipsManage),
        )
        val protectedSystemOnly = profile(
            protectedAccess = true,
            effectivePermissions = listOf(ErpPermission.AdminSystem),
        )

        assertFalse(canManageMembershipMoney(unprotectedAdmin))
        assertFalse(canManageMembershipMoney(protectedWithoutPermission))
        assertTrue(canManageMembershipMoney(protectedAdmin))
        assertFalse(canManageMembershipMoney(protectedSystemOnly))
    }

    @Test
    fun coOwnerKeepsOrdinaryMembershipMoneyWithoutAuditRecoveryAuthority() {
        val coOwner = profile(
            protectedAccess = true,
            auditAccess = false,
            effectivePermissions = listOf(ErpPermission.MembershipsManage),
        )
        val auditBitWithoutServerPermission = profile(
            protectedAccess = true,
            auditAccess = true,
            effectivePermissions = listOf(ErpPermission.MembershipsManage),
        )
        val systemPermissionWithoutAuditIdentity = profile(
            protectedAccess = true,
            auditAccess = false,
            effectivePermissions = listOf(
                ErpPermission.MembershipsManage,
                ErpPermission.AdminSystem,
            ),
        )
        val auditOwner = profile(
            protectedAccess = true,
            auditAccess = true,
            effectivePermissions = listOf(
                ErpPermission.MembershipsManage,
                ErpPermission.AdminSystem,
            ),
        )

        assertTrue(canManageMembershipMoney(coOwner))
        assertFalse(canRecoverLegacyMembershipEvidence(coOwner))
        assertFalse(canRecoverLegacyMembershipEvidence(auditBitWithoutServerPermission))
        assertFalse(canRecoverLegacyMembershipEvidence(systemPermissionWithoutAuditIdentity))
        assertTrue(canRecoverLegacyMembershipEvidence(auditOwner))
    }

    @Test
    fun deniedLegacyRecoveryCopyExplainsEscalationAndPreventsDuplicateMoney() {
        assertTrue(MEMBERSHIP_AUDIT_CONTROL_MESSAGE.contains("read only"))
        assertTrue(MEMBERSHIP_AUDIT_CONTROL_MESSAGE.contains("Audit Control owner"))
        assertTrue(MEMBERSHIP_AUDIT_CONTROL_MESSAGE.contains("do not repeat"))
    }

    @Test
    fun cachedMembershipFailsClosedAfterExpiryOrWithInvalidDates() {
        val now = Instant.parse("2026-08-27T12:00:00Z")
        val active = subscription(
            startsAt = "2026-08-01T00:00:00Z",
            expiresAt = "2026-09-01T00:00:00Z",
        )

        assertTrue(active.isActiveAt(now))
        assertTrue(active.copy(cancelledAt = "2026-08-20T00:00:00Z").isActiveAt(now))
        assertFalse(active.copy(expiresAt = "2026-08-27T12:00:00Z").isActiveAt(now))
        assertFalse(active.copy(startsAt = "2026-08-28T00:00:00Z").isActiveAt(now))
        assertFalse(active.copy(isActive = false).isActiveAt(now))
        assertFalse(active.copy(revokedAt = "2026-08-20T00:00:00Z").isActiveAt(now))
        assertFalse(active.copy(expiresAt = "not-a-timestamp").isActiveAt(now))
    }

    private fun subscription(startsAt: String, expiresAt: String) = Subscription(
        id = "membership-1",
        customerId = "customer-1",
        tierId = "tier-1",
        tierCode = "gold",
        tierName = "Gold",
        billingCycle = "monthly",
        startsAt = startsAt,
        expiresAt = expiresAt,
        cancelledAt = null,
        revokedAt = null,
        autoRenew = false,
        amountPaidMinor = 100_00,
        isActive = true,
    )

    private fun profile(
        protectedAccess: Boolean,
        auditAccess: Boolean = false,
        effectivePermissions: List<String>,
    ) = MeResponse(
        userId = "owner-1",
        email = "owner@example.com",
        name = "Owner",
        roles = listOf("co_owner"),
        protectedAccess = protectedAccess,
        auditAccess = auditAccess,
        companyId = "company-1",
        branchId = "branch-1",
        effectivePermissions = effectivePermissions,
    )
}
