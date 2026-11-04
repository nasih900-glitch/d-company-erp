package cloud.dcompany.erp.ui.screens.gaming

import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.Order
import cloud.dcompany.erp.core.net.OrderLine
import cloud.dcompany.erp.core.net.CheckoutClaimResult
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.encodeToString
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class GamingVoidV2ContractTest {

    @Test
    fun `active Void with served item rejects removed or changed target before POST`() = runBlocking {
        val target = retainedTarget()
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), listOf(reviewedAddon()), listOf(target), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", target.shiftId, review.scopeLease)
        for (targets in listOf(emptyList(), listOf(target.copy(terminalId = "changed-terminal")))) {
            var posts = 0
            val result = runCatching {
                executeSessionVoidAttempt(review, attempt, { review.scopeLease }, { review.session },
                    { review.activeAddons }, { targets }, { _, _, _ -> posts += 1; error("must not dispatch") })
            }
            assertTrue(result.exceptionOrNull() is IllegalStateException)
            assertEquals(0, posts)
        }
    }

    @Test
    fun `active Void never dispatches after lease changes during retention discovery`() = runBlocking {
        val target = retainedTarget()
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), listOf(reviewedAddon()), listOf(target), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", target.shiftId, review.scopeLease)
        var currentLease = review.scopeLease
        var posts = 0
        val result = runCatching {
            executeSessionVoidAttempt(review, attempt, { currentLease }, { review.session },
                { review.activeAddons }, { currentLease = lease(2); review.eligibleTargets },
                { _, _, _ -> posts += 1; error("must not dispatch") })
        }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals(0, posts)
    }

    @Test
    fun `active Void accepts exact same Hybrid target and retains its reviewed item`() = runBlocking {
        val original = reviewedSession(status = "active").copy(
            serverPlayElapsedMs = 10 * 60_000L,
            voidReasonRequired = false,
        )
        val target = retainedTarget().copy(shiftId = requireNotNull(original.shiftId), terminalId = requireNotNull(lease().scope.terminalId))
        val review = SessionVoidReviewUi(original, listOf(reviewedAddon()), listOf(target), lease())
        val attempt = sessionVoidAttempt(review, "", target.shiftId, review.scopeLease)
        val completed = completedSessionVoid(attempt, "retained-order-1")
        var posts = 0
        val result = executeSessionVoidAttempt(review, attempt, { review.scopeLease }, { review.session },
            { review.activeAddons }, { review.eligibleTargets }, { _, body, key ->
                posts += 1
                assertEquals(target.shiftId, body.retainedTargetShiftId)
                assertEquals(attempt.idempotencyKey, key)
                assertTrue(body.reason.isNullOrBlank())
                completed
            }, nowMillis = { java.time.Instant.parse(original.startAt).toEpochMilli() + 10 * 60_000 })
        assertEquals(completed, result)
        assertEquals(1, posts)
        assertNull(retainedAddonOrderError(attempt, result, retainedOrder(review.activeAddons.single())))
    }

    @Test
    fun `before POS attempt freezes explicit null fence reason and request identity`() {
        val session = reviewedSession(status = "active", amountMinor = 15_000)
        val review = SessionVoidReviewUi(session, emptyList(), emptyList(), lease())
        val attempt = sessionVoidAttempt(review, "  Entry mistake  ", null, review.scopeLease)

        assertEquals("Entry mistake", attempt.body.reason)
        assertNull(attempt.retainedTargetShiftId)
        assertEquals("gaming-session-void:session-1", attempt.idempotencyKey)
        assertTrue(attempt.requestHash.matches(Regex("[0-9a-f]{64}")))
        assertEquals(
            "{\"reason\":\"Entry mistake\",\"expected_order_id\":null," +
                "\"expected_participant_revision\":3,\"expected_billing_revision\":7," +
                "\"expected_amount_minor\":15000}",
            ApiClient.json.encodeToString(attempt.body),
        )
    }

    @Test
    fun `fresh review identity permits elapsed policy drift but rejects financial CAS drift`() {
        val reviewed = reviewedSession(status = "active", amountMinor = 15_000)
        assertTrue(
            reviewed.copy(serverPlayElapsedMs = 1_800_001, voidReasonRequired = true)
                .matchesVoidReviewSnapshot(reviewed),
        )
        assertFalse(reviewed.copy(billingRevision = 8).matchesVoidReviewSnapshot(reviewed))
        assertFalse(reviewed.copy(participantRevision = 4).matchesVoidReviewSnapshot(reviewed))
        assertFalse(reviewed.copy(amountMinor = 15_001).matchesVoidReviewSnapshot(reviewed))
        assertFalse(reviewed.copy(orderId = "pos-order").matchesVoidReviewSnapshot(reviewed))
    }

    @Test
    fun `canonical cancelled state converges only for the exact receipt key and request hash`() {
        val review = SessionVoidReviewUi(reviewedSession(), emptyList(), emptyList(), lease())
        val attempt = sessionVoidAttempt(review, "Mistake", null, review.scopeLease)
        val canonical = review.session.copy(
            status = "cancelled",
            amountMinor = 0,
            billableMinutes = 0,
            cancelReason = "Mistake",
            cancelledBy = attempt.scopeLease.scope.userId,
            cancellationReceiptId = "receipt-1",
            cancellationIdempotencyKey = attempt.idempotencyKey,
            cancellationRequestHash = attempt.requestHash,
        )

        assertTrue(canonical.matchesCompletedSessionVoid(attempt))
        assertFalse(canonical.copy(cancellationIdempotencyKey = "other").matchesCompletedSessionVoid(attempt))
        assertFalse(canonical.copy(cancellationRequestHash = "0".repeat(64)).matchesCompletedSessionVoid(attempt))
        assertFalse(canonical.copy(cancellationReceiptId = null).matchesCompletedSessionVoid(attempt))
    }

    @Test
    fun `Owner POS attempt freezes every backend CAS field and never uses generic void`() {
        val review = linkedReview()
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)

        assertTrue(attempt.idempotencyKey.matches(
            Regex("pos-gaming-void:order-1:session-1:v11:[0-9a-f]{24}"),
        ))
        assertTrue(attempt.requestHash.matches(Regex("[0-9a-f]{64}")))
        assertFalse(
            linkedGamingVoidAttempt(review, "A different reviewed reason", review.scopeLease).idempotencyKey ==
                attempt.idempotencyKey,
        )
        assertEquals(
            "{\"session_id\":\"session-1\",\"reason\":\"Reviewed operator mistake\"," +
                "\"expected_order_status\":\"held\",\"expected_checkout_version\":11," +
                "\"expected_order_total_minor\":15700,\"expected_session_status\":\"ended\"," +
                "\"expected_billing_revision\":7,\"expected_participant_revision\":3," +
                "\"expected_amount_minor\":15000}",
            ApiClient.json.encodeToString(attempt.body),
        )
    }

    @Test
    fun `Owner POS response must distinguish a closed empty order from retained add-ons`() {
        val review = linkedReview()
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        val empty = GamingOrderVoidRead(
            receiptId = "receipt-1",
            sessionId = review.session.id,
            sourceOrderId = review.order.id,
            retainedTotalMinor = 0,
            retainedAddonCount = 0,
            sessionStatus = "cancelled",
            sourceOrderStatus = "void",
        )
        val retained = empty.copy(
            retainedOrderId = review.order.id,
            retainedTotalMinor = 700,
            retainedAddonCount = 2,
            sourceOrderStatus = "held",
        )

        assertNull(gamingOrderVoidResponseError(empty, attempt))
        assertNull(gamingOrderVoidResponseError(retained, attempt))
        assertTrue(gamingOrderVoidResponseError(empty.copy(retainedTotalMinor = 1), attempt) != null)
        assertTrue(gamingOrderVoidResponseError(retained.copy(retainedOrderId = "other"), attempt) != null)
        assertTrue(gamingOrderVoidResponseError(retained.copy(sourceOrderStatus = "void"), attempt) != null)
    }

    @Test
    fun `retained target list fails closed on empty duplicate or incomplete identities`() {
        val target = PosTargetShift(
            shiftId = "shift-pos",
            terminalId = "terminal-pos",
            terminalName = "Cafe POS",
            openedBy = "user-1",
            openedByName = "Cashier",
            openedAt = "2026-10-26T08:00:00Z",
        )
        assertTrue(retainedAddonTargetListError(emptyList()) != null)
        assertTrue(retainedAddonTargetListError(listOf(target, target)) != null)
        assertTrue(retainedAddonTargetListError(listOf(target.copy(openedBy = ""))) != null)
        assertNull(retainedAddonTargetListError(listOf(target)))
    }

    @Test
    fun `before POS attempt rejects a retained shift when no consumed items exist`() {
        val review = SessionVoidReviewUi(reviewedSession(), emptyList(), emptyList(), lease())

        assertThrows(IllegalArgumentException::class.java) {
            sessionVoidAttempt(review, "Mistake", "shift-pos", review.scopeLease)
        }
    }

    @Test
    fun `percentage discounted linked bill remains reviewable through exact order CAS`() {
        val discounted = linkedReview().let { review ->
            review.copy(order = review.order.copy(totalMinor = 12_000, dueMinor = 12_000))
        }
        val attempt = linkedGamingVoidAttempt(discounted, "Reviewed operator mistake", discounted.scopeLease)

        assertEquals(12_000L, attempt.body.expectedOrderTotalMinor)
        assertEquals(15_000L, attempt.body.expectedAmountMinor)
    }

    @Test
    fun `process attempt owners replay exact bodies and reject edited reasons`() {
        val sessionReview = SessionVoidReviewUi(reviewedSession(), emptyList(), emptyList(), lease())
        val firstSessionAttempt = sessionVoidAttempt(sessionReview, "Mistake", null, sessionReview.scopeLease)
        val sessionOwner = AtomicReference<SessionVoidAttempt?>(null)
        assertTrue(firstSessionAttempt === retainExactSessionVoidAttempt(sessionOwner, firstSessionAttempt))
        assertTrue(firstSessionAttempt === retainExactSessionVoidAttempt(sessionOwner, firstSessionAttempt.copy()))
        assertThrows(IllegalStateException::class.java) {
            retainExactSessionVoidAttempt(
                sessionOwner,
                sessionVoidAttempt(sessionReview, "Different reason", null, sessionReview.scopeLease),
            )
        }

        val linkedReview = linkedReview()
        val firstLinkedAttempt = linkedGamingVoidAttempt(linkedReview, "Mistake", linkedReview.scopeLease)
        val linkedOwner = AtomicReference<LinkedGamingVoidAttempt?>(null)
        assertTrue(firstLinkedAttempt === retainExactLinkedGamingVoidAttempt(linkedOwner, firstLinkedAttempt))
        assertThrows(IllegalStateException::class.java) {
            retainExactLinkedGamingVoidAttempt(
                linkedOwner,
                linkedGamingVoidAttempt(linkedReview, "Different reason", linkedReview.scopeLease),
            )
        }
    }

    @Test
    fun `Void review and confirmation must retain the exact workspace lease`() {
        val reviewLease = lease(41)
        val sessionReview = SessionVoidReviewUi(reviewedSession(), emptyList(), emptyList(), reviewLease)
        val ownerReview = linkedReview().copy(scopeLease = reviewLease)

        assertNull(voidActionLeaseError(reviewLease, reviewLease))
        assertTrue(voidActionLeaseError(reviewLease, lease(42)) != null)
        assertTrue(
            voidActionLeaseError(
                reviewLease,
                CacheScopeLease(
                    CacheScope("owner-1", "company-1", "branch-1", null),
                    41,
                ),
            ) != null,
        )
        assertThrows(IllegalArgumentException::class.java) {
            sessionVoidAttempt(sessionReview, "Mistake", null, lease(42))
        }
        assertThrows(IllegalArgumentException::class.java) {
            linkedGamingVoidAttempt(ownerReview, "Mistake", lease(42))
        }
    }

    @Test
    fun `cross shift Owner capability exposes only the unlinked Void decision`() {
        assertTrue(canOfferUnlinkedVoid(ownsSession = true, canVoidUnlinkedAcrossShifts = false))
        assertTrue(canOfferUnlinkedVoid(ownsSession = false, canVoidUnlinkedAcrossShifts = true))
        assertFalse(canOfferUnlinkedVoid(ownsSession = false, canVoidUnlinkedAcrossShifts = false))
    }

    @Test
    fun `Owner review admits only the exact unpaid held session bill`() {
        val review = linkedReview()

        assertNull(linkedGamingVoidReviewOrderError(review.order, review.order.id))
        assertTrue(linkedGamingVoidReviewOrderError(review.order.copy(type = "retail"), review.order.id) != null)
        assertTrue(linkedGamingVoidReviewOrderError(review.order.copy(status = "paid"), review.order.id) != null)
        assertTrue(linkedGamingVoidReviewOrderError(review.order.copy(paidMinor = 1), review.order.id) != null)
        assertTrue(linkedGamingVoidReviewOrderError(review.order.copy(invoiceNo = "INV-1"), review.order.id) != null)
        assertTrue(linkedGamingVoidReviewOrderError(review.order, "other-order") != null)
    }

    @Test
    fun `Owner review and final retained bill bind the exact consumed item cohort`() {
        val review = linkedReview()
        val addonLine = review.order.lines.single { it.clientLineId == review.activeAddons.single().clientLineId }

        assertNull(linkedGamingVoidReviewCohortError(review.order, review.activeAddons))
        assertTrue(
            linkedGamingVoidReviewCohortError(
                review.order.copy(lines = review.order.lines.map {
                    if (it.id == "gaming-charge-line") it.copy(clientLineId = "forbidden-charge-client") else it
                }),
                review.activeAddons,
            ) != null,
        )
        assertTrue(
            linkedGamingVoidReviewCohortError(
                review.order.copy(lines = review.order.lines.map {
                    if (it.id == addonLine.id) it.copy(discountMinor = 1) else it
                }),
                review.activeAddons,
            ) != null,
        )

        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        val response = GamingOrderVoidRead(
            receiptId = "receipt-1",
            sessionId = review.session.id,
            sourceOrderId = review.order.id,
            retainedOrderId = review.order.id,
            retainedTotalMinor = 700,
            retainedAddonCount = 1,
            sessionStatus = "cancelled",
            sourceOrderStatus = "held",
        )
        val retained = Order(
            id = review.order.id,
            heldAt = "2026-10-26T08:21:00Z",
            status = "held",
            type = "session",
            subtotalMinor = 600,
            taxMinor = 100,
            totalMinor = 700,
            paidMinor = 0,
            dueMinor = 700,
            lines = listOf(addonLine),
        )

        assertNull(linkedRetainedOrderError(response, attempt, retained))
        assertTrue(
            linkedRetainedOrderError(
                response,
                attempt,
                retained.copy(lines = listOf(addonLine.copy(menuItemId = "substituted-item"))),
            ) != null,
        )
        assertTrue(
            linkedRetainedOrderError(
                response,
                attempt,
                retained.copy(lines = listOf(addonLine.copy(discountMinor = 1))),
            ) != null,
        )
    }

    @Test
    fun `Owner checkout claim must belong to captured employee terminal and exact bill`() {
        val review = linkedReview()
        val claim = CheckoutClaimResult(
            claimId = "claim-1",
            orderId = review.order.id,
            claimToken = review.claimToken,
            expiresAt = "2100-01-01T00:00:00Z",
            orderTotalMinor = review.order.totalMinor,
            paidMinor = review.order.paidMinor,
            dueMinor = review.order.dueMinor,
            orderVersion = review.order.checkoutVersion,
            claimantUserId = review.scopeLease.scope.userId,
            terminalId = requireNotNull(review.scopeLease.scope.terminalId),
        )

        assertNull(linkedGamingVoidClaimError(review.order, claim, review.scopeLease, 0L))
        assertTrue(
            linkedGamingVoidClaimError(
                review.order,
                claim.copy(claimantUserId = "owner-2"),
                review.scopeLease,
                0L,
            ) != null,
        )
        assertTrue(
            linkedGamingVoidClaimError(
                review.order,
                claim.copy(terminalId = "terminal-2"),
                review.scopeLease,
                0L,
            ) != null,
        )
    }

    @Test
    fun `Owner POS canonical read binds receipt key hash reason and captured actor`() {
        val review = linkedReview()
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        val response = GamingOrderVoidRead(
            receiptId = "receipt-1",
            sessionId = review.session.id,
            sourceOrderId = review.order.id,
            retainedOrderId = review.order.id,
            retainedTotalMinor = 7_000,
            retainedAddonCount = 1,
            sessionStatus = "cancelled",
            sourceOrderStatus = "held",
        )
        val canonical = review.session.copy(
            status = "cancelled",
            orderId = null,
            retainedAddonOrderId = response.retainedOrderId,
            amountMinor = 0,
            billableMinutes = 0,
            cancelReason = attempt.body.reason,
            cancelledBy = attempt.scopeLease.scope.userId,
            cancellationReceiptId = response.receiptId,
            cancellationIdempotencyKey = attempt.idempotencyKey,
            cancellationRequestHash = attempt.requestHash,
        )

        assertNull(linkedGamingVoidCanonicalError(canonical, response, attempt))
        assertTrue(linkedGamingVoidCanonicalError(canonical.copy(cancelledBy = "owner-2"), response, attempt) != null)
        assertTrue(linkedGamingVoidCanonicalError(canonical.copy(cancellationReceiptId = "other"), response, attempt) != null)
        assertTrue(linkedGamingVoidCanonicalError(canonical.copy(cancellationIdempotencyKey = "other"), response, attempt) != null)
        assertTrue(linkedGamingVoidCanonicalError(canonical.copy(cancellationRequestHash = "0".repeat(64)), response, attempt) != null)
        assertTrue(linkedGamingVoidCanonicalError(canonical.copy(cancelReason = "Different"), response, attempt) != null)
    }

    @Test
    fun `retained consumed item bill must match selected target and every immutable money snapshot`() {
        val target = PosTargetShift(
            shiftId = "shift-pos",
            terminalId = "terminal-pos",
            terminalName = "Cafe POS",
            openedBy = "cashier-1",
            openedByName = "Cashier",
            openedAt = "2026-10-26T08:00:00Z",
        )
        val addon = reviewedAddon()
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), listOf(addon), listOf(target), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", target.shiftId, review.scopeLease)
        val cancelled = review.session.copy(
            status = "cancelled",
            amountMinor = 0,
            billableMinutes = 0,
            cancelReason = "Entry mistake",
            cancelledBy = attempt.scopeLease.scope.userId,
            retainedAddonOrderId = "retained-order-1",
            cancellationReceiptId = "receipt-1",
            cancellationIdempotencyKey = attempt.idempotencyKey,
            cancellationRequestHash = attempt.requestHash,
        )
        val retained = Order(
            id = "retained-order-1",
            heldAt = "2026-10-26T08:21:00Z",
            status = "held",
            type = "session",
            subtotalMinor = 600,
            discountMinor = 0,
            taxMinor = 100,
            totalMinor = 700,
            paidMinor = 0,
            dueMinor = 700,
            lines = listOf(
                OrderLine(
                    id = "order-line-1",
                    clientLineId = addon.clientLineId,
                    menuItemId = addon.menuItemId,
                    name = addon.menuItemName,
                    qty = 1.0,
                    unitPriceMinor = 700,
                    lineTotalMinor = 700,
                    taxableValueMinor = 600,
                    cgstMinor = 50,
                    sgstMinor = 50,
                ),
            ),
        )

        assertNull(retainedAddonOrderError(attempt, cancelled, retained))
        assertTrue(retainedAddonOrderError(attempt, cancelled, retained.copy(id = "other")) != null)
        assertTrue(retainedAddonOrderError(attempt, cancelled, retained.copy(status = "paid")) != null)
        assertTrue(retainedAddonOrderError(attempt, cancelled, retained.copy(invoiceNo = "INV-1")) != null)
        assertTrue(retainedAddonOrderError(attempt, cancelled, retained.copy(totalMinor = 800, dueMinor = 800)) != null)
        assertTrue(
            retainedAddonOrderError(
                attempt,
                cancelled,
                retained.copy(lines = retained.lines.map { it.copy(lineTotalMinor = 701) }),
            ) != null,
        )
        assertTrue(
            retainedAddonOrderError(
                attempt,
                cancelled,
                retained.copy(lines = retained.lines.map { it.copy(discountMinor = 1) }),
            ) != null,
        )
        assertTrue(
            retainedAddonOrderError(
                attempt.copy(retainedTarget = target.copy(terminalId = "")),
                cancelled,
                retained,
            ) != null,
        )
    }

    @Test
    fun `committed before POS Void recovers on next invocation without a second POST`() = runBlocking {
        val target = PosTargetShift(
            shiftId = "shift-pos",
            terminalId = "terminal-pos",
            terminalName = "Cafe POS",
            openedBy = "cashier-1",
            openedByName = "Cashier",
            openedAt = "2026-10-26T08:00:00Z",
        )
        val addon = reviewedAddon()
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), listOf(addon), listOf(target), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", target.shiftId, review.scopeLease)
        val completed = completedSessionVoid(attempt, "retained-order-1")
        var canonicalReads = 0
        var cancelCalls = 0

        val first = runCatching {
            executeSessionVoidAttempt(
                review = review,
                attempt = attempt,
                currentLease = { review.scopeLease },
                loadSession = {
                    canonicalReads += 1
                    when (canonicalReads) {
                        1 -> review.session
                        else -> throw ApiException("receipt read lost")
                    }
                },
                loadAddons = { review.activeAddons },
                loadTargets = { review.eligibleTargets },
                cancel = { _, body, key ->
                    assertEquals(attempt.body, body)
                    assertEquals(attempt.idempotencyKey, key)
                    cancelCalls += 1
                    throw ApiException("response lost")
                },
            )
        }
        assertTrue(first.exceptionOrNull() is ApiException)
        assertEquals(1, cancelCalls)
        assertEquals(2, canonicalReads)

        val recovered = executeSessionVoidAttempt(
            review = review,
            attempt = attempt,
            currentLease = { review.scopeLease },
            loadSession = { completed },
            loadAddons = { error("completed receipt must bypass mutable add-on reads") },
            loadTargets = { error("completed receipt must bypass target reads") },
            cancel = { _, _, _ -> cancelCalls += 1; error("must not POST twice") },
        )
        assertEquals(completed, recovered)
        assertEquals(1, cancelCalls)
        assertNull(retainedAddonOrderError(attempt, recovered, retainedOrder(addon)))
    }

    @Test
    fun `retained bill read failure recovers from completed before POS receipt without reposting`() = runBlocking {
        val addon = reviewedAddon()
        val target = retainedTarget()
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), listOf(addon), listOf(target), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", target.shiftId, review.scopeLease)
        val completed = completedSessionVoid(attempt, "retained-order-1")
        var cancelCalls = 0

        val first = executeSessionVoidAttempt(
            review,
            attempt,
            { review.scopeLease },
            { review.session },
            { review.activeAddons },
            { review.eligibleTargets },
            { _, _, _ -> cancelCalls += 1; completed },
        )
        val failedRetainedRead = runCatching {
            loadVerifiedSessionVoidResult(attempt, first) {
                throw ApiException("retained order read failed")
            }
        }
        assertTrue(failedRetainedRead.exceptionOrNull() is ApiException)
        assertEquals(completed, first)

        val recovered = executeSessionVoidAttempt(
            review,
            attempt,
            { review.scopeLease },
            { completed },
            { error("must not reload add-ons") },
            { error("must not reload targets") },
            { _, _, _ -> cancelCalls += 1; error("must not POST twice") },
        )
        val (_, retained) = loadVerifiedSessionVoidResult(attempt, recovered) { retainedOrder(addon) }
        assertEquals("retained-order-1", retained?.id)
        assertEquals(1, cancelCalls)
    }

    @Test
    fun `before POS recovery rejects another actor even with the same key and hash`() = runBlocking {
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), emptyList(), emptyList(), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", null, review.scopeLease)
        val otherActor = completedSessionVoid(attempt, null).copy(cancelledBy = "owner-2")
        var cancelCalls = 0

        val outcome = runCatching {
            executeSessionVoidAttempt(
                review,
                attempt,
                { review.scopeLease },
                { otherActor },
                { emptyList() },
                { emptyList() },
                { _, _, _ -> cancelCalls += 1; otherActor },
            )
        }
        assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        assertEquals(0, cancelCalls)
    }

    @Test
    fun `before POS recovery stops when the workspace lease changes after canonical GET`() = runBlocking {
        val review = SessionVoidReviewUi(reviewedSession(status = "active"), emptyList(), emptyList(), lease())
        val attempt = sessionVoidAttempt(review, "Entry mistake", null, review.scopeLease)
        var leaseReads = 0
        var cancelCalls = 0

        val outcome = runCatching {
            executeSessionVoidAttempt(
                review = review,
                attempt = attempt,
                currentLease = { if (leaseReads++ == 0) review.scopeLease else lease(2) },
                loadSession = { review.session },
                loadAddons = { error("stale lease must stop before add-on reads") },
                loadTargets = { error("stale lease must stop before target reads") },
                cancel = { _, _, _ -> cancelCalls += 1; completedSessionVoid(attempt, null) },
            )
        }

        assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        assertEquals(0, cancelCalls)
    }

    @Test
    fun `expired linked claim replays only an exact owned durable receipt`() = runBlocking {
        val review = linkedReview().copy(claimExpiresAtMillis = 0L)
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        val canonical = completedLinkedVoid(attempt)
        val response = linkedVoidResponse(attempt)
        var paymentChecks = 0
        var postCalls = 0

        val actual = executeLinkedGamingVoidAttempt(
            attempt = attempt,
            currentLease = { review.scopeLease },
            loadSession = { canonical },
            hasPendingPayment = { paymentChecks += 1; true },
            postVoid = { orderId, body, key, token ->
                assertEquals(review.order.id, orderId)
                assertEquals(attempt.body, body)
                assertEquals(attempt.idempotencyKey, key)
                assertEquals(review.claimToken, token)
                postCalls += 1
                response
            },
            nowMillis = { 1L },
        )
        assertEquals(response, actual)
        assertEquals(0, paymentChecks)
        assertEquals(1, postCalls)
    }

    @Test
    fun `linked response and retained read loss recover by replaying only the exact durable attempt`() = runBlocking {
        val review = linkedReview()
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        val canonical = completedLinkedVoid(attempt)
        val response = linkedVoidResponse(attempt)
        val postedBodies = mutableListOf<GamingOrderVoidRequest>()
        val postedKeys = mutableListOf<String>()
        val postedTokens = mutableListOf<String>()

        val first = runCatching {
            executeLinkedGamingVoidAttempt(
                attempt = attempt,
                currentLease = { review.scopeLease },
                loadSession = { review.session },
                hasPendingPayment = { false },
                postVoid = { _, body, key, token ->
                    postedBodies += body
                    postedKeys += key
                    postedTokens += token
                    throw ApiException("response lost")
                },
                nowMillis = { 0L },
            )
        }
        assertTrue(first.exceptionOrNull() is ApiException)

        val replayAfterLostResponse = executeLinkedGamingVoidAttempt(
            attempt = attempt,
            currentLease = { review.scopeLease },
            loadSession = { canonical },
            hasPendingPayment = { error("durable receipt must bypass mutable payment state") },
            postVoid = { _, body, key, token ->
                postedBodies += body
                postedKeys += key
                postedTokens += token
                response
            },
            nowMillis = { Long.MAX_VALUE },
        )
        val failedRetainedRead = runCatching {
            loadVerifiedLinkedGamingVoidResult(
                attempt,
                replayAfterLostResponse,
                loadSession = { canonical },
                loadOrder = { throw ApiException("retained order read lost") },
            )
        }
        assertTrue(failedRetainedRead.exceptionOrNull() is ApiException)

        val replayAfterLostRead = executeLinkedGamingVoidAttempt(
            attempt = attempt,
            currentLease = { review.scopeLease },
            loadSession = { canonical },
            hasPendingPayment = { error("durable receipt must bypass mutable payment state") },
            postVoid = { _, body, key, token ->
                postedBodies += body
                postedKeys += key
                postedTokens += token
                response
            },
            nowMillis = { Long.MAX_VALUE },
        )
        val (verifiedSession, retained) = loadVerifiedLinkedGamingVoidResult(
            attempt,
            replayAfterLostRead,
            loadSession = { canonical },
            loadOrder = {
                retainedOrder(review.activeAddons.single()).copy(
                    id = review.order.id,
                    lines = review.order.lines.filter { it.clientLineId != null },
                )
            },
        )

        assertEquals(canonical, verifiedSession)
        assertEquals(review.order.id, retained?.id)
        assertEquals(listOf(attempt.body, attempt.body, attempt.body), postedBodies)
        assertEquals(listOf(attempt.idempotencyKey, attempt.idempotencyKey, attempt.idempotencyKey), postedKeys)
        assertEquals(listOf(review.claimToken, review.claimToken, review.claimToken), postedTokens)
    }

    @Test
    fun `expired linked claim never dispatches for missing wrong actor or edited receipt proof`() = runBlocking {
        val review = linkedReview().copy(claimExpiresAtMillis = 0L)
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        val candidates = listOf(
            review.session,
            completedLinkedVoid(attempt).copy(cancelledBy = "owner-2"),
            completedLinkedVoid(attempt).copy(cancelReason = "Edited reason"),
            completedLinkedVoid(attempt).copy(cancellationRequestHash = "0".repeat(64)),
        )
        var postCalls = 0
        candidates.forEach { canonical ->
            val outcome = runCatching {
                executeLinkedGamingVoidAttempt(
                    attempt,
                    { review.scopeLease },
                    { canonical },
                    { false },
                    { _, _, _, _ -> postCalls += 1; linkedVoidResponse(attempt) },
                    { 1L },
                )
            }
            assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        }
        assertEquals(0, postCalls)
    }

    @Test
    fun `linked replay stops when lease changes after canonical GET`() = runBlocking {
        val review = linkedReview().copy(claimExpiresAtMillis = 0L)
        val attempt = linkedGamingVoidAttempt(review, "Reviewed operator mistake", review.scopeLease)
        var leaseReads = 0
        var postCalls = 0
        val outcome = runCatching {
            executeLinkedGamingVoidAttempt(
                attempt,
                currentLease = { if (leaseReads++ == 0) review.scopeLease else lease(2) },
                loadSession = { completedLinkedVoid(attempt) },
                hasPendingPayment = { false },
                postVoid = { _, _, _, _ -> postCalls += 1; linkedVoidResponse(attempt) },
            )
        }
        assertTrue(outcome.exceptionOrNull() is IllegalStateException)
        assertEquals(0, postCalls)
    }

    @Test
    fun `invalid acquired claim is released only while its captured lease still owns the workspace`() = runBlocking {
        val review = linkedReview()
        val invalidClaim = CheckoutClaimResult(
            claimId = "claim-1",
            orderId = review.order.id,
            claimToken = review.claimToken,
            expiresAt = "2100-01-01T00:00:00Z",
            orderTotalMinor = review.order.totalMinor,
            paidMinor = review.order.paidMinor,
            dueMinor = review.order.dueMinor,
            orderVersion = review.order.checkoutVersion,
            claimantUserId = "owner-2",
            terminalId = requireNotNull(review.scopeLease.scope.terminalId),
        )
        val releases = mutableListOf<Pair<String, String>>()
        val owned = runCatching {
            validateAcquiredGamingVoidClaim(
                review.order,
                invalidClaim,
                review.scopeLease,
                0L,
                { review.scopeLease },
                { orderId, token -> releases += orderId to token },
            )
        }
        assertTrue(owned.exceptionOrNull() is IllegalStateException)
        assertEquals(listOf(review.order.id to review.claimToken), releases)

        releases.clear()
        val stale = runCatching {
            validateAcquiredGamingVoidClaim(
                review.order,
                invalidClaim,
                review.scopeLease,
                0L,
                { lease(2) },
                { orderId, token -> releases += orderId to token },
            )
        }
        assertTrue(stale.exceptionOrNull() is IllegalStateException)
        assertTrue(releases.isEmpty())
    }

    private fun reviewedSession(
        status: String = "ended",
        amountMinor: Long = 15_000,
    ) = GameSession(
        id = "session-1",
        stationId = "station-1",
        shiftId = "shift-1",
        status = status,
        startAt = "2026-10-26T08:00:00Z",
        endAt = if (status == "ended") "2026-10-26T08:20:00Z" else null,
        billableMinutes = if (status == "ended") 20 else null,
        amountMinor = amountMinor,
        billingMode = "package",
        packageId = "package-1",
        packagePriceMinorSnapshot = 15_000,
        participantRevision = 3,
        billingRevision = 7,
    )

    private fun reviewedAddon() = SessionAddon(
        id = "addon-1",
        gamingSessionId = "session-1",
        clientLineId = "client-line-1",
        menuItemId = "menu-item-1",
        menuItemName = "Water",
        menuItemType = "drink",
        modifiers = JsonArray(emptyList()),
        qty = 1,
        catalogUnitPriceMinor = 700,
        unitPriceMinor = 700,
        lineTotalMinor = 700,
        discountMinor = 0,
        taxRate = 18.0,
        taxableValueMinor = 600,
        cgstMinor = 50,
        sgstMinor = 50,
        igstMinor = 0,
        cessMinor = 0,
        createdBy = "owner-1",
        createdTerminalId = "terminal-1",
        createdAt = "2026-10-26T08:10:00Z",
    )

    private fun retainedTarget() = PosTargetShift(
        shiftId = "shift-pos",
        terminalId = "terminal-pos",
        terminalName = "Cafe POS",
        openedBy = "cashier-1",
        openedByName = "Cashier",
        openedAt = "2026-10-26T08:00:00Z",
    )

    private fun completedSessionVoid(
        attempt: SessionVoidAttempt,
        retainedOrderId: String?,
    ) = attempt.session.copy(
        status = "cancelled",
        amountMinor = 0,
        billableMinutes = 0,
        cancelReason = attempt.body.reason ?: "Void requested without a staff-entered reason.",
        cancelledBy = attempt.scopeLease.scope.userId,
        retainedAddonOrderId = retainedOrderId,
        cancellationReceiptId = "receipt-1",
        cancellationIdempotencyKey = attempt.idempotencyKey,
        cancellationRequestHash = attempt.requestHash,
    )

    private fun retainedOrder(addon: SessionAddon) = Order(
        id = "retained-order-1",
        heldAt = "2026-10-26T08:21:00Z",
        status = "held",
        type = "session",
        subtotalMinor = 600,
        discountMinor = 0,
        taxMinor = 100,
        totalMinor = 700,
        paidMinor = 0,
        dueMinor = 700,
        lines = listOf(
            OrderLine(
                id = "order-line-1",
                clientLineId = addon.clientLineId,
                menuItemId = addon.menuItemId,
                name = addon.menuItemName,
                qty = addon.qty.toDouble(),
                unitPriceMinor = addon.unitPriceMinor,
                lineTotalMinor = addon.lineTotalMinor,
                discountMinor = addon.discountMinor,
                taxableValueMinor = addon.taxableValueMinor,
                cgstMinor = addon.cgstMinor,
                sgstMinor = addon.sgstMinor,
                igstMinor = addon.igstMinor,
            ),
        ),
    )

    private fun completedLinkedVoid(attempt: LinkedGamingVoidAttempt) = attempt.review.session.copy(
        status = "cancelled",
        orderId = null,
        retainedAddonOrderId = attempt.review.order.id.takeIf { attempt.review.activeAddons.isNotEmpty() },
        amountMinor = 0,
        billableMinutes = 0,
        cancelReason = attempt.body.reason,
        cancelledBy = attempt.scopeLease.scope.userId,
        cancellationReceiptId = "receipt-1",
        cancellationIdempotencyKey = attempt.idempotencyKey,
        cancellationRequestHash = attempt.requestHash,
    )

    private fun linkedVoidResponse(attempt: LinkedGamingVoidAttempt) = GamingOrderVoidRead(
        receiptId = "receipt-1",
        sessionId = attempt.review.session.id,
        sourceOrderId = attempt.review.order.id,
        retainedOrderId = attempt.review.order.id.takeIf { attempt.review.activeAddons.isNotEmpty() },
        retainedTotalMinor = if (attempt.review.activeAddons.isEmpty()) 0 else 700,
        retainedAddonCount = attempt.review.activeAddons.size,
        sessionStatus = "cancelled",
        sourceOrderStatus = if (attempt.review.activeAddons.isEmpty()) "void" else "held",
    )

    private fun linkedReview(): LinkedGamingVoidReviewUi {
        val addon = reviewedAddon()
        return LinkedGamingVoidReviewUi(
            session = reviewedSession().copy(orderId = "order-1"),
            order = Order(
                id = "order-1",
                status = "held",
                type = "session",
                subtotalMinor = 15_600,
                taxMinor = 100,
                totalMinor = 15_700,
                paidMinor = 0,
                dueMinor = 15_700,
                checkoutVersion = 11,
                lines = listOf(
                    OrderLine(
                        id = "gaming-charge-line",
                        name = "Gaming session",
                        qty = 1.0,
                        unitPriceMinor = 15_000,
                        lineTotalMinor = 15_000,
                        taxableValueMinor = 15_000,
                    ),
                    OrderLine(
                        id = "addon-line",
                        clientLineId = addon.clientLineId,
                        menuItemId = addon.menuItemId,
                        name = addon.menuItemName,
                        qty = addon.qty.toDouble(),
                        unitPriceMinor = addon.unitPriceMinor,
                        lineTotalMinor = addon.lineTotalMinor,
                        taxableValueMinor = addon.taxableValueMinor,
                        discountMinor = addon.discountMinor,
                        cgstMinor = addon.cgstMinor,
                        sgstMinor = addon.sgstMinor,
                        igstMinor = addon.igstMinor,
                    ),
                ),
            ),
            activeAddons = listOf(addon),
            claimToken = "claim-token",
            claimExpiresAtMillis = Long.MAX_VALUE,
            scopeLease = lease(),
        )
    }

    private fun lease(generation: Long = 1L) = CacheScopeLease(
        scope = CacheScope(
            userId = "owner-1",
            companyId = "company-1",
            branchId = "branch-1",
            terminalId = "terminal-1",
        ),
        generation = generation,
    )
}
