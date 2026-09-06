package cloud.dcompany.erp.ui.remote

import cloud.dcompany.erp.core.remote.RemoteErpModule
import cloud.dcompany.erp.ui.Destination

internal fun Destination.remoteModule(): RemoteErpModule? = when (this) {
    Destination.Help -> RemoteErpModule.HELP
    else -> null
}

internal fun RemoteErpModule.destination(): Destination = when (this) {
    RemoteErpModule.HELP -> Destination.Help
}

/** Every destination is named explicitly; capture policy separately admits only audited routes. */
internal fun Destination.remoteRouteKey(): String = when (this) {
    Destination.Dashboard -> "dashboard"
    Destination.Pos -> "pos"
    Destination.Gaming -> "gaming"
    Destination.Tables -> "tables"
    Destination.Reservations -> "reservations"
    Destination.Kitchen -> "kitchen"
    Destination.Shift -> "shift"
    Destination.Customers -> "customers"
    Destination.Menu -> "products"
    Destination.Staff -> "staff"
    Destination.Inventory -> "stock"
    Destination.Reports -> "reports"
    Destination.Analytics -> "analytics"
    Destination.Finance -> "finance"
    Destination.Events -> "events"
    Destination.Memberships -> "memberships"
    Destination.Refunds -> "refunds"
    Destination.AuditLog -> "audit_log"
    Destination.AccessControl -> "access_control"
    Destination.Settings -> "settings"
    Destination.SupportInbox -> "support_inbox"
    Destination.Help -> "help"
}
