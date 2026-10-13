package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.CustomerRewardEligibility
import cloud.dcompany.erp.core.net.OrderPointsRedemptionUpdateRequest
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.db.CustomerCacheEntity
import cloud.dcompany.erp.core.db.LocalPointsRedemptionActionEntity
import cloud.dcompany.erp.core.db.LoyaltyActionState
import cloud.dcompany.erp.core.db.mergeCustomerProjection
import cloud.dcompany.erp.ui.screens.settings.LoyaltyProgramRulesDto
import cloud.dcompany.erp.ui.screens.settings.LoyaltyRankRuleDto
import cloud.dcompany.erp.ui.screens.settings.LoyaltyRewardRuleDto
import cloud.dcompany.erp.ui.screens.settings.loyaltyProgramChangeSummary
import cloud.dcompany.erp.ui.screens.settings.validateLoyaltyRules
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LoyaltyProgramContractTest {
    @Test
    fun `legacy points retry omits program revision byte for byte`() {
        val body = ApiClient.json.encodeToString(
            OrderPointsRedemptionUpdateRequest(points = 100, expectedCheckoutVersion = 7),
        )
        assertEquals("{\"points\":100,\"expected_checkout_version\":7}", body)
        assertFalse(body.contains("expected_program_revision"))
    }

    @Test
    fun `new points key freezes revision and body`() {
        val key = pointsRedemptionIdempotencyKeyV2("local", "order", 7, 100, 4)
        assertEquals("order-points:v2:local:order:7:4:100", key)
    }

    @Test
    fun `response loss replay uses captured body before terminal void evidence`() = runBlocking {
        val action = savedAction(LoyaltyActionState.AMBIGUOUS)
        var replayCompleted = false

        val evidence = replayCapturedPointsActionThenLoadCanonical(
            action = action,
            updatePoints = { orderId, body, key ->
                assertEquals(action.orderId, orderId)
                assertEquals(action.actionId, key)
                assertEquals(100, body.points)
                assertEquals(7L, body.expectedCheckoutVersion)
                assertEquals(4L, body.expectedProgramRevision)
                replayCompleted = true
                Order(id = orderId, status = "open", checkoutVersion = 8)
            },
            updateReward = { _, _, _ -> error("generic action must not become a reward") },
            loadCanonical = { orderId ->
                assertTrue("terminal read must follow exact replay", replayCompleted)
                Order(id = orderId, status = "void", checkoutVersion = 9)
            },
        )

        assertEquals(8L, evidence.accepted.checkoutVersion)
        assertEquals("void", evidence.canonical.status)
        assertEquals(9L, evidence.canonical.checkoutVersion)
    }

    @Test
    fun `uncertain replay never advances to terminal retirement evidence`() = runBlocking {
        val action = savedAction(LoyaltyActionState.AMBIGUOUS)
        var canonicalRead = false

        val failure = runCatching {
            replayCapturedPointsActionThenLoadCanonical(
                action = action,
                updatePoints = { _, _, _ -> error("connection lost again") },
                updateReward = { _, _, _ -> error("wrong action kind") },
                loadCanonical = {
                    canonicalRead = true
                    Order(id = it, status = "void")
                },
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertFalse(canonicalRead)
    }

    @Test
    fun `failed canonical read is distinguished from a rejected replay`() = runBlocking {
        val action = savedAction(LoyaltyActionState.AMBIGUOUS)
        val failure = runCatching {
            replayCapturedPointsActionThenLoadCanonical(
                action = action,
                updatePoints = { orderId, _, _ -> Order(id = orderId, status = "open") },
                updateReward = { _, _, _ -> error("wrong action kind") },
                loadCanonical = { error("canonical read failed") },
            )
        }.exceptionOrNull()

        assertTrue(failure is CanonicalPointsEvidenceException)
        assertTrue(failure?.cause?.message?.contains("canonical read failed") == true)
    }

    @Test
    fun `open reconciliation checkpoints fresh canonical instead of frozen replay`() = runBlocking {
        val action = savedAction(LoyaltyActionState.AMBIGUOUS)
        val evidence = replayCapturedPointsActionThenLoadCanonical(
            action = action,
            updatePoints = { orderId, body, _ ->
                assertEquals(7L, body.expectedCheckoutVersion)
                Order(id = orderId, status = "open", checkoutVersion = 8, totalMinor = 1_000)
            },
            updateReward = { _, _, _ -> error("wrong action kind") },
            loadCanonical = { orderId ->
                Order(id = orderId, status = "open", checkoutVersion = 9, totalMinor = 1_500)
            },
        )

        assertEquals(8L, evidence.accepted.checkoutVersion)
        assertEquals(1_000L, evidence.accepted.totalMinor)
        assertEquals(9L, evidence.canonical.checkoutVersion)
        assertEquals(1_500L, evidence.canonical.totalMinor)
    }

    @Test
    fun `response loss accepts every supported canonical lifecycle only after replay`() = runBlocking {
        mapOf(
            "open" to CanonicalPointsDisposition.REVIEWABLE,
            "held" to CanonicalPointsDisposition.REVIEWABLE,
            "void" to CanonicalPointsDisposition.VOID,
            "paid" to CanonicalPointsDisposition.SETTLED,
            "refunded" to CanonicalPointsDisposition.SETTLED,
        ).forEach { (status, expectedDisposition) ->
            var replayed = false
            val evidence = replayCapturedPointsActionThenLoadCanonical(
                action = savedAction(LoyaltyActionState.AMBIGUOUS),
                updatePoints = { orderId, _, _ ->
                    replayed = true
                    Order(id = orderId, status = "open", checkoutVersion = 8)
                },
                updateReward = { _, _, _ -> error("wrong action kind") },
                loadCanonical = { orderId ->
                    assertTrue(replayed)
                    Order(id = orderId, status = status, checkoutVersion = 9)
                },
            )
            assertEquals(status, evidence.canonical.status)
            assertEquals(expectedDisposition, canonicalPointsDisposition(status))
        }
    }

    @Test
    fun `settings validation rejects non increasing ranks and accepts legacy shape`() {
        val valid = legacyRules()
        assertEquals(null, validateLoyaltyRules(valid))
        val invalid = valid.copy(
            ranks = valid.ranks.map { if (it.key == "player") it.copy(threshold = 0) else it },
        )
        assertNotNull(validateLoyaltyRules(invalid))
    }

    @Test
    fun `named rewards keep stable keys and explicit economics`() {
        val rules = legacyRules()
        assertEquals(7, rules.rewards.size)
        assertTrue(rules.rewards.all { it.pointsCost > 0 && it.valueMinor > 0 })
        assertEquals(10, rules.minorPerPoint)
    }

    @Test
    fun `named rewards use server stable rank eligibility and explain locked choices`() {
        val rules = legacyRules()
        val choices = rewardEligibilityChoices(
            rules = rules,
            revision = 4,
            phone = "9999999999",
            catalog = EligibleRewardCatalogState(
                phone = "9999999999",
                programRevision = 4,
                rewards = listOf(
                    CustomerRewardEligibility(
                        programRevision = 4,
                        key = "extra_controller",
                        name = "Controller",
                        description = "Credit",
                        pointsCost = 50,
                        valueMinor = 3_000,
                        minRank = "Rookie",
                        affordable = true,
                    ),
                ),
                verified = true,
            ),
        )

        assertTrue(choices.first { it.key == "extra_controller" }.affordable)
        val legend = choices.first { it.key == "vr_legend" }
        assertFalse(legend.eligible)
        assertEquals("Unlocks at Legend rank", legend.unavailableReason)
    }

    @Test
    fun `named reward catalog stays bound to exact customer and revision`() {
        val catalog = EligibleRewardCatalogState(
            phone = "9999999999",
            programRevision = 4,
            verified = true,
        )
        assertTrue(
            rewardEligibilityChoices(
                legacyRules(),
                revision = 4,
                phone = "8888888888",
                catalog = catalog,
            ).isEmpty(),
        )
        assertTrue(
            rewardEligibilityChoices(
                legacyRules(),
                revision = 5,
                phone = "9999999999",
                catalog = catalog,
            ).isEmpty(),
        )
    }

    @Test
    fun `explicit retry changes catalog request identity without changing customer economics`() {
        val failed = RewardCatalogRequest(
            phone = "9999999999",
            programRevision = 4,
            online = true,
            companyId = "company-1",
            userId = "user-1",
            refreshGeneration = 7,
        )
        val retry = failed.copy(refreshGeneration = 8)

        assertNotEquals(failed, retry)
        assertEquals(failed.phone, retry.phone)
        assertEquals(failed.programRevision, retry.programRevision)
        assertEquals(failed.companyId, retry.companyId)
        assertEquals(failed.userId, retry.userId)
    }

    @Test
    fun `owner review lists frozen rank reward and rupee economics`() {
        val before = legacyRules()
        val after = before.copy(
            gamingEarnPoints = 3,
            minorPerPoint = 11,
            ranks = before.ranks.map {
                if (it.key == "player") it.copy(threshold = 250, bonusPoints = 75) else it
            },
            rewards = before.rewards.map {
                if (it.key == "extra_controller") {
                    it.copy(pointsCost = 55, valueMinor = 3_050, minRankKey = "player", enabled = false)
                } else it
            },
        )

        val summary = loyaltyProgramChangeSummary(before, after)

        assertTrue(summary.any { it.contains("Gaming earn: 2 per ₹10 → 3 per ₹10") })
        assertTrue(summary.any { it.contains("100-point value: ₹10.00 → ₹11.00") })
        assertTrue(summary.any { it.contains("player rank") && it.contains("threshold 200 → 250") && it.contains("bonus 50 → 75") })
        assertTrue(summary.any { it.contains("extra_controller") && it.contains("credit ₹30.00 → ₹30.50") && it.contains("minimum rank rookie → player") && it.contains("disabled") })
    }

    @Test
    fun `older and unknown program projections cannot erase newer customer economics`() {
        val newer = customerProjection(revision = 4, points = 900, value = 11)
        val older = customerProjection(revision = 3, points = 100, value = 10).copy(name = "New name")
        val legacy = customerProjection(revision = null, points = 0, value = null).copy(name = "Newest name")

        val afterOlder = mergeCustomerProjection(newer, older)
        assertEquals("New name", afterOlder.name)
        assertEquals(4L, afterOlder.loyaltyProgramRevision)
        assertEquals(900, afterOlder.loyaltyPoints)
        assertEquals(11, afterOlder.loyaltyPointValueMinor)

        val afterLegacy = mergeCustomerProjection(afterOlder, legacy)
        assertEquals("Newest name", afterLegacy.name)
        assertEquals(4L, afterLegacy.loyaltyProgramRevision)
        assertEquals(900, afterLegacy.loyaltyPoints)
    }

    private fun customerProjection(
        revision: Long?,
        points: Int,
        value: Int?,
    ) = CustomerCacheEntity(
        id = "customer-1",
        name = "Customer",
        phone = "9999999999",
        email = null,
        birthday = null,
        visitCount = 1,
        totalSpentMinor = 100,
        loyaltyPoints = points,
        loyaltyProgramRevision = revision,
        loyaltyPointValueMinor = value,
        lifetimeGamingPointsEarned = points,
        gamingRank = "rookie",
        gamingRankFloor = 0,
        nextGamingRank = "player",
        nextGamingRankFloor = 200,
        pointsToNextGamingRank = 200,
        lastVisitAt = null,
        notes = null,
    )

    private fun savedAction(state: String) = LocalPointsRedemptionActionEntity(
        actionId = "captured-key",
        ownerCompanyId = "company-1",
        ownerUserId = "user-1",
        shiftId = "shift-1",
        orderId = "order-1",
        localOrderId = "local-1",
        actionKind = "generic",
        points = 100,
        rewardKey = null,
        programRevision = 4,
        expectedCheckoutVersion = 7,
        state = state,
        createdAtMillis = 1,
    )

    private fun legacyRules() = LoyaltyProgramRulesDto(
        gamingEarnPoints = 2,
        gamingEarnRupees = 10,
        minorPerPoint = 10,
        ranks = listOf(
            LoyaltyRankRuleDto("rookie", "Rookie", 0, 0),
            LoyaltyRankRuleDto("player", "Player", 200, 50),
            LoyaltyRankRuleDto("pro", "Pro", 500, 150),
            LoyaltyRankRuleDto("legend", "Legend", 1000, 300),
        ),
        rewards = listOf(
            LoyaltyRewardRuleDto("extra_controller", "Controller", "Credit", 50, 3000, "rookie", true),
            LoyaltyRewardRuleDto("snack", "Snack", "Credit", 60, 4000, "rookie", true),
            LoyaltyRewardRuleDto("ps5_30", "PS5 30", "Credit", 150, 8000, "rookie", true),
            LoyaltyRewardRuleDto("ps5_60", "PS5 60", "Credit", 220, 12000, "player", true),
            LoyaltyRewardRuleDto("squad_night", "Squad", "Credit", 350, 18000, "pro", true),
            LoyaltyRewardRuleDto("vr_legend", "VR", "Credit", 500, 25000, "legend", true),
            LoyaltyRewardRuleDto("legend_credit_1000", "Legend", "Credit", 1000, 100000, "legend", true),
        ),
    )
}
