package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.core.auth.SessionRefreshLease
import java.io.IOException
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response

/** Immutable original authority is copied to the request before Retrofit hands it to another thread. */
internal class OperationDispatchContext(
    val binding: OriginalOperationBinding,
    val permit: HandoverCoordinator.Permit?,
    val progress: OperationProgress? = null,
    val handoverBearer: String? = null,
    val handoverBody: TillHandoverBody? = null,
) : ThreadContextElement<OperationDispatchContext?>, AbstractCoroutineContextElement(Key) {
    fun asCoroutineContext(): CoroutineContext = this
    override fun updateThreadContext(context: CoroutineContext): OperationDispatchContext? = current.get().also { current.set(this) }
    override fun restoreThreadContext(context: CoroutineContext, oldState: OperationDispatchContext?) { current.set(oldState) }
    override fun toString() = "OperationDispatchContext(<redacted>)"
    companion object Key : CoroutineContext.Key<OperationDispatchContext> {
        private val current = ThreadLocal<OperationDispatchContext?>()
        fun captured(): OperationDispatchContext? = current.get()
    }
}

internal class OperationTaggedCallFactory(
    private val delegate: Call.Factory,
    private val boundRead: OriginalOperationBinding? = null,
    private val captureBootstrap: () -> SessionRefreshLease? = { null },
) : Call.Factory {
    override fun newCall(request: Request): Call {
        val inherited = OperationDispatchContext.captured()
        val support = OwnerSupportDeliveryContext.captured()
        if (inherited != null && boundRead != null) check(inherited.binding.authority == boundRead.authority &&
            inherited.binding.loginLease.session.lineage === boundRead.loginLease.session.lineage) { "This API belongs to another original caller." }
        val context = inherited ?: boundRead?.let { OperationDispatchContext(it, null) }
        val builder = request.newBuilder().tag(OperationDispatchContext::class.java, context)
            .tag(OwnerSupportDeliveryContext::class.java, support)
        if (isHandoverBootstrapRequest(request)) builder.tag(BootstrapDispatchLease::class.java, BootstrapDispatchLease(captureBootstrap()))
        return delegate.newCall(builder.build())
    }
}

internal class BootstrapDispatchLease(val lease: SessionRefreshLease?) {
    override fun toString() = "BootstrapDispatchLease(<redacted>)"
}

/** Only the two-method signed-out recovery factory attaches this marker; it sends no bearer. */
internal object AnonymousPasswordRecoveryRequest

internal class AnonymousPasswordRecoveryCallFactory(delegate: Call.Factory) : Call.Factory {
    private val anonymous = OperationTaggedCallFactory(delegate, boundRead = null, captureBootstrap = { null })
    override fun newCall(request: Request): Call {
        check(OperationDispatchContext.captured() == null && isPasswordRecoveryRoute(request)) {
            "Anonymous recovery is unavailable inside an authenticated operation."
        }
        return anonymous.newCall(request.newBuilder().removeHeader("Authorization").removeHeader("X-Terminal-Id")
            .tag(AnonymousPasswordRecoveryRequest::class.java, AnonymousPasswordRecoveryRequest).build())
    }
}

internal fun isPasswordRecoveryRoute(request: Request): Boolean = request.method == "POST" && request.url.encodedPath in setOf(
    "/api/v1/auth/password-reset/request", "/api/v1/auth/password-reset/confirm",
)

private fun isPermittedBootstrap(request: Request): Boolean = isHandoverBootstrapRequest(request) &&
    (!isPasswordRecoveryRoute(request) ||
        (request.tag(AnonymousPasswordRecoveryRequest::class.java) != null &&
            request.tag(OperationDispatchContext::class.java) == null))

internal fun isHandoverBootstrapRequest(request: Request): Boolean =
    request.method to request.url.encodedPath in setOf(
        "GET" to "/api/v1/public/client-compatibility",
        "POST" to "/api/v1/auth/login",
        "POST" to "/api/v1/auth/refresh",
        "POST" to "/api/v1/auth/password-reset/request",
        "POST" to "/api/v1/auth/password-reset/confirm",
        "GET" to "/api/v1/auth/me",
        "GET" to "/api/v1/settings/terminals",
    )

internal fun interface OperationDispatchGate {
    fun authorize(request: Request)
    fun beforeAttempt(request: Request) = authorize(request)
}

/** There is deliberately no permissive production default while Application is still restoring. */
internal object UnconfiguredOperationDispatchGate : OperationDispatchGate {
    override fun authorize(request: Request) {
        if (!isPermittedBootstrap(request) || request.tag(OperationDispatchContext::class.java) != null ||
            request.tag(OwnerSupportDeliveryContext::class.java) != null) throw IOException("Operational authority has not been restored.")
    }
}

internal class CoordinatorOperationDispatchGate(
    private val coordinator: HandoverCoordinator,
    private val startup: () -> HandoverStartupStatus,
) : OperationDispatchGate {
    override fun authorize(request: Request) {
        if (request.tag(OwnerSupportDeliveryContext::class.java) != null) throw IOException("Use the exact owner-support delivery client.")
        val original = request.tag(OperationDispatchContext::class.java)
        val purpose = original?.permit?.purpose
        if (purpose != null && purpose != OperationPurpose.ORDINARY) {
            val cleanup = requireNotNull(original)
            val permit = requireNotNull(cleanup.permit)
            val state = coordinator.state.value
            val intent = state.intent
            val action = intent?.action ?: state.preparationAction
            val path = request.url.encodedPath
            val finiteRead = request.method == "GET" && request.url.query == null && action != null &&
                path in setOf("/api/v1/auth/me", "/api/v1/pos/shifts/${action.shiftId}/operator-state")
            val allowed = when (purpose) {
                OperationPurpose.SEND_HANDOVER, OperationPurpose.READ_HANDOVER_RECEIPT ->
                    intent?.stage == HandoverStage.SENT_UNKNOWN && permit.handoverIntent == intent &&
                        matchesHandoverWire(request, purpose, intent.action, cleanup.handoverBody)
                OperationPurpose.OBSERVE_REMOTE_KEY -> permit.knownKeyObservation?.let { key ->
                    request.method == "GET" && path == key.encodedPath &&
                        request.url.encodedQuery == "installation_id=${key.installationId}"
                } == true
                OperationPurpose.OBSERVE_REMOTE_STATUS -> permit.knownStatusObservation?.let { observed ->
                    request.method == "GET" && path == observed.encodedPath &&
                        request.url.encodedQuery == "installation_id=${observed.installationId}"
                } == true
                OperationPurpose.REMOTE_HEARTBEAT -> request.method == "POST" && request.url.query == null &&
                    path == "/api/v1/remote-assistance/device/heartbeat"
                OperationPurpose.OBSERVE_WORKSPACE_EXIT -> state.workspaceExitKey == permit.existingAction &&
                    request.method == "GET" && path in setOf("/api/v1/auth/me", "/api/v1/settings/terminals",
                        "/api/v1/pos/shifts", "/api/v1/gaming/sessions")
                OperationPurpose.OBSERVE_HANDOVER_STATE -> action != null && permit.existingAction == action.key && finiteRead
                OperationPurpose.RELEASE_OWN_CHECKOUT -> action != null && (finiteRead ||
                    (request.method == "DELETE" && request.url.query == null &&
                        permit.existingAction?.let { order ->
                            runCatching { handoverUuid(order) }.isSuccess && path == "/api/v1/pos/orders/$order/checkout-claim"
                        } == true && !request.header("X-Checkout-Claim").isNullOrBlank()))
                // No production route is delegated to this older foundation capability.
                OperationPurpose.RECONCILE_OWN_ACTION -> false
                OperationPurpose.ORDINARY -> false
            }
            if (!allowed || (purpose !in setOf(OperationPurpose.OBSERVE_WORKSPACE_EXIT, OperationPurpose.REMOTE_HEARTBEAT, OperationPurpose.OBSERVE_REMOTE_KEY, OperationPurpose.OBSERVE_REMOTE_STATUS) && action?.origin?.scope != cleanup.binding.authority.scope)) {
                throw IOException("This cleanup capability cannot dispatch that request.")
            }
        }
        // Decode the entire path once to identify aliases, but authorize only the canonical wire route.
        if (request.url.toUri().path.matches(handoverWirePath) && purpose?.isHandoverWire != true) {
            throw IOException("Use the exact retained handover capability.")
        }
        if (isPermittedBootstrap(request)) {
            request.tag(OperationDispatchContext::class.java)?.let { original ->
                if (!original.binding.isCurrent()) throw IOException("The original bootstrap caller changed.")
                original.permit?.let { permit ->
                    try { runBlocking { coordinator.requireContinuation(permit, original.binding.authority) } }
                    catch (failure: Exception) { throw IOException("The original bootstrap operation changed.", failure) }
                }
            }
            return
        }
        val status = startup()
        if (status == HandoverStartupStatus.Loading || status == HandoverStartupStatus.Unavailable) {
            throw IOException("Operational authority has not been restored.")
        }
        val context = request.tag(OperationDispatchContext::class.java)
            ?: throw IOException("The original operation authority is missing.")
        if (!context.binding.isCurrent()) throw IOException("The originating cashier workspace changed.")
        val permit = context.permit
        if (permit == null) {
            // A bound read is not an operation permit or a licence to hydrate while frozen.
            if (request.method != "GET" || status != HandoverStartupStatus.Open) throw IOException("A registered operation is required.")
            return
        }
        try {
            runBlocking {
                coordinator.requireContinuation(permit, context.binding.authority)
            }
        } catch (failure: Exception) {
            throw IOException("The original operation can no longer dispatch.", failure)
        }
    }
    override fun beforeAttempt(request: Request) {
        authorize(request)
        val observed = request.tag(OperationDispatchContext::class.java)
        val handover = observed?.permit?.takeIf { it.purpose.isHandoverWire }
        if (handover != null) {
            val context = requireNotNull(observed)
            val bearer = context.handoverBearer ?: throw IOException("The original handover bearer is unavailable.")
            if (request.headers("Authorization") != listOf("Bearer $bearer") ||
                request.headers("X-Terminal-Id") != listOf(context.binding.authority.scope.terminalId)) {
                throw IOException("The original handover headers changed.")
            }
            try { runBlocking { coordinator.beforeHandoverWire(handover, context.binding.authority, context.binding.isCurrent) } }
            catch (failure: Exception) { throw IOException("The exact retained handover cannot dispatch.", failure) }
            return
        }
        if (observed?.permit?.purpose == OperationPurpose.OBSERVE_REMOTE_KEY) {
            try { runBlocking { coordinator.beforeKnownKeyRead(requireNotNull(observed.permit), observed.binding.authority) } }
            catch (failure: Exception) { throw IOException("The exact key observation already finished or changed.", failure) }
        }
        if (observed?.permit?.purpose == OperationPurpose.OBSERVE_REMOTE_STATUS) {
            val exact = requireNotNull(observed.permit.knownStatusObservation)
            if (request.header("X-ERP-Device-Key-Id") != exact.keyId) throw IOException("The captured device key changed.")
            try { runBlocking { coordinator.beforeKnownStatusRead(observed.permit, observed.binding.authority) } }
            catch (failure: Exception) { throw IOException("The exact status observation already finished or changed.", failure) }
        }
        if (!isPermittedBootstrap(request) && request.method !in setOf("GET", "HEAD")) {
            val context = requireNotNull(request.tag(OperationDispatchContext::class.java))
            try {
                runBlocking { coordinator.beforeMutationDispatch(requireNotNull(context.permit), context.binding.authority) }
                context.progress?.mutationDispatched()
            } catch (failure: Exception) { throw IOException("The original mutation cannot dispatch.", failure) }
        }
    }
}

private val handoverWirePath = Regex("/api/v1/pos/shifts/[^/]+/(operator-handovers|operator-handover-receipt)/?")

private fun matchesHandoverWire(request: Request, purpose: OperationPurpose, action: HandoverAction,
    body: TillHandoverBody?): Boolean {
    val shiftPath = "/api/v1/pos/shifts/${action.shiftId}"
    return if (purpose == OperationPurpose.SEND_HANDOVER) {
        request.method == "POST" && request.url.encodedPath == "$shiftPath/operator-handovers" &&
            request.url.encodedQuery == null && request.headers("Idempotency-Key") == listOf(action.key) &&
            body?.action == action && request.body === body
    } else {
        request.method == "GET" && request.url.encodedPath == "$shiftPath/operator-handover-receipt" &&
            request.url.encodedQuery == "action_key=${action.key}" && request.headers("Idempotency-Key").isEmpty() &&
            body == null && request.body == null
    }
}

/** Install both before connection and at each physical network attempt; request tags survive retries. */
internal class OperationDispatchInterceptor(private val gate: OperationDispatchGate, private val physicalAttempt: Boolean = false) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (physicalAttempt) gate.beforeAttempt(chain.request()) else gate.authorize(chain.request())
        return chain.proceed(chain.request())
    }
}
