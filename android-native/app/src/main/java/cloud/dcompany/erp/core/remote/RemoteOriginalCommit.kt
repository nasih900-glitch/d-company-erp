package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.auth.SessionRefreshLease
import cloud.dcompany.erp.core.handover.OriginalOperationBinding

/** The non-suspending original-login/lifecycle boundary; callbacks must not acquire the cache mutex. */
internal class RemoteOriginalCommit(
    private val lifecycle: Any,
    private val commitLogin: (SessionRefreshLease?, () -> Unit) -> Boolean,
    private val originalViewCurrent: (OriginalOperationBinding) -> Boolean,
) {
    fun <T> withOriginal(original: OriginalOperationBinding, publish: () -> T): T? {
        var result: T? = null
        commitLogin(original.loginLease) {
            synchronized(lifecycle) {
                if (originalViewCurrent(original)) result = publish()
            }
        }
        return result
    }
    fun withLoginSnapshot(login: SessionRefreshLease?, cleanup: () -> Unit): Boolean =
        commitLogin(login) { synchronized(lifecycle) { cleanup() } }
}
