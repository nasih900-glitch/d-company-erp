package cloud.dcompany.erp.ui.screens.inventory

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query
import cloud.dcompany.erp.core.net.CostingCoverage

/**
 * Inventory endpoints, declared here rather than in the shared ErpApi so this
 * feature cannot collide with anyone else's.
 *
 * Every offline create carries a stable Idempotency-Key. Ingredient and supplier
 * creates need it to recover an accepted POST whose response was lost; GRN and
 * adjustment keys additionally protect stock and money from being applied twice.
 */
interface InventoryApi {

    @GET("inventory/ingredients")
    suspend fun ingredients(@Query("branch_id") branchId: String? = null): List<Ingredient>

    @POST("inventory/ingredients")
    suspend fun createIngredient(
        @Body body: IngredientCreate,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Ingredient

    @PATCH("inventory/ingredients/{id}")
    suspend fun updateIngredient(
        @Path("id") id: String,
        @Body body: IngredientUpdate,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Ingredient

    /** Soft delete — existing batches stay in the audit trail. */
    @DELETE("inventory/ingredients/{id}")
    suspend fun deleteIngredient(
        @Path("id") id: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    @GET("inventory/suppliers")
    suspend fun suppliers(): List<Supplier>

    @POST("inventory/suppliers")
    suspend fun createSupplier(
        @Body body: SupplierBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Supplier

    @PATCH("inventory/suppliers/{id}")
    suspend fun updateSupplier(
        @Path("id") id: String,
        @Body body: SupplierBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): Supplier

    @DELETE("inventory/suppliers/{id}")
    suspend fun deleteSupplier(
        @Path("id") id: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    )

    @POST("inventory/grn")
    suspend fun postGrn(
        @Body body: GrnBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): GrnResult

    @POST("inventory/adjustments")
    suspend fun postAdjustment(
        @Body body: AdjustmentBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): AdjustmentResult

    @GET("inventory/batches")
    suspend fun batches(
        @Query("ingredient_id") ingredientId: String? = null,
        @Query("branch_id") branchId: String? = null,
    ): List<Batch>

    @GET("insights/inventory/valuation")
    suspend fun valuation(@Query("branch_id") branchId: String? = null): InventoryValuation

    @GET("insights/inventory/costing-coverage")
    suspend fun costingCoverage(): CostingCoverage

    @GET("inventory/recipes")
    suspend fun recipes(@Query("menu_item_id") menuItemId: String): List<Recipe>

    @POST("inventory/recipes")
    suspend fun createRecipe(@Body body: RecipeCreateBody): Recipe

    @POST("inventory/recipes/{recipe_id}/lines")
    suspend fun addRecipeLine(
        @Path("recipe_id") recipeId: String,
        @Body body: RecipeLineBody,
    ): RecipeLine

    @PATCH("inventory/recipes/{recipe_id}/lines/{line_id}")
    suspend fun updateRecipeLine(
        @Path("recipe_id") recipeId: String,
        @Path("line_id") lineId: String,
        @Body body: RecipeLineUpdateBody,
    ): RecipeLine

    @DELETE("inventory/recipes/{recipe_id}/lines/{line_id}")
    suspend fun deleteRecipeLine(
        @Path("recipe_id") recipeId: String,
        @Path("line_id") lineId: String,
    )

    @DELETE("inventory/recipes/{recipe_id}")
    suspend fun deleteRecipe(@Path("recipe_id") recipeId: String)

    /** Both GRN and adjustments are branch-scoped, so the picker needs this. */
    @GET("inventory/branches")
    suspend fun branches(): List<Branch>
}
