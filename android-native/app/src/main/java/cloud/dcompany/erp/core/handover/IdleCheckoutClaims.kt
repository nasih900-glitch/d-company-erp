package cloud.dcompany.erp.core.handover

/** Only known idle claim capabilities; RAM only. Unknown acquires still require fresh server absence. */
internal class IdleCheckoutClaims {
    internal class Handle(val scope: HandoverScope, val orderId: String, val token: String, val abandoned: Boolean = false) {
        override fun toString() = "IdleCheckoutClaim(<redacted>)"
    }
    private val retained = linkedMapOf<Pair<HandoverScope, String>, Handle>()
    @Synchronized fun remember(binding: OriginalOperationBinding, orderId: String, token: String) {
        check(binding.isCurrent()); handoverUuid(orderId)
        require(token.isNotBlank() && token.length <= 2048 && token.none { it.isISOControl() })
        retained[binding.authority.scope to orderId] = Handle(binding.authority.scope, orderId, token)
    }
    @Synchronized fun markAbandoned(scope: HandoverScope, orderId: String, token: String) {
        retained[scope to orderId]?.takeIf { it.token == token }?.let {
            if (!it.abandoned) retained[scope to orderId] = Handle(scope, orderId, token, abandoned = true)
        }
    }
    @Synchronized fun known(scope: HandoverScope, orderId: String): Handle? = retained[scope to orderId]
    @Synchronized fun isCurrent(handle: Handle): Boolean = retained[handle.scope to handle.orderId] === handle
    @Synchronized fun released(handle: Handle) { if (isCurrent(handle)) retained.remove(handle.scope to handle.orderId) }
    @Synchronized fun forgetExact(scope: HandoverScope, orderId: String, token: String) {
        retained[scope to orderId]?.takeIf { it.token == token }?.let(::released)
    }
    @Synchronized fun snapshot(scope: HandoverScope): List<Handle> = retained.values.filter { it.scope == scope }
    /** Only handles observed before the server read may retire; late/renewed handles stay known. */
    @Synchronized fun observeAbsent(scope: HandoverScope, ownOrderIds: Set<String>, observed: List<Handle>) {
        require(observed.all { it.scope == scope })
        observed.filter { it.orderId !in ownOrderIds }.forEach(::released)
    }
}
