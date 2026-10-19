package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.*
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.io.IOException
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Retrofit

class ReceiptHistorySearchTest {
    private val owner = CacheScopeLease(CacheScope("owner", "company", "branch", "terminal"), 1)
    private val filters = ReceiptHistoryFilters(dateFrom="2026-10-17",dateTo="2026-10-18",
        stationId="11111111-1111-1111-1111-111111111111",amountMinor=27000,method="upi",
        staffUserId="22222222-2222-2222-2222-222222222222")
    private fun receipt(id: String="receipt-1", company: String="company", branch: String="branch") = CanonicalReceipt(
        orderId=id,companyId=company,branchId=branch,terminalId="terminal",shiftId="shift",openedBy="owner",
        invoiceNo="QA51-$id",fiscalYear="2026-27",status="paid",orderType="takeaway",subtotalMinor=27000,
        discountMinor=0,manualDiscountMinor=0,pointsRedeemedMinor=0,cgstMinor=0,sgstMinor=0,igstMinor=0,
        cessMinor=0,taxMinor=0,roundOffMinor=0,tipMinor=0,totalMinor=27000,paidMinor=27000,
        refundedMinor=0,netCollectedMinor=27000,isReverseCharge=false,openedAt="2026-10-17T20:00:00Z",
        closedAt="2026-10-17T20:00:00Z",invoiceIssuedAt="2026-10-17T20:00:00Z",
        createdAt="2026-10-17T20:00:00Z",updatedAt="2026-10-17T20:00:00Z")
    private fun page(vararg rows: CanonicalReceipt, cursor: String?=null) = CanonicalReceiptPage(
        rows.toList(),cursor,cursor!=null,"invoice_issued_at_in_shop_timezone","Asia/Kolkata")
    private suspend fun idle(search: ReceiptHistorySearch) = withTimeout(3000) {
        while(search.state.value.loading || search.state.value.detailLoading) delay(1)
    }

    @Test fun fullFilterTupleAndCursorReachActualRetrofitRequestAndUnsupportedOldResponseRefuses()= runBlocking {
        MockWebServer().use { server ->
            val api=Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType())).build().create(ErpApi::class.java)
            server.enqueue(MockResponse().setBody("""{"items":[],"has_more":false,"shop_timezone":"Asia/Kolkata","date_filter_basis":"invoice_issued_at_in_shop_timezone"}"""))
            api.filteredReceiptHistory(filters.copy(query="QA51-1"),"scoped-older")
            val query=requireNotNull(server.takeRequest().requestUrl)
            mapOf("q" to "QA51-1","cursor" to "scoped-older","date_from" to filters.dateFrom,
                "date_to" to filters.dateTo,"station_id" to filters.stationId,"amount_minor" to "27000",
                "method" to "upi","staff_user_id" to filters.staffUserId).forEach { (key,value)->assertEquals(value,query.queryParameter(key)) }
            server.enqueue(MockResponse().setBody("""{"items":[],"has_more":false}"""))
            try { api.filteredReceiptHistory(filters,null); fail("An older server cannot silently ignore the filters") }
            catch(_:IllegalArgumentException) { }
        }
    }

    @Test fun filterOnlySearchAndOlderUseSameAppliedTupleAndRefreshRetainsWholeLoadedWindow()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val calls=mutableListOf<Pair<ReceiptHistoryFilters,String?>>()
            var failRefresh=false
            val selected=filters.copy(stationId=null,method=null,staffUserId=null)
            val search=ReceiptHistorySearch(tasks,{true},{owner},{_,write->write();true},{emptyList<CanonicalReceipt>() to null},
                { f,c -> calls+=f to c; if(failRefresh) throw IOException("Offline now")
                    if(c==null) CanonicalReceiptPage(List(50){receipt("one-$it")},"older",true,"invoice_issued_at_in_shop_timezone","Asia/Kolkata") else page(receipt("two")) },{receipt(it)})
            search.find(selected);idle(search);search.more();idle(search)
            assertEquals(51,search.state.value.receipts.size)
            assertEquals(listOf(selected to null,selected to "older"),calls)
            search.detail("one-0");idle(search)
            search.find(selected);idle(search)
            assertEquals(51,search.state.value.receipts.size)
            assertEquals("one-0",search.state.value.detail?.orderId)
            assertEquals(4,calls.size)
            failRefresh=true;search.find(selected);idle(search)
            assertEquals(51,search.state.value.receipts.size);assertEquals("one-0",search.state.value.detail?.orderId)
            assertEquals(selected,search.state.value.appliedCriteria);assertEquals("Offline now",search.state.value.error)
        } finally {tasks.cancel()}
    }

    @Test fun changedFilterFailureKeepsOldAppliedResultsAndCannotPageWithNewFilter()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            var calls=0
            val search=ReceiptHistorySearch(tasks,{true},{owner},{_,write->write();true},{emptyList<CanonicalReceipt>() to null},
                { f,_->calls++;if(f.method=="cash") throw IOException("Unavailable") else page(receipt()) },{receipt(it)})
            search.find(filters);idle(search)
            search.find(filters.copy(method="cash"));idle(search);search.more();yield()
            assertEquals(2,calls);assertEquals(filters,search.state.value.appliedCriteria)
            assertEquals("cash",search.state.value.criteria.method);assertEquals(1,search.state.value.receipts.size)
        } finally {tasks.cancel()}
    }

    @Test fun staleAccountAndLatePreviousFilterCannotReplaceCurrentResults()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val oldReply=CompletableDeferred<Unit>();var active=owner
            val search=ReceiptHistorySearch(tasks,{true},{active},{lease,write->if(lease==active){write();true}else false},
                {emptyList<CanonicalReceipt>() to null}, { f,_->
                    if(f.query=="old") withContext(NonCancellable){oldReply.await();page(receipt("old"))} else page(receipt("new")) },{receipt(it)})
            search.find(ReceiptHistoryFilters(query="old"));yield()
            search.find(ReceiptHistoryFilters(query="new"));idle(search);oldReply.complete(Unit);yield()
            assertEquals("new",search.state.value.receipts.single().orderId)
            active=CacheScopeLease(owner.scope.copy(userId="other"),2)
            search.detail("wrong");idle(search)
            assertTrue(search.state.value.receipts.isEmpty());assertNull(search.state.value.detail)
        } finally {tasks.cancel()}
    }

    @Test fun wrongTenantAndWrongExactReceiptAreRejectedAndCompanyWideBranchRemainsValid()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            var foreign=false
            val companyLease=owner.copy(scope=owner.scope.copy(branchId=null))
            val search=ReceiptHistorySearch(tasks,{true},{companyLease},{_,write->write();true},{emptyList<CanonicalReceipt>() to null},
                { _,_->page(receipt(branch="different-authorized-branch")) },
                { if(foreign) receipt(company="foreign") else receipt("not-requested") })
            search.find(ReceiptHistoryFilters(query="QA51"));idle(search)
            assertEquals("different-authorized-branch",search.state.value.receipts.single().branchId)
            search.detail("receipt-1");idle(search);assertNull(search.state.value.detail);assertNotNull(search.state.value.detailError)
            foreign=true;search.detail("receipt-1");idle(search);assertNull(search.state.value.detail)
            assertEquals(1,search.state.value.receipts.size)
        } finally {tasks.cancel()}
    }

    @Test fun offlineAdvancedFilterDoesNotPretendToSearchServerAndQueryCacheIsScoped()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val search=ReceiptHistorySearch(tasks,{false},{owner},{_,write->write();true},
                {listOf(receipt(),receipt(company="foreign")) to 123L},{_,_->error("Must not call server offline")},{error("Must not call server")})
            search.find(ReceiptHistoryFilters(query="receipt-1"));assertEquals(1,search.state.value.receipts.size)
            assertEquals(ReceiptSearchSource.CACHED,search.state.value.source)
            search.find(filters);assertEquals(1,search.state.value.receipts.size)
            assertNotEquals(filters,search.state.value.appliedCriteria);assertTrue(search.state.value.error!!.contains("no full-history search"))
        } finally {tasks.cancel()}
    }

    @Test fun effectiveMethodAndRecorderMustMatchSameLegAndDatesUseShopZone() {
        fun payment(id:String,method:String,recorder:String)=CanonicalReceiptPayment(id,"shift",method,100,paidAt="2026-10-17T20:00:00Z",recordedBy=recorder,createdAt="2026-10-17T20:00:00Z")
        val row=receipt().copy(payments=listOf(payment("a","cash","one"),payment("b","upi","two")))
        assertFalse(receiptMatchesAdvancedFilters(row,ReceiptHistoryFilters(method="upi",staffUserId="one"),"Asia/Kolkata"))
        assertTrue(receiptMatchesAdvancedFilters(row,ReceiptHistoryFilters(method="upi",staffUserId="two",dateFrom="2026-10-18",dateTo="2026-10-18"),"Asia/Kolkata"))
        assertFalse(receiptMatchesAdvancedFilters(row,ReceiptHistoryFilters(dateFrom="2026-10-18"),"UTC"))
        assertFalse(receiptMatchesAdvancedFilters(row,ReceiptHistoryFilters(amountMinor=1),"Asia/Kolkata"))
    }


    @Test fun exactEffectiveCorrectionRefreshKeepsDetailButRemovesNoLongerMatchingFilteredRow()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val cash=CanonicalReceiptPayment("p","shift","cash",27000,paidAt="2026-10-17T20:00:00Z",createdAt="2026-10-17T20:00:00Z")
            val original=receipt().copy(payments=listOf(cash))
            val corrected=original.copy(payments=listOf(cash.copy(method="upi",originalMethod="cash",tenderCorrectionId="correction")))
            val search=ReceiptHistorySearch(tasks,{true},{owner},{_,write->write();true},{emptyList<CanonicalReceipt>() to null},
                {_,_->page(original)},{corrected})
            search.find(ReceiptHistoryFilters(method="cash"));idle(search)
            search.detail(original.orderId);idle(search)
            assertTrue(search.state.value.receipts.isEmpty())
            assertEquals(corrected,search.state.value.detail)
            assertEquals("cash",search.state.value.appliedCriteria?.method)
        } finally {tasks.cancel()}
    }

    @Test fun scopedInvestigationRejectsWrongOrderTenantOrAssignedBranchAndKeepsVerifiedShopZone()=runBlocking {
        MockWebServer().use { server ->
            val api=Retrofit.Builder().baseUrl(server.url("/api/v1/"))
                .addConverterFactory(ApiClient.json.asConverterFactory("application/json".toMediaType())).build().create(ErpApi::class.java)
            fun reply(row:CanonicalReceipt) {
                val data=ReceiptInvestigation(row,emptyList(),emptyList(),emptyList(),emptyList(),"Legacy proof explicit","invoice_issued_at_in_shop_timezone","Asia/Kolkata")
                server.enqueue(MockResponse().setBody(ApiClient.json.encodeToString(ReceiptInvestigation.serializer(),data)))
            }
            for(row in listOf(receipt("wrong"),receipt(company="foreign"),receipt(branch="other"))) {
                reply(row)
                try {api.scopedReceiptInvestigation("receipt-1",owner);fail("Mismatched investigation must refuse")}
                catch(_:IllegalArgumentException) { }
            }
            reply(receipt(branch="other"))
            val result=api.scopedReceiptInvestigation("receipt-1",owner.copy(scope=owner.scope.copy(branchId=null)))
            assertEquals("Asia/Kolkata",result.shopTimezone);assertEquals("other",result.receipt.branchId)
        }
    }

    @Test fun invalidFilterDoesNotRequestAndRepeatedOlderCursorKeepsAllLoadedRows()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            var calls=0
            val search=ReceiptHistorySearch(tasks,{true},{owner},{_,write->write();true},{emptyList<CanonicalReceipt>() to null},
                {_,cursor->calls++;if(cursor==null) CanonicalReceiptPage(List(50){receipt("row-$it")},"older",true) else page(receipt("next"),cursor="older") },{receipt(it)})
            search.find(ReceiptHistoryFilters(dateFrom="2026-02-30"));assertEquals(0,calls)
            search.find(ReceiptHistoryFilters(query="QA"));idle(search)
            search.more();idle(search)
            assertEquals(50,search.state.value.receipts.size);assertEquals("older",search.state.value.nextCursor)
            assertTrue(search.state.value.error!!.contains("did not advance"))
        } finally {tasks.cancel()}
    }

    @Test fun onlyResolvedShiftBlockerNoticeIsCleared() {
        val notice=kotlinx.coroutines.flow.MutableStateFlow<String?>(POS_NO_OPEN_SHIFT_MESSAGE)
        clearResolvedNoShiftNotice(notice,false);assertEquals(POS_NO_OPEN_SHIFT_MESSAGE,notice.value)
        clearResolvedNoShiftNotice(notice,true);assertNull(notice.value)
        notice.value="Payment failed; keep exact pending action";clearResolvedNoShiftNotice(notice,true)
        assertEquals("Payment failed; keep exact pending action",notice.value)
    }
    @Test fun namedChoicesRespectDirectoryPermissionsAndKeepInactiveHistoryChoices() {
        val account=MeResponse("owner","qa@example.test","QA Owner",companyId="company",effectivePermissions=emptyList())
        val stations=listOf(cloud.dcompany.erp.core.db.GamingStationEntity("station","PS5-1","PlayStation 1","ps5",0,false))
        val staff=listOf(cloud.dcompany.erp.core.db.StaffCacheEntity("staff","staff@example.test","QA Staff",null,"inactive","staff",null))
        val limited=buildReceiptFilterChoices(account,emptyList(),stations,staff)
        assertTrue(limited.stations.isEmpty());assertEquals(listOf("owner"),limited.staff.map {it.id});assertFalse(limited.staffDirectoryAvailable)
        val allowed=buildReceiptFilterChoices(account.copy(effectivePermissions=listOf("staff.read","gaming.read")),emptyList(),stations,staff)
        assertTrue(allowed.staffDirectoryAvailable);assertTrue(allowed.staff.any {it.id=="staff" && it.label.contains("inactive")})
        assertTrue(allowed.stations.single().label.contains("disabled"));assertTrue(allowed.stations.single().label.contains("current"))
    }

    @Test fun olderWindowReplyCannotUndoNewerExactCorrectedReceiptOrFilteredMembership()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val cash=CanonicalReceiptPayment("p","shift","cash",27000,paidAt="2026-10-17T20:00:00Z",createdAt="2026-10-17T20:00:00Z")
            val original=receipt().copy(payments=listOf(cash))
            val corrected=original.copy(payments=listOf(cash.copy(method="upi",originalMethod="cash",tenderCorrectionId="correction")))
            val olderReply=CompletableDeferred<Unit>();var reads=0
            val search=ReceiptHistorySearch(tasks,{true},{owner},{_,write->write();true},{emptyList<CanonicalReceipt>() to null},
                {_,_->if(++reads>1) withContext(NonCancellable){olderReply.await();page(original)} else page(original)}, {corrected})
            search.find(ReceiptHistoryFilters(method="cash"));idle(search)
            search.find(ReceiptHistoryFilters(method="cash"));yield()
            search.detail(original.orderId);idle(search)
            assertEquals("upi",search.state.value.detail?.payments?.single()?.method)
            assertTrue(search.state.value.receipts.isEmpty())
            olderReply.complete(Unit);yield();delay(5)
            assertEquals(corrected,search.state.value.detail);assertTrue(search.state.value.receipts.isEmpty())
            assertFalse(search.state.value.loading)
        } finally {tasks.cancel()}
    }

}
