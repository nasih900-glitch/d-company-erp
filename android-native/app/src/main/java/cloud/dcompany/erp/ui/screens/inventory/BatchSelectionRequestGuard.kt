package cloud.dcompany.erp.ui.screens.inventory

/** Identity guard for cancellable, rapidly changing batch-detail requests. */
internal data class BatchSelectionRequest(
    val ingredientId: String?,
    val branchId: String?,
    val generation: Long,
)

internal class BatchSelectionRequestGuard {
    private var generation = 0L
    private var ingredientId: String? = null
    private var branchId: String? = null

    @Synchronized
    fun begin(nextIngredientId: String?, nextBranchId: String?): BatchSelectionRequest {
        generation += 1
        ingredientId = nextIngredientId
        branchId = nextBranchId
        return BatchSelectionRequest(nextIngredientId, nextBranchId, generation)
    }

    @Synchronized
    fun isCurrent(request: BatchSelectionRequest): Boolean =
        request.generation == generation && request.ingredientId == ingredientId &&
            request.branchId == branchId
}
