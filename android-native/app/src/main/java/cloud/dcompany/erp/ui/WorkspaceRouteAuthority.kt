package cloud.dcompany.erp.ui

internal data class WorkspaceRouteSnapshot(
    val destination: Destination,
    val revision: Long,
)

internal enum class WorkspaceRouteRejection {
    STALE_REVISION,
    OUTSIDE_CURRENT_SCOPE,
    SAME_DESTINATION,
}

internal sealed interface WorkspaceRouteRequestResult {
    data class Accepted(val snapshot: WorkspaceRouteSnapshot) : WorkspaceRouteRequestResult

    data class Rejected(
        val reason: WorkspaceRouteRejection,
        val current: WorkspaceRouteSnapshot,
    ) : WorkspaceRouteRequestResult
}

/**
 * Direct route authority used by the live rail. The rendered destination remains a presentation
 * input; gesture decisions read this owner synchronously so delayed composition cannot approve or
 * reject a route change.
 */
internal interface ExecutiveRailRouteAuthority {
    fun snapshot(): WorkspaceRouteSnapshot

    fun request(
        target: Destination,
        expected: WorkspaceRouteSnapshot,
        beforeCommit: () -> Unit = {},
        afterCommit: () -> Unit = {},
    ): WorkspaceRouteRequestResult
}

/**
 * User-scoped in-memory revision owner around the existing saveable destination state.
 * [replace] is for already-validated external navigation sources. [request] additionally requires
 * an exact snapshot, making destination-equal ABA replacements observable to an active gesture.
 */
internal class MutableWorkspaceRouteAuthority(
    private val readDestination: () -> Destination,
    private val writeDestination: (Destination) -> Unit,
    private val allowedDestinations: () -> List<Destination>,
) : ExecutiveRailRouteAuthority {
    private var revision = 0L

    @Synchronized
    override fun snapshot(): WorkspaceRouteSnapshot = currentSnapshot()

    @Synchronized
    override fun request(
        target: Destination,
        expected: WorkspaceRouteSnapshot,
        beforeCommit: () -> Unit,
        afterCommit: () -> Unit,
    ): WorkspaceRouteRequestResult {
        val current = currentSnapshot()
        if (current != expected) {
            return WorkspaceRouteRequestResult.Rejected(
                reason = WorkspaceRouteRejection.STALE_REVISION,
                current = current,
            )
        }
        return replaceLocked(
            target = target,
            current = current,
            beforeCommit = beforeCommit,
            afterCommit = afterCommit,
            runEffectsForSameDestination = false,
        )
    }

    @Synchronized
    fun replace(
        target: Destination,
        beforeCommit: () -> Unit = {},
        afterCommit: () -> Unit = {},
    ): WorkspaceRouteRequestResult = replaceLocked(
        target = target,
        current = currentSnapshot(),
        beforeCommit = beforeCommit,
        afterCommit = afterCommit,
        runEffectsForSameDestination = true,
    )

    private fun replaceLocked(
        target: Destination,
        current: WorkspaceRouteSnapshot,
        beforeCommit: () -> Unit,
        afterCommit: () -> Unit,
        runEffectsForSameDestination: Boolean,
    ): WorkspaceRouteRequestResult {
        val allowed = allowedDestinations()
        if (target !in allowed) {
            return WorkspaceRouteRequestResult.Rejected(
                reason = WorkspaceRouteRejection.OUTSIDE_CURRENT_SCOPE,
                current = current,
            )
        }
        val rawDestination = readDestination()
        if (rawDestination == target) {
            if (runEffectsForSameDestination) {
                beforeCommit()
                afterCommit()
            }
            return WorkspaceRouteRequestResult.Rejected(
                reason = WorkspaceRouteRejection.SAME_DESTINATION,
                current = current,
            )
        }
        beforeCommit()
        writeDestination(target)
        revision += 1L
        val accepted = WorkspaceRouteRequestResult.Accepted(
            WorkspaceRouteSnapshot(destination = target, revision = revision),
        )
        afterCommit()
        return accepted
    }

    private fun currentSnapshot(): WorkspaceRouteSnapshot = WorkspaceRouteSnapshot(
        destination = resolveWorkspaceDestination(readDestination(), allowedDestinations()),
        revision = revision,
    )
}
