package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.OutboxOwnerIdentity
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import okhttp3.Request

/** No cache/terminal authority. It may use only the original employee's durable support queue. */
internal class OwnerSupportDeliveryContext(
    val permit: HandoverCoordinator.OwnerSupportPermit,
    val owner: OutboxOwnerIdentity,
    val originalLoginIsCurrent: () -> Boolean,
) : ThreadContextElement<OwnerSupportDeliveryContext?>, AbstractCoroutineContextElement(Key) {
    init { require(permit.companyId == owner.companyId && permit.userId == owner.userId) }
    override fun updateThreadContext(context: CoroutineContext) = current.get().also { current.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: OwnerSupportDeliveryContext?) { current.set(oldState) }
    val gate = object : OperationDispatchGate {
        override fun authorize(request: Request) {
            val path = request.url.encodedPath
            val allowed = request.method == "POST" && request.url.query == null &&
                (path == "/api/v1/bug-reports" || path.matches(Regex("/api/v1/bug-reports/mine/[a-zA-Z0-9-]+/attachments"))) &&
                !request.header("Idempotency-Key").isNullOrBlank()
            if (!allowed || request.tag(OperationDispatchContext::class.java) != null) throw IOException("This permit only delivers the original employee's support queue.")
            try { runBlocking { permit.owner.requireOwnerSupport(permit, originalLoginIsCurrent) } }
            catch (_: Exception) { throw IOException("The original support delivery authority changed.") }
        }
        override fun beforeAttempt(request: Request) {
            authorize(request)
            val bearer = request.header("Authorization")?.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ")
            if (bearer?.let(cloud.dcompany.erp.core.auth.AccessTokenIdentityParser::parse) != owner ||
                request.header("X-Terminal-Id") != null) throw IOException("The original support request owner changed.")
        }
    }
    suspend fun commitOwnedRow(write: suspend () -> Unit) {
        permit.owner.commitOwnerSupport(permit, originalLoginIsCurrent, write)
    }
    override fun toString() = "OwnerSupportDeliveryContext(<redacted>)"
    companion object Key : CoroutineContext.Key<OwnerSupportDeliveryContext> {
        private val current = ThreadLocal<OwnerSupportDeliveryContext?>()
        fun captured(): OwnerSupportDeliveryContext? = current.get()
    }
}
