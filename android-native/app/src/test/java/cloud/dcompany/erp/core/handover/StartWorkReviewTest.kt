package cloud.dcompany.erp.core.handover

import cloud.dcompany.erp.ui.screens.shift.work.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory

class StartWorkReviewTest {
    private val actor="11111111-1111-4111-8111-111111111111"
    private val company="22222222-2222-4222-8222-222222222222"
    private val branch="33333333-3333-4333-8333-333333333333"
    private val terminal="44444444-4444-4444-8444-444444444444"
    private val authority=HandoverAuthority(HandoverScope(actor,company,branch,terminal),1)
    private fun reference():StartWorkReference {
        val r=StartWorkReference(UUID.randomUUID().toString(),company,actor,branch,terminal,UUID.randomUUID().toString(),openingFloatMinor=0,requestHash="")
        return r.copy(requestHash=r.expectedHash())
    }
    private class Store:HandoverIntentStore {
        var value:HandoverIntent?=null;var bad=false
        override suspend fun read():HandoverIntent? { if(bad)throw IOException("storage");return value }
        override suspend fun begin(intent:HandoverIntent) { value=intent }
        override suspend fun compareAndSet(expected:HandoverIntent,next:HandoverIntent) { check(value==expected);value=next }
        override suspend fun cancelNotSent(expected:HandoverIntent) { error("Unexpected retirement") }
    }
    @Test fun reviewRequiresInitializedAndFreshlyReadEmptyDurableFence()=runBlocking {
        val store=Store();val c=HandoverCoordinator(store);val ref=reference()
        assertTrue(runCatching{c.beginStartWorkReview(ref,"owner_status")}.isFailure)
        c.initialize(authority);store.bad=true
        assertTrue(runCatching{c.beginStartWorkReview(ref,"owner_status")}.isFailure)
        val retained=Store();val d=HandoverCoordinator(retained);d.initialize(authority)
        retained.value=HandoverIntent(HandoverAction(UUID.randomUUID().toString(),authority,UUID.randomUUID().toString(),UUID.randomUUID().toString(),0),stage=HandoverStage.SENT_UNKNOWN)
        assertTrue(runCatching{d.beginStartWorkReview(ref,"owner_status")}.isFailure)
    }
    @Test fun realRetrofitOwnerStatusSerializesNullReasonAndStripsSessionHeaders()=runBlocking {
        val c=HandoverCoordinator(Store());c.initialize(authority)
        val ref=reference();val permit=c.beginStartWorkReview(ref,"owner_status")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{\"state\":\"unconfirmed\",\"receipt\":null,\"cancellation\":null,\"current_shift\":null}"))
            val client=OkHttpClient.Builder().addInterceptor { chain -> chain.proceed(chain.request()) }.build()
            val api=Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                .callFactory(StartWorkReviewCallFactory(client,permit))
                .addConverterFactory(StartWorkJson.asConverterFactory("application/json".toMediaType())).build().create(OwnerStartWorkApi::class.java)
            assertEquals("unconfirmed",api.review(OwnerStartWorkReview(ref,"status","owner@example.test","memory-only")).state)
            val sent=server.takeRequest();assertEquals("/api/v1/pos/shifts/start-work/recovery",sent.path)
            assertNull(sent.getHeader("Authorization"));assertNull(sent.getHeader("X-Terminal-Id"))
            val body=StartWorkJson.parseToJsonElement(sent.body.readUtf8()).jsonObject
            assertEquals(setOf("reference","mode","owner_email","owner_password","reason"),body.keys)
            assertEquals("null",body["reason"].toString());assertEquals("status",body["mode"]!!.jsonPrimitive.content)
            assertThrows(IllegalArgumentException::class.java) {
                StartWorkReviewCallFactory(client,permit).newCall(Request.Builder().url(server.url("/api/v1/pos/shifts/start-work/recovery?alias=1")).get().build())
            }
        }
        c.finishStartWorkReview(permit)
    }
    @Test fun cancelledCoroutineCannotRetireLiveRecoveryWireAndBlocksOrdinaryAdmission()=runBlocking {
        val c=HandoverCoordinator(Store());c.initialize(authority)
        val permit=c.beginStartWorkReview(reference(),"owner_status");c.beforeStartWorkReviewWire(permit)
        c.finishStartWorkReview(permit)
        assertTrue(c.state.value.activeOperations.isNotEmpty())
        assertTrue(runCatching{c.admit(authority)}.isFailure)
        c.endStartWorkReviewWire(permit);assertTrue(c.state.value.activeOperations.isEmpty())
        val ordinary=c.admit(authority);c.finish(ordinary,OperationOutcome.NO_WRITE_SENT)
    }
    @Test fun coldOwnerRecoveryNeedsRealStartupAndFreshEmptyFenceButNeverInitializesIdentity()=runBlocking {
        val store=Store();val fence=HandoverStartupFence(store)
        var startup:cloud.dcompany.erp.PersistedStartupStateResult?=null
        val operations=HandoverOperations(store,fence,{startup},{null},{null},{null},{null},{null})
        val ref=reference()
        assertTrue(runCatching{operations.beginStartWorkOwnerRecovery(ref,"owner_status")}.isFailure)
        startup=cloud.dcompany.erp.PersistedStartupStateResult.Ready
        assertTrue(runCatching{operations.beginStartWorkOwnerRecovery(ref,"owner_status")}.isFailure)
        fence.load()
        assertTrue(runCatching{operations.beginStartWorkOwnerRecovery(ref,"actor_status")}.isFailure)
        val permit=operations.beginStartWorkOwnerRecovery(ref,"owner_status")
        assertFalse(operations.coordinator.state.value.initialized)
        assertEquals(HandoverStartupStatus.Preparing,operations.status())
        assertTrue(runCatching{operations.coordinator.admit(authority)}.isFailure)
        operations.coordinator.finishStartWorkReview(permit)
        assertFalse(operations.coordinator.state.value.initialized)
        store.value=HandoverIntent(HandoverAction(UUID.randomUUID().toString(),authority,UUID.randomUUID().toString(),UUID.randomUUID().toString(),0),stage=HandoverStage.SENT_UNKNOWN)
        assertTrue(runCatching{operations.beginStartWorkOwnerRecovery(ref,"owner_status")}.isFailure)
    }
    @Test fun coldOwnerRecoveryCannotRetireAChangedImmutableOutcomeOrSkipItsDurableCheckpoint()=runBlocking {
        val store=Store();val fence=HandoverStartupFence(store);fence.load()
        val operations=HandoverOperations(store,fence,{cloud.dcompany.erp.PersistedStartupStateResult.Ready},{null},{null},{null},{null},{null})
        val ref=reference();val permit=operations.beginStartWorkOwnerRecovery(ref,"owner_cancel")
        operations.coordinator.beforeStartWorkReviewWire(permit)
        val cancel=StartWorkCancellation(UUID.randomUUID().toString(),ref.actionKey,ref.companyId,ref.actorId,ref.branchId,ref.terminalId,ref.installationId,ref.openingFloatMinor,ref.requestHash,ref.platform,actor,"2026-11-12T00:00:00Z","Retire this saved uncommitted start")
        val proof=ReviewedStartWorkResolution(ref,StartWorkOutcome("cancelled",cancellation=cancel))
        var checkpointed=false
        assertTrue(runCatching{operations.coordinator.checkpointStartWorkReview(permit,proof,{true}){throw IOException("fsync")}}.isFailure)
        operations.coordinator.checkpointStartWorkReview(permit,proof,{true}){checkpointed=true}
        assertTrue(checkpointed);assertFalse(operations.coordinator.state.value.initialized)
        operations.coordinator.finishStartWorkReview(permit)
        assertTrue(operations.coordinator.state.value.activeOperations.isNotEmpty())
        operations.coordinator.endStartWorkReviewWire(permit)
        assertTrue(operations.coordinator.state.value.activeOperations.isEmpty())
    }

}
