package cloud.dcompany.erp.core.remote

import cloud.dcompany.erp.core.auth.*
import cloud.dcompany.erp.core.handover.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

/** Actual production guard/helper; the auth monitor substitutes only TokenStore's Android-backed primitive. */
class RemoteSharingLockOrderTest {
    private fun origin() = OriginalOperationBinding(HandoverAuthority(HandoverScope(
        "11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", null, null), 1),
        CacheScopeLease(CacheScope("11111111-1111-4111-8111-111111111111", "22222222-2222-4222-8222-222222222222", null, null), 1),
        SessionRefreshLease(StoredSession("synthetic", "synthetic"), 1)) { true }
    private fun thread(name: String, block: () -> Unit) = Thread(block, name).also { it.isDaemon = true; it.start() }
    @Test fun tokenOwnedCleanupAndSharingPredicateFinishWithoutOppositeMonitorWait() {
        val auth = Any(); val a = origin(); val tokenOwned = CountDownLatch(1)
        val currentEntered = CountDownLatch(1); val finished = CountDownLatch(2)
        val enabled = AtomicBoolean(false)
        val guard = RemoteLocalSharingGuard {
            if (enabled.get()) currentEntered.countDown()
            synchronized(auth) { true }
        }
        guard.observe(a, "33333333-3333-4333-8333-333333333333", "44444444-4444-4444-8444-444444444444")
        enabled.set(true)
        thread("owned-auth-cleanup") {
            synchronized(auth) {
                tokenOwned.countDown(); check(currentEntered.await(2, TimeUnit.SECONDS))
                guard.authorityChanged()
            }
            finished.countDown()
        }
        thread("remote-sharing-predicate") {
            check(tokenOwned.await(2, TimeUnit.SECONDS)); guard.pause(a); finished.countDown()
        }
        assertTrue("Token cleanup and the real sharing guard must both finish", finished.await(3, TimeUnit.SECONDS))
    }
    @Test fun tokenOwnedCleanupAndActualPublicationHelperFinishInOneOrder() {
        val auth = Any(); val lifecycle = Any(); val a = origin()
        val tokenOwned = CountDownLatch(1); val acquisitionAttempt = CountDownLatch(1); val finished = CountDownLatch(2)
        val helper = RemoteOriginalCommit(lifecycle, commitLogin = { _, body ->
            acquisitionAttempt.countDown(); synchronized(auth) { body(); true }
        }, originalViewCurrent = {
            acquisitionAttempt.countDown(); synchronized(auth) { true }
        })
        thread("owned-auth-lifecycle-cleanup") {
            synchronized(auth) {
                tokenOwned.countDown(); check(acquisitionAttempt.await(2, TimeUnit.SECONDS))
                helper.withLoginSnapshot(a.loginLease) { /* same non-suspending cleanup callback boundary */ }
            }
            finished.countDown()
        }
        thread("remote-original-publication") {
            check(tokenOwned.await(2, TimeUnit.SECONDS)); helper.withOriginal(a) { true }; finished.countDown()
        }
        assertTrue("Token-owned cleanup and the actual publication helper must both finish", finished.await(3, TimeUnit.SECONDS))
    }
    @Test fun ownedViewCheckAndPrivacyCallbacksCannotBorrowReplacementLogin() {
        val auth = Any(); val lifecycle = Any(); val a = origin(); val b = origin()
        var current = a; var stops = 0; var publications = 0
        val guard = RemoteLocalSharingGuard { it === current }
        val helper = RemoteOriginalCommit(lifecycle, commitLogin = { lease, body -> synchronized(auth) {
            if (lease !== current.loginLease) false else { body(); true }
        } }, originalViewCurrent = {
            assertTrue(Thread.holdsLock(auth)); assertTrue(Thread.holdsLock(lifecycle)); it === current
        })
        val grant = "33333333-3333-4333-8333-333333333333"
        val session = "44444444-4444-4444-8444-444444444444"
        helper.withOriginal(a) { guard.observe(a, grant, session); assertTrue(guard.resume(a, grant, session, true)) }
        synchronized(auth) { current = b; helper.withLoginSnapshot(b.loginLease) { guard.authorityChanged() } }
        helper.withOriginal(b) { guard.observe(b, grant, session); assertTrue(guard.resume(b, grant, session, true)) }
        assertNull(helper.withOriginal(a) { publications++; guard.pause(a) })
        assertNull(helper.withOriginal(a) {
            guard.privacyRecord(RemoteLocalPrivacyAction(a, RemotePendingKind.END, session), true,
                stop = { stops++ }, record = { true }, matches = { it })
        })
        assertEquals(0, publications); assertEquals(0, stops); assertTrue(guard.allows(b, grant, session))
    }

    @Test fun sharingMonitorNeverEnclosesCurrentOrRecordCallbacks() {
        val a = origin(); lateinit var guard: RemoteLocalSharingGuard
        guard = RemoteLocalSharingGuard { assertFalse(Thread.holdsLock(guard)); true }
        val grant = "33333333-3333-4333-8333-333333333333"
        val session = "44444444-4444-4444-8444-444444444444"
        guard.observe(a, grant, session); guard.resume(a, grant, session, true)
        guard.privacyRecord(RemoteLocalPrivacyAction(a, RemotePendingKind.END, session), true,
            stop = { assertFalse(Thread.holdsLock(guard)) },
            record = { assertFalse(Thread.holdsLock(guard)); true },
            matches = { assertFalse(Thread.holdsLock(guard)); it })
        assertFalse(guard.allows(a, grant, session)); assertFalse(guard.hasWriteHold())
    }

}
