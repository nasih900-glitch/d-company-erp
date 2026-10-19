package cloud.dcompany.erp.ui.screens.audit

import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.screens.staff.StaffApi
import cloud.dcompany.erp.ui.screens.staff.StaffUser
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AuditSnapshotStateTest {
    private fun row(id:Long)=AuditEntry(id=id,action="recorded",entityType="Payment",entityId="p-$id",createdAt="2026-10-18T10:00:00Z")
    private fun page(vararg ids:Long,more:Boolean=false,new:Boolean=false)=AuditEventsPage(
        entries=ids.map(::row),nextCursor=if(more) "older" else null,activityCursor="activity",hasMore=more,
        snapshotMaxId=100,loadedCount=ids.size,matchingCount=10,catalogueVersion=1,shopTimezone="Asia/Kolkata",
        dateFilterBasis="server_recorded_shop_date",countBasis="events_not_revenue",newEntriesAvailable=new,
        snapshotExpiresIn=600,scope="company_wide_protected_owner")
    private class Api : AuditLogApi {
        var grant=0
        var expires=600
        var next:AuditEventsPage?=null
        var failure:Exception?=null
        var block=false
        var blocked:Continuation<AuditEventsPage>?=null
        var metadataBlock=false
        var metadata:Continuation<AuditCatalogue>?=null
        val calls=mutableListOf<Map<String,String?>>()
        override suspend fun unlock(body:AuditUnlockRequest)=AuditUnlockResponse("grant-${++grant}",expires)
        override suspend fun catalogue(auditToken:String):AuditCatalogue {
            if(metadataBlock) { metadataBlock=false; return suspendCoroutine { metadata=it } }
            return catalogue()
        }
        fun catalogue()=AuditCatalogue(1,listOf("pos"),listOf("completed"),listOf("business","all"),emptyList())
        override suspend fun events(auditToken:String,limit:Int,cursor:String?,view:String,code:String?,category:String?,outcome:String?,actorUserId:String?,targetUserId:String?,reference:String?,dateFrom:String?,dateTo:String?,query:String?):AuditEventsPage {
            calls+=mapOf("token" to auditToken,"cursor" to cursor,"view" to view,"code" to code,"category" to category,
                "outcome" to outcome,"actor" to actorUserId,"target" to targetUserId,"reference" to reference,
                "from" to dateFrom,"to" to dateTo,"q" to query)
            if(block) { block=false; return suspendCoroutine { blocked=it } }
            failure?.let{failure=null;throw it}
            return requireNotNull(next)
        }
        override suspend fun list(auditToken:String,limit:Int,beforeId:Long?,area:String?,entityType:String?,action:String?,query:String?):List<AuditEntry> = error("Legacy paging must not be used")
        override suspend fun facets(auditToken:String)=AuditFacets()
    }
    private val staff=Proxy.newProxyInstance(StaffApi::class.java.classLoader,arrayOf(StaffApi::class.java)){_,method,_->
        if(method.name=="list") emptyList<StaffUser>() else error("Unexpected Staff call ${method.name}")
    } as StaffApi
    private fun vm(api:Api,scope:CoroutineScope,clock:()->Long={System.nanoTime()/1_000_000})=AuditLogViewModel(api,staff,scope,clock).also{it.bindIdentity("owner","company","branch",true)}

    @Test fun activityCheckReusesCursorAndNeverReplacesLoadedPagesOrSelectedDetail() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,99,more=true)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            api.next=page(98,97)
            vm.loadMore();vm.select(vm.state.value.entries.last())
            val loaded=vm.state.value.entries
            api.next=page(96,new=true)
            repeat(10){vm.checkActivity()}
            assertEquals(loaded,vm.state.value.entries)
            assertEquals(97L,vm.state.value.selected?.id)
            assertTrue(vm.state.value.newEntriesAvailable)
            assertTrue(api.calls.drop(2).all{it["cursor"]=="activity"})
        } finally { scope.cancel() }
    }

    @Test fun fullFilterTupleIsAppliedToOlderAndActivityRequests() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,more=true)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            vm.changeFilters{AuditFilters(view="all",category="pos",code="POS-PAY",outcome="completed",actorUserId="actor",targetUserId="target",reference=" D/MN/26/10 ",dateFrom="2026-10-17",dateTo="2026-10-18",query=" payment ")}
            vm.find();vm.loadMore();vm.checkActivity()
            api.calls.drop(1).forEach{call->
                assertEquals("all",call["view"]);assertEquals("pos",call["category"]);assertEquals("POS-PAY",call["code"])
                assertEquals("completed",call["outcome"]);assertEquals("actor",call["actor"]);assertEquals("target",call["target"])
                assertEquals("D/MN/26/10",call["reference"]);assertEquals("2026-10-17",call["from"]);assertEquals("2026-10-18",call["to"]);assertEquals("payment",call["q"])
            }
        } finally { scope.cancel() }
    }

    @Test fun lockDuringDeferredOlderLoadResetsBusyAndRejectsLateResponseAfterFreshUnlock() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,99,more=true)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            api.block=true;vm.loadMore();assertTrue(vm.state.value.loadingMore)
            val pending=requireNotNull(api.blocked)
            vm.lock();assertTrue(vm.state.value.locked);assertFalse(vm.state.value.loadingMore)
            vm.unlock("synthetic-password");assertFalse(vm.state.value.locked)
            pending.resume(page(12))
            assertEquals(listOf(100L,99L),vm.state.value.entries.map{it.id})
            assertFalse(vm.state.value.loadingMore);assertFalse(vm.state.value.unlocking)
        } finally { scope.cancel() }
    }

    @Test fun emptySnapshotCheckNeverAllocatesAFirstPageAgain() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page()}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            repeat(10){vm.checkActivity()}
            assertEquals(1,api.calls.count{it["cursor"]==null})
            assertTrue(api.calls.drop(1).all{it["cursor"]=="activity"})
            assertTrue(vm.state.value.entries.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun expiredSnapshotAndServiceFailureKeepLoadedEvidenceAndExplainRefresh() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,99,more=true)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            api.failure=ApiException("Audit history snapshot expired. Refresh to load current activity.",422,"business_rule")
            vm.checkActivity()
            assertTrue(vm.state.value.snapshotExpired);assertEquals(2,vm.state.value.entries.size)
            api.failure=ApiException("Service unavailable",503)
            vm.refresh()
            assertEquals(2,vm.state.value.entries.size);assertNotNull(vm.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun changedFiltersNeverRelabelPreviousRowsWhenNewSearchFails() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            vm.changeFilters{it.copy(dateFrom="invalid")}
            api.failure=ApiException("Invalid date",422)
            vm.find()
            assertTrue(vm.state.value.entries.isEmpty());assertNull(vm.state.value.page)
            assertNotNull(vm.state.value.error)
        } finally { scope.cancel() }
    }

    @Test fun expiryDuringMetadataUnlockResetsBusyAndLateMetadataCannotUnlock() = runBlocking {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100);it.expires=1;it.metadataBlock=true}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            assertTrue(vm.state.value.unlocking)
            val late=requireNotNull(api.metadata)
            delay(1100)
            assertTrue(vm.state.value.locked);assertFalse(vm.state.value.unlocking)
            late.resume(api.catalogue())
            assertTrue(vm.state.value.locked)
            api.expires=600;vm.unlock("synthetic-password")
            assertFalse(vm.state.value.locked);assertFalse(vm.state.value.unlocking)
        } finally { scope.cancel() }
    }

    @Test fun requestedBillDuringOlderLoadIsAppliedOnlyAfterThatRequestSettles() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,more=true)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            api.block=true;vm.loadMore()
            val old=requireNotNull(api.blocked)
            vm.reference("D/MN/26/11")
            assertEquals("",vm.state.value.filters.reference)
            api.next=page(50)
            old.resume(page(99))
            assertEquals("D/MN/26/11",vm.state.value.appliedFilters.reference)
            assertEquals(listOf(50L),vm.state.value.entries.map{it.id})
            assertEquals("D/MN/26/11",api.calls.last()["reference"])
        } finally { scope.cancel() }
    }

    @Test fun revokedAccessDuringUnlockCannotRestoreProtectedRowsFromLateMetadata() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100);it.metadataBlock=true}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            val late=requireNotNull(api.metadata)
            vm.bindIdentity("owner","company","branch",false)
            late.resume(api.catalogue())
            assertFalse(vm.state.value.allowed);assertTrue(vm.state.value.locked)
            assertFalse(vm.state.value.unlocking);assertTrue(vm.state.value.entries.isEmpty())
            assertTrue(api.calls.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun explicitRefreshResetsViewportWhileAnActivityCheckKeepsIt() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,99)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            vm.rememberViewport(1,20)
            vm.checkActivity()
            assertEquals(1,vm.state.value.scrollIndex);assertEquals(20,vm.state.value.scrollOffset)
            val reset=vm.state.value.viewportReset
            vm.refresh()
            assertEquals(0,vm.state.value.scrollIndex);assertEquals(0,vm.state.value.scrollOffset)
            assertEquals(reset+1,vm.state.value.viewportReset)
        } finally { scope.cancel() }
    }

    @Test fun staleRenderedUnlockAndRowCallbacksCannotTargetAnotherAccount() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100)}
            val vm=vm(api,scope)
            val oldOwner=vm.state.value.ownerScopeKey
            vm.unlock("synthetic-password",oldOwner)
            val oldRow=vm.state.value.entries.single()
            vm.bindIdentity("other-owner","company","branch",true)
            val grants=api.grant
            vm.unlock("prior-account-input",oldOwner)
            assertEquals(grants,api.grant);assertTrue(vm.state.value.locked)
            api.next=page(200);vm.unlock("current-account-input")
            vm.select(oldRow)
            assertNull(vm.state.value.selected);assertEquals(listOf(200L),vm.state.value.entries.map{it.id})
        } finally { scope.cancel() }
    }

    @Test fun elapsedDeadlineExpiresDuringSleepWithoutAdvancingTheCoroutineTimer() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var elapsed=1_000L
            val api=Api().also{it.next=page(100,99,more=true)}
            val vm=vm(api,scope){elapsed};vm.unlock("synthetic-password")
            vm.select(vm.state.value.entries.last())
            api.block=true;vm.loadMore()
            val late=requireNotNull(api.blocked)
            elapsed+=600_001
            assertFalse(vm.verificationIsCurrent())
            vm.checkVerification()
            assertTrue(vm.state.value.locked);assertFalse(vm.state.value.loadingMore)
            assertTrue(vm.state.value.entries.isEmpty());assertNull(vm.state.value.selected)
            late.resume(page(12))
            assertTrue(vm.state.value.locked);assertTrue(vm.state.value.entries.isEmpty())
            api.next=page(200);vm.unlock("synthetic-password")
            assertFalse(vm.state.value.locked)
        } finally { scope.cancel() }
    }

    @Test fun elapsedDeadlineAlsoRefusesLateUnlockMetadataBeforeItCanRender() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            var elapsed=1_000L
            val api=Api().also{it.next=page(100);it.metadataBlock=true}
            val vm=vm(api,scope){elapsed};vm.unlock("synthetic-password")
            val late=requireNotNull(api.metadata)
            elapsed+=600_001
            late.resume(api.catalogue())
            assertTrue(vm.state.value.locked);assertFalse(vm.state.value.unlocking)
            assertTrue(api.calls.isEmpty());assertTrue(vm.state.value.entries.isEmpty())
        } finally { scope.cancel() }
    }

    @Test fun sameOwnerUnlockRestoresReturnStateButAnotherAccountHasNoRowsOrFilters() {
        val scope=CoroutineScope(SupervisorJob()+Dispatchers.Unconfined)
        try {
            val api=Api().also{it.next=page(100,99,more=true)}
            val vm=vm(api,scope);vm.unlock("synthetic-password")
            vm.changeFilters{it.copy(reference="D/MN/26/10")};vm.find()
            vm.select(vm.state.value.entries.last());vm.rememberViewport(1,20)
            vm.lock();assertTrue(vm.state.value.entries.isEmpty())
            vm.unlock("synthetic-password")
            assertEquals("D/MN/26/10",vm.state.value.appliedFilters.reference)
            assertEquals(99L,vm.state.value.selected?.id);assertEquals(1,vm.state.value.scrollIndex)
            vm.bindIdentity("other-owner","company","branch",true)
            assertTrue(vm.state.value.locked);assertTrue(vm.state.value.entries.isEmpty())
            assertEquals(AuditFilters(),vm.state.value.filters)
        } finally { scope.cancel() }
    }
}
