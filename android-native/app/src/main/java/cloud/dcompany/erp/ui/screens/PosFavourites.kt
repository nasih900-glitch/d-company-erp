package cloud.dcompany.erp.ui.screens

import android.content.Context
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.ValidatedTerminalDisplay
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.net.MeResponse

internal data class PosFavouritesScope(val companyId: String, val branchId: String) {
    val preferenceKey: String get() = "${companyId.length}:$companyId$branchId"
}

internal fun posFavouritesScope(
    profile: MeResponse,
    lease: CacheScopeLease?,
    terminal: ValidatedTerminalDisplay?,
): PosFavouritesScope? {
    val scope = lease?.scope ?: return null
    val profileBranchId = profile.branchId?.trim()?.takeIf(String::isNotEmpty)
    if (scope.userId != profile.userId.trim() || scope.companyId != profile.companyId.trim() ||
        scope.branchId != profileBranchId || scope.terminalId == null
    ) return null

    val branchId = scope.branchId ?: terminal?.takeIf { it.terminalId == scope.terminalId }
        ?.branchId?.takeIf(String::isNotBlank) ?: return null
    return PosFavouritesScope(scope.companyId, branchId)
}

internal class PosFavouritesStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        "dcompany_pos_favourites",
        Context.MODE_PRIVATE,
    )

    fun load(scope: PosFavouritesScope): Set<String> =
        preferences.getStringSet(scope.preferenceKey, emptySet()).orEmpty().toSet()

    fun toggle(scope: PosFavouritesScope, itemId: String): Set<String> {
        val ids = load(scope)
        val updated = if (itemId in ids) ids - itemId else ids + itemId
        preferences.edit().putStringSet(scope.preferenceKey, updated).apply()
        return updated
    }
}

internal fun prioritisePosFavourites(
    items: List<MenuItemEntity>,
    selectedCategoryId: String?,
    favouriteIds: Set<String>,
): List<MenuItemEntity> {
    if (selectedCategoryId != null || favouriteIds.isEmpty()) return items
    val (favourites, otherItems) = items.partition { it.id in favouriteIds }
    return if (favourites.isEmpty()) items else favourites + otherItems
}
