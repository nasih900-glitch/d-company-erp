package cloud.dcompany.erp.core.auth

/** Exact owner tiers belong only in an authorized management response. */
fun roleDisplayLabel(code: String, exactOwnerAccess: Boolean = false): String = when (code) {
    "super_owner" -> if (exactOwnerAccess) "Owner (protected audit)" else "Owner"
    "co_owner" -> if (exactOwnerAccess) "Owner (operational override)" else "Owner"
    "owner" -> "Owner"
    "manager" -> "Manager"
    "gaming_cashier" -> "Gaming & POS staff (legacy)"
    "store_staff" -> "Store Staff"
    "auditor" -> "Business Viewer"
    "partner" -> "Partner (legacy, configurable)"
    "pos_discount" -> "Manual POS discounts"
    else -> code.replace('_', ' ').replaceFirstChar(Char::uppercase)
}

fun rolePurposeSummary(code: String): String = when (code) {
    "owner" -> "Business operations, finance, assets and payroll; standard shift accountability. Protected audit access is separate."
    "co_owner" -> "Full operational override access, including finance and payroll; no protected audit or Access Control."
    "super_owner" -> "Protected audit and Access Control, with full operational override access. Cannot be reassigned from Staff."
    "manager" -> "Broad daily management: POS refunds and voids, inventory, gaming, finance writes and staff management. No payroll access by default."
    "store_staff" -> "POS sales and manual discounts, Gaming sessions and eligible voids before POS, shop shifts and attendance. Gaming voids require a reason from 30 minutes. No POS voids/refunds, finance, payroll or staff administration."
    "gaming_cashier" -> "Legacy role: POS sales and voids, gaming sessions, shop shifts and attendance. No business management; manual discounts require a separate Owner grant."
    "auditor" -> "Read-only business, finance, staff and operational records, with report exports. No writes, refunds, voids or shift actions."
    "partner" -> "Legacy business partner role. Access is configurable, not guaranteed read-only. Use Business Viewer for immutable read-only access."
    "cashier" -> "Legacy POS sales, table orders and shift operations. Access can be configured."
    "kitchen" -> "Legacy kitchen display and ticket updates. Access can be configured."
    "gaming_supervisor" -> "Legacy gaming sessions, bookings and tournaments. Access can be configured."
    "staff" -> "Legacy service staff: table orders, kitchen progress and attendance. Access can be configured."
    else -> "Existing role. Review effective permissions in Access Control."
}
