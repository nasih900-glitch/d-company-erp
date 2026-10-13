package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.net.CustomerRewardEligibility
import cloud.dcompany.erp.ui.screens.settings.LoyaltyProgramRulesDto

data class EligibleRewardCatalogState(
    val phone: String? = null,
    val programRevision: Long? = null,
    val rewards: List<CustomerRewardEligibility> = emptyList(),
    val loading: Boolean = false,
    val verified: Boolean = false,
    val error: String? = null,
)

data class RewardEligibilityChoice(
    val key: String,
    val label: String,
    val description: String,
    val pointsCost: Int,
    val valueMinor: Long,
    val eligible: Boolean,
    val affordable: Boolean,
    val unavailableReason: String? = null,
)

/** The server catalog is the authority for stable-rank eligibility. Program
 * rules contribute only the complete enabled list and locked-rank wording;
 * they never turn a missing server eligibility row into an actionable reward. */
internal fun rewardEligibilityChoices(
    rules: LoyaltyProgramRulesDto,
    revision: Long,
    phone: String,
    catalog: EligibleRewardCatalogState,
): List<RewardEligibilityChoice> {
    if (
        !catalog.verified || catalog.programRevision != revision ||
        catalog.phone != phone
    ) return emptyList()
    val eligibleByKey = catalog.rewards
        .filter { it.programRevision == revision }
        .associateBy(CustomerRewardEligibility::key)
    val rankLabels = rules.ranks.associate { it.key to it.label }
    return rules.rewards.filter { it.enabled }.map { rule ->
        val eligible = eligibleByKey[rule.key]
        when {
            eligible == null -> RewardEligibilityChoice(
                key = rule.key,
                label = rule.label,
                description = rule.description,
                pointsCost = rule.pointsCost,
                valueMinor = rule.valueMinor,
                eligible = false,
                affordable = false,
                unavailableReason = "Unlocks at ${rankLabels[rule.minRankKey] ?: "the required"} rank",
            )
            !eligible.affordable -> RewardEligibilityChoice(
                key = eligible.key,
                label = eligible.name,
                description = eligible.description,
                pointsCost = eligible.pointsCost,
                valueMinor = eligible.valueMinor,
                eligible = true,
                affordable = false,
                unavailableReason = "Current server balance is not enough · refresh before redeeming",
            )
            else -> RewardEligibilityChoice(
                key = eligible.key,
                label = eligible.name,
                description = eligible.description,
                pointsCost = eligible.pointsCost,
                valueMinor = eligible.valueMinor,
                eligible = true,
                affordable = true,
            )
        }
    }
}
