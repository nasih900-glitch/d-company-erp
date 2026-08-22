package cloud.dcompany.erp.ui.screens.memberships

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Tier create/edit stays web-only (Settings → Memberships already has a
 * working UI) — this screen only ever reads tiers, never writes them.
 *
 * Subscribe carries an Idempotency-Key: starts_at/expires_at are computed
 * server-side fresh on every call, and the overlap check guards against a
 * *second* real subscription for the same customer, not a retry of the
 * same one — a retry without a stable key would silently mint a duplicate
 * membership term, same reasoning as Events' ticket sales. Cancel carries
 * none: it mutates one existing row by id and a retry against an
 * already-cancelled subscription just 4xxs, it never double-effects, same
 * as Events' check-in.
 *
 * Both writes are gated server-side by `tenant.protected_access`
 * (super_owner/co_owner only) — not by the broader `admin.system`
 * permission every role but auditor has — but this app's established
 * doctrine is to show every write affordance optimistically and let a 403
 * land as a rejected outbox row with the server's own message, same as
 * every other screen in this rebuild; no special client-side gating here.
 */
interface MembershipsApi {

    @GET("memberships/tiers")
    suspend fun listTiers(): List<MembershipTier>

    @POST("memberships/subscribe")
    suspend fun subscribe(
        @Body body: SubscribeRequest,
        @Header("Idempotency-Key") key: String,
    ): Subscription

    /** Returns the customer's current *active* subscription, or a literal
     * JSON null if none — never a 404. */
    @GET("memberships/customer/{customer_id}")
    suspend fun getCustomerSubscription(@Path("customer_id") customerId: String): Subscription?

    @POST("memberships/{subscription_id}/cancel")
    suspend fun cancel(@Path("subscription_id") subscriptionId: String): Subscription
}
