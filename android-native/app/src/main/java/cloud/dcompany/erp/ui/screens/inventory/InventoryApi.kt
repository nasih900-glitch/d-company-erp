package cloud.dcompany.erp.ui.screens.inventory

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Inventory endpoints, declared here rather than in the shared ErpApi so this
 * feature cannot collide with anyone else's.
 *
 * GRN and adjustments carry an Idempotency-Key because both move money: a GRN
 * creates costed batches and rewrites the ingredient's average cost, and an
 * adjustment writes off stock at batch cost. A receipt posted twice because a
 * tablet lost the reply mid-request would inflate both stock and purchase spend
 * with no trace that it was the same delivery.
 */
interface InventoryApi {

    @GET("inventory/ingredients")
    suspend fun ingredients(): List<Ingredient>

    @POST("inventory/ingredients")
    suspend fun createIngredient(@Body body: IngredientCreate): Ingredient

    @PATCH("inventory/ingredients/{id}")
    suspend fun updateIngredient(
        @Path("id") id: String,
        @Body body: IngredientUpdate,
    ): Ingredient

    /** Soft delete — existing batches stay in the audit trail. */
    @DELETE("inventory/ingredients/{id}")
    suspend fun deleteIngredient(@Path("id") id: String)

    @GET("inventory/suppliers")
    suspend fun suppliers(): List<Supplier>

    @POST("inventory/suppliers")
    suspend fun createSupplier(@Body body: SupplierBody): Supplier

    @PATCH("inventory/suppliers/{id}")
    suspend fun updateSupplier(
        @Path("id") id: String,
        @Body body: SupplierBody,
    ): Supplier

    @DELETE("inventory/suppliers/{id}")
    suspend fun deleteSupplier(@Path("id") id: String)

    @POST("inventory/grn")
    suspend fun postGrn(
        @Body body: GrnBody,
        @Header("Idempotency-Key") key: String,
    ): GrnResult

    @POST("inventory/adjustments")
    suspend fun postAdjustment(
        @Body body: AdjustmentBody,
        @Header("Idempotency-Key") key: String,
    ): AdjustmentResult

    @GET("inventory/batches")
    suspend fun batches(@Query("ingredient_id") ingredientId: String? = null): List<Batch>

    /** Both GRN and adjustments are branch-scoped, so the picker needs this. */
    @GET("settings/branches")
    suspend fun branches(): List<Branch>
}
