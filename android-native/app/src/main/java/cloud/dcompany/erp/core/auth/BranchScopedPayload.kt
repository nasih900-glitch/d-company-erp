package cloud.dcompany.erp.core.auth

/**
 * A financial/operational aggregate may enter the visible cache only when the
 * server echoes the same branch captured in the active cache-scope lease.
 * Server authorization remains the primary control; this is the native
 * fail-closed boundary against a stale token, proxy/cache mix-up, or backend
 * regression returning another branch's figures under a valid response.
 */
class BranchScopeMismatchException(message: String) : Exception(message)

internal fun verifyBranchScopedPayload(
    expectedBranchId: String?,
    payloadBranchId: String?,
    label: String,
) {
    val expected = expectedBranchId?.trim()?.takeIf(String::isNotEmpty)
        ?: throw BranchScopeMismatchException(
            "No active branch is verified for this tablet. Sign out and sign in again.",
        )
    val actual = payloadBranchId?.trim()?.takeIf(String::isNotEmpty)
    if (actual != expected) {
        throw BranchScopeMismatchException(
            "The $label did not match this tablet's active branch. " +
                "Sign out and sign in again before using these figures.",
        )
    }
}
