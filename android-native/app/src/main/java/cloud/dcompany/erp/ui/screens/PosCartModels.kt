package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.db.LocalModifierSelectionSnapshot
import cloud.dcompany.erp.core.db.LocalOrderLineEntity
import cloud.dcompany.erp.core.db.MenuItemEntity
import cloud.dcompany.erp.core.db.MenuModifierEntity
import cloud.dcompany.erp.core.db.MenuModifierGroupEntity
import cloud.dcompany.erp.core.db.MenuVariantEntity
import cloud.dcompany.erp.core.db.decodeModifierSelections
import cloud.dcompany.erp.core.db.encodeModifierSelections
import java.util.UUID

data class CartModifierSelection(
    val modifier: MenuModifierEntity,
    val qty: Int,
)

/**
 * A cart line has its own stable identity. Two coffees with different milk,
 * preparation notes, or variants must never collapse into one quantity row.
 */
data class CartLine(
    val lineId: String,
    val item: MenuItemEntity,
    val qty: Int,
    val variant: MenuVariantEntity? = null,
    val modifiers: List<CartModifierSelection> = emptyList(),
    val note: String? = null,
    /** Immutable price shown when this line was added; server revalidates before online payment. */
    val unitPriceMinor: Long = configuredUnitPriceMinor(item, variant, modifiers),
) {
    val lineTotalMinor: Long get() = unitPriceMinor * qty
}

internal fun configuredUnitPriceMinor(
    item: MenuItemEntity,
    variant: MenuVariantEntity?,
    modifiers: List<CartModifierSelection>,
): Long = item.basePriceMinor +
    (variant?.priceDeltaMinor ?: 0L) +
    modifiers.sumOf { selection -> selection.modifier.priceDeltaMinor * selection.qty }

internal fun newCartLine(
    item: MenuItemEntity,
    variant: MenuVariantEntity? = null,
    modifiers: List<CartModifierSelection> = emptyList(),
    note: String? = null,
): CartLine = CartLine(
    lineId = UUID.randomUUID().toString(),
    item = item,
    qty = 1,
    variant = variant,
    modifiers = modifiers.sortedBy { it.modifier.id },
    note = note?.trim()?.takeIf(String::isNotEmpty),
)

internal fun CartLine.toLocalOrderLine(orderLocalId: String): LocalOrderLineEntity =
    LocalOrderLineEntity(
        orderLocalId = orderLocalId,
        clientLineId = lineId,
        menuItemId = item.id,
        name = item.name,
        qty = qty,
        variantId = variant?.id,
        variantName = variant?.name,
        variantPriceDeltaMinor = variant?.priceDeltaMinor ?: 0L,
        modifierSelectionsJson = encodeModifierSelections(
            modifiers.map { selection ->
                LocalModifierSelectionSnapshot(
                    modifierId = selection.modifier.id,
                    modifierGroupId = selection.modifier.modifierGroupId,
                    name = selection.modifier.name,
                    priceDeltaMinor = selection.modifier.priceDeltaMinor,
                    qty = selection.qty,
                )
            },
        ),
        note = note,
        unitPriceMinor = unitPriceMinor,
    )

internal fun LocalOrderLineEntity.toCartLine(
    currentItems: Map<String, MenuItemEntity>,
    currentVariants: Map<String, MenuVariantEntity>,
    currentModifiers: Map<String, MenuModifierEntity>,
): CartLine {
    val modifierSnapshots = decodeModifierSelections(modifierSelectionsJson)
    val modifierRows = modifierSnapshots.map { snapshot ->
        val current = currentModifiers[snapshot.modifierId]
        CartModifierSelection(
            modifier = current ?: MenuModifierEntity(
                id = snapshot.modifierId,
                menuItemId = menuItemId,
                modifierGroupId = snapshot.modifierGroupId ?: "saved-modifier-group",
                name = snapshot.name,
                priceDeltaMinor = snapshot.priceDeltaMinor,
                maxQuantity = snapshot.qty.coerceAtLeast(1),
                sortOrder = 0,
                isActive = false,
            ),
            qty = snapshot.qty,
        )
    }
    val currentVariant = variantId?.let(currentVariants::get)
    val restoredVariant = currentVariant ?: variantId?.let { id ->
        MenuVariantEntity(
            id = id,
            menuItemId = menuItemId,
            name = variantName ?: "Saved option",
            priceDeltaMinor = variantPriceDeltaMinor,
            sortOrder = 0,
            isActive = false,
        )
    }
    val optionDelta = variantPriceDeltaMinor +
        modifierSnapshots.sumOf { it.priceDeltaMinor * it.qty }
    val restoredItem = currentItems[menuItemId] ?: MenuItemEntity(
        id = menuItemId,
        categoryId = "",
        sku = "",
        name = name,
        type = "food",
        basePriceMinor = (unitPriceMinor - optionDelta).coerceAtLeast(0L),
        taxRate = 0.0,
        hsnCode = null,
        priceIncludesTax = true,
        isAvailable = false,
        description = null,
    )
    return CartLine(
        lineId = clientLineId ?: "legacy-$rowId",
        item = restoredItem.copy(name = name),
        qty = qty,
        variant = restoredVariant,
        modifiers = modifierRows,
        note = note,
        unitPriceMinor = unitPriceMinor,
    )
}

/** Offline collection is blocked if any mutable menu option no longer matches the saved snapshot. */
internal fun cartPricingReviewReason(
    cart: List<CartLine>,
    currentItems: Map<String, MenuItemEntity>,
    currentVariants: Map<String, MenuVariantEntity>,
    currentModifierGroups: Map<String, MenuModifierGroupEntity>,
    currentModifiers: Map<String, MenuModifierEntity>,
): String? {
    for (line in cart) {
        val item = currentItems[line.item.id]
            ?: return "${line.item.name} is no longer in the current menu. Remove it before collecting offline."
        if (!item.isAvailable) {
            return "${item.name} is unavailable. Remove it before collecting offline."
        }
        val variant = line.variant?.let { selected ->
            currentVariants[selected.id]
                ?: return "${item.name}'s saved variant is no longer available. Review the line."
        }
        if (variant != null && !variant.isActive) {
            return "${item.name}'s saved variant is no longer active. Review the line."
        }
        val modifiers = line.modifiers.map { selected ->
            val current = currentModifiers[selected.modifier.id]
                ?: return "${item.name}'s saved modifier is no longer available. Review the line."
            if (!current.isActive || selected.qty !in 1..current.maxQuantity) {
                return "${item.name}'s modifier quantity is no longer allowed. Review the line."
            }
            CartModifierSelection(current, selected.qty)
        }
        val selectedByGroup = modifiers.groupingBy { it.modifier.modifierGroupId }
            .fold(0) { count, selection -> count + selection.qty }
        val activeGroups = currentModifierGroups.values.filter {
            it.menuItemId == item.id && it.isActive
        }
        for (group in activeGroups) {
            val count = selectedByGroup[group.id] ?: 0
            if (count !in group.minSelect..group.maxSelect) {
                return "${item.name}'s ${group.name} choices changed. Review the line."
            }
        }
        if (configuredUnitPriceMinor(item, variant, modifiers) != line.unitPriceMinor) {
            return "${item.name}'s price or options changed. Re-add the line before collecting offline."
        }
    }
    return null
}
