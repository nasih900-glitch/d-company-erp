package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.net.ApiException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BugReportViewModelTest {

    private val validContext = BugReportClientContext(
        platform = "android",
        appVersion = "3.0.5",
        versionCode = 6,
        connectivity = "online",
        occurredAt = "2026-08-28T10:00:00Z",
    )

    @Test
    fun `ambiguous retry retains draft exact request and idempotency key`() {
        val api = RecordingBugReportApi(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(ApiException("timeout")),
                    Result.success(success()),
                ),
            ),
        )
        val vm = viewModel(api)
        enterValidDraft(vm)

        vm.submit(validContext)

        assertFalse(vm.state.value.submitting)
        assertNull(vm.state.value.success)
        assertEquals("Payment failed at till", vm.state.value.draft.title)
        assertTrue(vm.state.value.error.orEmpty().contains("not be created twice"))

        // A newly captured context would normally change occurred_at. The
        // retry must still replay the original body and key byte-for-byte.
        vm.submit(validContext.copy(occurredAt = "2026-08-28T10:01:00Z"))

        assertNotNull(vm.state.value.success)
        assertEquals(2, api.requests.size)
        assertEquals(api.requests[0], api.requests[1])
        assertEquals(listOf("test-key", "test-key"), api.keys)
    }

    @Test
    fun `double tap while request is in flight sends only once`() {
        val gate = CompletableDeferred<BugReportCreateResponse>()
        val api = BlockingBugReportApi(gate)
        val vm = viewModel(api)
        enterValidDraft(vm)

        vm.submit(validContext)
        vm.submit(validContext)

        assertTrue(vm.state.value.submitting)
        assertEquals(1, api.calls)

        gate.complete(success())

        assertFalse(vm.state.value.submitting)
        assertEquals(1, api.calls)
        assertEquals("open", vm.state.value.success?.status)
    }

    @Test
    fun `offline send keeps the entire draft and never calls the API`() {
        val api = RecordingBugReportApi()
        val vm = viewModel(api)
        enterValidDraft(vm)
        val before = vm.state.value.draft

        vm.submit(validContext.copy(connectivity = "offline"))

        assertEquals(before, vm.state.value.draft)
        assertTrue(vm.state.value.error.orEmpty().contains("offline", ignoreCase = true))
        assertTrue(api.requests.isEmpty())
    }

    @Test
    fun `failed draft survives closing and reopening but success resets it`() {
        val api = RecordingBugReportApi(
            outcomes = ArrayDeque(
                listOf(
                    Result.failure(ApiException("server", status = 500)),
                    Result.success(success()),
                ),
            ),
        )
        val vm = viewModel(api)
        vm.open()
        enterValidDraft(vm)

        vm.submit(validContext)
        vm.dismiss()
        assertFalse(vm.state.value.isOpen)
        assertEquals("Payment failed at till", vm.state.value.draft.title)

        vm.open()
        vm.submit(validContext)
        vm.dismiss()

        assertFalse(vm.state.value.isOpen)
        assertEquals(BugReportDraft(), vm.state.value.draft)
        assertNull(vm.state.value.success)
        assertNull(vm.state.value.error)
    }

    private fun viewModel(api: BugReportApi) = BugReportViewModel(
        api = api,
        keyFactory = { "test-key" },
        requestScope = CoroutineScope(Dispatchers.Unconfined),
    )

    private fun enterValidDraft(vm: BugReportViewModel) {
        vm.categoryChanged(BugReportCategory.Payment)
        vm.severityChanged(BugReportSeverity.High)
        vm.titleChanged("Payment failed at till")
        vm.descriptionChanged("Cash payment was rejected without an explanation.")
        vm.reproductionStepsChanged("Open POS, select cash, then tap Pay")
        vm.expectedBehaviorChanged("Payment completes or explains what to do")
        vm.actualBehaviorChanged("The dialog stayed open with no useful message")
    }

    private fun success() = BugReportCreateResponse(
        id = "33333333-3333-4333-8333-333333333333",
        status = "open",
        createdAt = "2026-08-28T10:00:01Z",
    )

    private class RecordingBugReportApi(
        private val outcomes: ArrayDeque<Result<BugReportCreateResponse>> = ArrayDeque(),
    ) : BugReportApi {
        val requests = mutableListOf<BugReportCreateRequest>()
        val keys = mutableListOf<String>()

        override suspend fun create(
            body: BugReportCreateRequest,
            idempotencyKey: String,
        ): BugReportCreateResponse {
            requests += body
            keys += idempotencyKey
            return outcomes.removeFirstOrNull()?.getOrThrow() ?: success()
        }

        private fun success() = BugReportCreateResponse(
            id = "33333333-3333-4333-8333-333333333333",
            status = "open",
            createdAt = "2026-08-28T10:00:01Z",
        )
    }

    private class BlockingBugReportApi(
        private val gate: CompletableDeferred<BugReportCreateResponse>,
    ) : BugReportApi {
        var calls = 0

        override suspend fun create(
            body: BugReportCreateRequest,
            idempotencyKey: String,
        ): BugReportCreateResponse {
            calls += 1
            return gate.await()
        }
    }
}
