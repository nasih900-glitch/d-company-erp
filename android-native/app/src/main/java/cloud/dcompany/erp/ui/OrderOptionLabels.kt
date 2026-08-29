package cloud.dcompany.erp.ui

import cloud.dcompany.erp.core.net.OrderModifierSnapshot
import cloud.dcompany.erp.core.net.OrderVariantSnapshot

/** Compact, price-free option detail shared by Tables and Kitchen. */
internal fun orderOptionLabels(
    variant: OrderVariantSnapshot?,
    modifiers: List<OrderModifierSnapshot>,
): List<String> = buildList {
    variant?.name?.trim()?.takeIf(String::isNotEmpty)?.let(::add)
    modifiers.forEach { modifier ->
        modifier.name.trim().takeIf(String::isNotEmpty)?.let { name ->
            add(if (modifier.qty > 1) "${modifier.qty}× $name" else name)
        }
    }
}
