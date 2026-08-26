package cloud.dcompany.erp.ui.screens.menu

import cloud.dcompany.erp.core.net.MenuCategory
import cloud.dcompany.erp.core.net.MenuItem
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.HeaderMap
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path

/** Mirrors CategoryCreate in backend/app/api/v1/menu/router.py. */
@Serializable
data class CategoryCreateBody(val name: String, @SerialName("sort_order") val sortOrder: Int = 0)

/** Mirrors CategoryUpdate. No Idempotency-Key sent — an absolute-PATCH is replay-safe by construction (same reasoning as Customers). */
@Serializable
data class CategoryUpdateBody(val name: String? = null, @SerialName("sort_order") val sortOrder: Int? = null)

/**
 * Mirrors the non-price subset of ItemUpdate — deliberately excludes
 * base_price_minor/tax_rate/hsn_code/price_includes_tax, which are always
 * sent via [ItemPricingUpdateBody] instead (see [LocalMenuItemEntity]'s doc
 * comment for why the split exists). No Idempotency-Key: absolute-PATCH,
 * replay-safe by construction.
 */
@Serializable
data class ItemDetailsUpdateBody(
    @SerialName("category_id") val categoryId: String? = null,
    val name: String? = null,
    val description: String? = null,
    @SerialName("is_available") val isAvailable: Boolean? = null,
)

/** Mirrors ItemCreate. Always sent online, immediately — creating an item
 * requires a live pricing-unlock token, so it can never be queued offline. */
@Serializable
data class ItemCreateBody(
    @SerialName("category_id") val categoryId: String,
    val sku: String,
    val name: String,
    val type: String,
    @SerialName("base_price_minor") val basePriceMinor: Long,
    @SerialName("tax_rate") val taxRate: Double = 0.0,
    @SerialName("hsn_code") val hsnCode: String? = null,
    @SerialName("price_includes_tax") val priceIncludesTax: Boolean = true,
    val description: String? = null,
)

/** The price-only subset of ItemUpdate — always sent online, immediately,
 * same online-only reasoning as create. No Idempotency-Key: absolute-PATCH. */
@Serializable
data class ItemPricingUpdateBody(
    @SerialName("base_price_minor") val basePriceMinor: Long? = null,
    @SerialName("tax_rate") val taxRate: Double? = null,
    @SerialName("hsn_code") val hsnCode: String? = null,
    @SerialName("price_includes_tax") val priceIncludesTax: Boolean? = null,
)

interface MenuApi {

    @POST("menu/categories")
    suspend fun createCategory(
        @Body body: CategoryCreateBody,
        @Header("Idempotency-Key") key: String,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MenuCategory

    @PATCH("menu/categories/{id}")
    suspend fun updateCategory(
        @Path("id") id: String,
        @Body body: CategoryUpdateBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MenuCategory

    @PATCH("menu/items/{id}")
    suspend fun updateItemDetails(
        @Path("id") id: String,
        @Body body: ItemDetailsUpdateBody,
        @HeaderMap provenance: Map<String, String> = emptyMap(),
    ): MenuItem

    /** Requires a live X-Pricing-Token — attached automatically by
     * ApiClient's PricingTokenInterceptor whenever PricingLock holds one. */
    @POST("menu/items")
    suspend fun createItem(
        @Body body: ItemCreateBody,
        @Header("Idempotency-Key") key: String,
    ): MenuItem

    /** Requires a live X-Pricing-Token, same as [createItem]. */
    @PATCH("menu/items/{id}")
    suspend fun updateItemPricing(
        @Path("id") id: String,
        @Body body: ItemPricingUpdateBody,
    ): MenuItem
}
