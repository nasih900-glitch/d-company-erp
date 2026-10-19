package cloud.dcompany.erp.ui.screens.finance

import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import java.io.IOException
import java.lang.reflect.Proxy
import kotlin.coroutines.Continuation
import kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class TenderWorkspaceStateTest {
    private val company = "11111111-1111-1111-1111-111111111111"
    private val owner = "22222222-2222-2222-2222-222222222222"
    private val branch = "33333333-3333-3333-3333-333333333333"
    private val payment = "44444444-4444-4444-4444-444444444444"
    private val secondPayment = "55555555-5555-5555-5555-555555555555"
    private val account = MeResponse(owner,"qa@example.test","Owner",listOf("owner"),auditAccess=true,companyId=company,branchId=branch,effectivePermissions=listOf("finance.tender.correct"))
    private val source get() = TenderContext.Payment(branch,payment,"QA51-1")
    private val scope get() = requireNotNull(account.tenderScope(branch))
    private val fingerprint = "a".repeat(64)
    private class Storage : TenderJournalStorage {
        val rows = mutableMapOf<String,String>()
        var failRead = false
        var failPut = false
        var failRemove = false
        override fun read(key:String):String? { check(!failRead){"Read denied"}; return rows[key] }
        override fun put(key:String,value:String):Boolean { rows[key]=value; return !failPut }
        override fun remove(key:String):Boolean { if(failRemove) return false; rows.remove(key);return true }
    }
    private inner class Api {
        var unlocks=0
        var commitFailure:Exception?=null
        var blockCommit=false
        var blocked:Continuation<TenderReceipt>?=null
        var blockedRow:TenderReceipt?=null
        val commits=mutableListOf<Pair<String,Any>>()
        val historyCalls=mutableListOf<List<Any?>>()
        val api=Proxy.newProxyInstance(FinanceApi::class.java.classLoader,arrayOf(FinanceApi::class.java)){_,method,args->
            val a=args.orEmpty()
            when(method.name) {
                "unlockTender" -> { unlocks++;TenderUnlockResponse("action-$unlocks","history-$unlocks",300) }
                "tenderHistory" -> { historyCalls+=a.dropLast(1);emptyList<TenderReceipt>() }
                "preparePaymentTender" -> {
                    val body=a[1] as PaymentTenderPrepare
                    PaymentTenderPreview(body.paymentId,"66666666-6666-6666-6666-666666666666","QA51-1",branch,"2026-10-17","Asia/Kolkata",fingerprint,27000,"cash","upi",33000,8000,6000,35000,0,"owner_attestation",true)
                }
                "prepareDailyTender" -> dailyPreview()
                "commitPaymentTender","commitDailyTender" -> {
                    val key=a[1] as String
                    val body=requireNotNull(a[2])
                    commits+=key to body
                    val row=receipt(key,body)
                    @Suppress("UNCHECKED_CAST") val continuation=a.last() as Continuation<TenderReceipt>
                    val error=commitFailure
                    when {
                        blockCommit -> { blockCommit=false;blocked=continuation;blockedRow=row;COROUTINE_SUSPENDED }
                        error!=null -> { continuation.resumeWithException(error);COROUTINE_SUSPENDED }
                        else -> row
                    }
                }
                else -> error("Unexpected finance API method ${method.name}")
            }
        } as FinanceApi
        private fun receipt(key:String,body:Any):TenderReceipt {
            val common=TenderReceipt("77777777-7777-7777-7777-777777777777","TC-QA51","payment_method_corrected",true,company,branch,"2026-10-17","Asia/Kolkata",owner,"2026-10-19T10:00:00Z",
                (body as? PaymentTenderCommit)?.reason ?: (body as DailyTenderCommit).reason,"owner_attestation",sourceFingerprint=fingerprint,idempotencyKey=key,sourceAllocation="identified_payment",totalDifferenceMinor=0)
            return when(body) {
                is PaymentTenderCommit -> common.copy(paymentId=body.paymentId,originalMethod="cash",effectiveMethod="upi",amountMinor=27000,evidenceReference=body.evidenceReference)
                is DailyTenderCommit -> {
                    val difference=body.actualCashMinor+body.actualUpiMinor-41000
                    common.copy(operation=if(difference==0L)"daily_collections_reconciled" else "daily_actuals_recorded",financialEffect=difference==0L,
                        sourceAllocation="unallocated_owner_attestation",kind=if(difference==0L)"applied" else "unresolved",totalDifferenceMinor=difference,
                        beforeCashMinor=33000,beforeUpiMinor=8000,actualCashMinor=body.actualCashMinor,actualUpiMinor=body.actualUpiMinor,
                        cashDeltaMinor=if(difference==0L)body.actualCashMinor-33000 else 0,upiDeltaMinor=if(difference==0L)body.actualUpiMinor-8000 else 0,evidenceReference=body.evidenceReference)
                }
                else -> error("Unexpected body")
            }
        }
    }
    private fun dailyPreview()=DailyTenderPreview(branch,"2026-10-17","Asia/Kolkata",fingerprint,33000,8000,41000,"cash_upi_only",emptyMap(),41000,"unallocated_owner_attestation",false)
    private suspend fun idle(vm:TenderWorkspaceViewModel) = withTimeout(5000) { while(vm.state.value.busy || vm.state.value.readingSavedWork) delay(5) }
    private suspend fun ready(vm:TenderWorkspaceViewModel) { idle(vm); vm.unlock("synthetic-owner-password",scope.ownerKey());idle(vm) }
    private suspend fun preview(vm:TenderWorkspaceViewModel) { vm.edit(reason="Owner confirmed method");vm.prepare();idle(vm);vm.confirm(true) }

    @Test fun lostReplyRetainsExactBodyAndKeyUntilExplicitRecovery()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val storage=Storage();val journal=TenderJournal(storage)
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,account);ready(vm);preview(vm)
            api.commitFailure=IOException("Reply lost")
            vm.commit();idle(vm)
            val pending=requireNotNull(vm.state.value.pending)
            assertEquals(pending,journal.read(scope));assertNull(vm.state.value.reviewedRejection)
            assertEquals(1,api.unlocks);assertEquals("history-1",api.historyCalls.single().first())
            api.commitFailure=null
            vm.recover(pending);idle(vm)
            assertEquals(api.commits[0],api.commits[1]);assertNull(journal.read(scope))
            assertNotNull(vm.state.value.receipt);assertNull(vm.state.value.pending)
            assertTrue(storage.rows.values.none{it.contains("synthetic-owner-password") || it.contains("action-1")})
        } finally { tasks.cancel() }
    }

    @Test fun anotherViewClearingDiskNeverTurnsRetainedRecoveryIntoAFreshAction()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val journal=TenderJournal(Storage())
            val a=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            a.bind(source,account);ready(a);preview(a)
            api.commitFailure=IOException("Reply lost");a.commit();idle(a)
            val pending=requireNotNull(a.state.value.pending)
            val b=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            b.bind(source,account);ready(b)
            api.commitFailure=null;b.recover(requireNotNull(b.state.value.pending));idle(b)
            assertNull(journal.read(scope))
            a.recover(pending);idle(a)
            assertEquals(3,api.commits.size)
            assertTrue(api.commits.all{it==api.commits.first()});assertNotNull(a.state.value.receipt)
        } finally { tasks.cancel() }
    }

    @Test fun confirmForOneReceiptCannotAdoptAnotherSavedSource()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val journal=TenderJournal(Storage())
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,account);ready(vm);preview(vm)
            val other=journal.reserve(paymentTenderCommand(scope,PaymentTenderCommit(secondPayment,fingerprint,"Different source"),"different-source",1000))
            vm.commit();idle(vm)
            assertTrue(api.commits.isEmpty());assertEquals(other,vm.state.value.pending)
            assertNull(vm.state.value.paymentPreview);assertFalse(vm.state.value.confirmed)
        } finally { tasks.cancel() }
    }

    @Test fun failedDurableSaveSendsNothingAndRetryKeepsTheCapturedIdentity()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val storage=Storage();val journal=TenderJournal(storage)
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,account);ready(vm);preview(vm)
            storage.failPut=true;vm.commit();idle(vm)
            val saved=requireNotNull(vm.state.value.pending)
            assertTrue(api.commits.isEmpty());assertNotNull(vm.state.value.storageError)
            storage.failPut=false;vm.recover(saved);idle(vm)
            assertEquals(saved.idempotencyKey,api.commits.single().first)
            assertNotNull(vm.state.value.receipt)
        } finally { tasks.cancel() }
    }

    @Test fun elapsedExpiryDuringDeferredCommitRetainsUnknownWorkAndUnlockDoesNotAutoSend()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            var elapsed=1000L
            val api=Api();val journal=TenderJournal(Storage())
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{elapsed},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,account);ready(vm);preview(vm)
            api.blockCommit=true;vm.commit()
            withTimeout(5000){while(api.blocked==null)delay(5)}
            val pending=requireNotNull(vm.state.value.pending)
            elapsed+=300001;assertFalse(vm.verificationIsCurrent());vm.checkVerification()
            assertFalse(vm.state.value.busy);assertNull(vm.state.value.receipt)
            requireNotNull(api.blocked).resume(requireNotNull(api.blockedRow))
            delay(10);assertEquals(pending,journal.read(scope))
            vm.unlock("synthetic-owner-password",scope.ownerKey());idle(vm)
            assertEquals(1,api.commits.size);assertEquals(pending,vm.state.value.pending)
            vm.recover(pending);idle(vm);assertEquals(2,api.commits.size)
            assertEquals(api.commits[0],api.commits[1]);assertNotNull(vm.state.value.receipt)
        } finally { tasks.cancel() }
    }

    @Test fun accountSwitchHidesLateReceiptAndKeepsOriginalOwnersJournal()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            var active=account
            val api=Api();val journal=TenderJournal(Storage())
            val vm=TenderWorkspaceViewModel(api.api,journal,{active},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,active);ready(vm);preview(vm)
            api.blockCommit=true;vm.commit()
            withTimeout(5000){while(api.blocked==null)delay(5)}
            val pending=requireNotNull(vm.state.value.pending)
            active=account.copy(userId="88888888-8888-8888-8888-888888888888")
            vm.bind(source,active);idle(vm)
            requireNotNull(api.blocked).resume(requireNotNull(api.blockedRow));delay(10)
            assertNull(vm.state.value.pending);assertNull(vm.state.value.receipt)
            assertFalse(vm.verificationIsCurrent());assertEquals(pending,journal.read(scope))
            val count=api.unlocks;vm.unlock("prior-password",scope.ownerKey());assertEquals(count,api.unlocks)
            active=account;vm.bind(source,active);idle(vm)
            assertEquals(pending,vm.state.value.pending);assertEquals(1,api.commits.size)
        } finally { tasks.cancel() }
    }

    @Test fun corruptSavedWorkNeverBecomesAnEmptySlotOrAFreshWrite()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val storage=Storage().also{it.failRead=true}
            val vm=TenderWorkspaceViewModel(api.api,TenderJournal(storage),{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,account);ready(vm)
            assertFalse(vm.state.value.savedWorkVerified);assertNotNull(vm.state.value.storageError)
            vm.prepare();vm.confirm(true);vm.commit();idle(vm)
            assertTrue(api.commits.isEmpty());assertNull(vm.state.value.paymentPreview)
        } finally { tasks.cancel() }
    }

    @Test fun authenticationAndRetryableConflictsNeverOfferDiscard()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            for(failure in listOf(ApiException("Expired",401),ApiException("Forbidden",403),ApiException("Retry later",409,"conflict",retryable=true),IOException("Offline"))) {
                val api=Api();val journal=TenderJournal(Storage())
                val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
                vm.bind(source,account);ready(vm);preview(vm)
                api.commitFailure=failure;vm.commit();idle(vm)
                assertNotNull(vm.state.value.pending);assertNull(vm.state.value.reviewedRejection)
                assertNotNull(journal.read(scope))
            }
        } finally { tasks.cancel() }
    }

    @Test fun reviewedRejectionStorageFailureStaysInlineAndPreservesCommand()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val storage=Storage();val journal=TenderJournal(storage)
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->emptyList()})
            vm.bind(source,account);ready(vm);preview(vm)
            api.commitFailure=ApiException("Source changed",409,"conflict",retryable=false)
            vm.commit();idle(vm)
            val pending=requireNotNull(vm.state.value.pending)
            assertEquals(pending,vm.state.value.reviewedRejection)
            storage.failRemove=true;vm.acknowledgeRejected(pending);idle(vm)
            assertEquals(pending,journal.read(scope));assertNotNull(vm.state.value.storageError)
            assertEquals(1,api.commits.size)
        } finally { tasks.cancel() }
    }

    @Test fun mixedRailsAttestOnlyCashAndUpiWhileOtherRailsRemainUnchanged() {
        val preview=dailyPreview().copy(recordedCashMinor=10000,recordedUpiMinor=10000,recordedTotalMinor=20000,unchangedOtherRails=mapOf("card" to 50000),recordedAllRailsTotalMinor=70000)
        val review=requireNotNull(dailyActualsReview(preview,"80","120"))
        assertEquals(0L,review.totalDifference);assertEquals(-2000L,review.cashDelta);assertEquals(2000L,review.upiDelta)
        assertTrue(review.supportedTransfer);assertEquals(50000L,preview.unchangedOtherRails["card"])
        assertNull(dailyActualsReview(preview,"-1","120"));assertNull(dailyActualsReview(preview,"1.001","120"))
    }
    @Test fun acceptedReplayRefreshesReadersAndReadFailureNeverBecomesAnotherWrite()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val journal=TenderJournal(Storage())
            var refreshes=0;var failRefresh=true
            val notices=mutableListOf<TenderReceipt>()
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},
                refreshReaders={owner,row-> assertEquals(scope,owner);assertEquals("payment_method_corrected",row.operation);refreshes++
                    if(failRefresh) throw IOException("Read failed") else emptyList() },
                notifyReaders={owner,row->assertEquals(scope,owner);notices+=row})
            vm.bind(source,account);ready(vm);preview(vm)
            api.commitFailure=IOException("Accepted reply lost");vm.commit();idle(vm)
            assertEquals(0,refreshes);assertTrue(notices.isEmpty())
            val pending=requireNotNull(vm.state.value.pending)
            api.commitFailure=null;vm.recover(pending);idle(vm)
            assertEquals(2,api.commits.size);assertEquals(api.commits[0],api.commits[1])
            assertNotNull(vm.state.value.receipt);assertNull(vm.state.value.pending);assertNull(journal.read(scope))
            assertNull(vm.state.value.error);assertNotNull(vm.state.value.refreshError);assertEquals(1,notices.size)
            failRefresh=false;vm.refreshViews();idle(vm)
            assertEquals(2,api.commits.size);assertEquals(2,refreshes);assertNull(vm.state.value.refreshError)
            assertEquals(notices.first(),notices.last())
        } finally { tasks.cancel() }
    }

    @Test fun acceptedReceiptSurvivesCleanupFailureAndStillRefreshesReaders()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val storage=Storage();val journal=TenderJournal(storage);var refreshed=0
            val vm=TenderWorkspaceViewModel(api.api,journal,{account},tasks,{1000},{1000},refreshReaders={_,_->refreshed++;emptyList()})
            vm.bind(source,account);ready(vm);preview(vm);storage.failRemove=true;vm.commit();idle(vm)
            val pending=requireNotNull(vm.state.value.pending)
            assertNotNull(vm.state.value.receipt);assertNotNull(vm.state.value.storageError);assertNull(vm.state.value.error)
            assertEquals(pending,journal.read(scope));assertEquals(1,refreshed)
            storage.failRemove=false;vm.recover(pending);idle(vm)
            assertEquals(api.commits[0],api.commits[1]);assertEquals(2,refreshed);assertNull(vm.state.value.pending)
            assertNull(vm.state.value.storageError)
        } finally { tasks.cancel() }
    }

    @Test fun accountSwitchDuringReadRefreshCannotRepaintOtherOwnersWorkspace()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            var active=account;var refreshStarted=false
            val waiting=CompletableDeferred<List<String>>()
            val api=Api();val journal=TenderJournal(Storage())
            val vm=TenderWorkspaceViewModel(api.api,journal,{active},tasks,{1000},{1000},refreshReaders={_,_->refreshStarted=true;waiting.await()})
            vm.bind(source,active);ready(vm);preview(vm);vm.commit()
            withTimeout(5000){while(!refreshStarted)delay(5)}
            assertNotNull(vm.state.value.receipt);assertNull(journal.read(scope))
            active=account.copy(userId="88888888-8888-8888-8888-888888888888");vm.bind(source,active);idle(vm)
            waiting.complete(listOf("Old owner's read failed"));delay(10)
            assertNull(vm.state.value.receipt);assertNull(vm.state.value.refreshError);assertEquals(1,api.commits.size)
        } finally { tasks.cancel() }
    }

    @Test fun correctionResourcesAndOwnerBranchHintsAreExplicit() {
        val paymentRow=TenderReceipt("id","reference","payment_method_corrected",true,company,branch,"2026-10-17","Asia/Kolkata",owner,"2026-10-19T10:00:00Z","reason","owner_attestation",sourceFingerprint=fingerprint,idempotencyKey="key",sourceAllocation="identified_payment",totalDifferenceMinor=0)
        assertEquals(setOf("receipts","orders","shifts","finance","customers","reports","audit"),tenderAffectedResources(paymentRow))
        assertEquals(setOf("finance","reports","audit"),tenderAffectedResources(paymentRow.copy(operation="daily_actuals_recorded",financialEffect=false)))
        val notice=TenderCorrectionNotice(scope,"id",setOf("audit"))
        assertTrue(notice.matchesOwnerKey(scope.ownerKey()));assertTrue(notice.matchesOwnerKey("$company|$owner|"))
        assertFalse(notice.matchesOwnerKey("$company|$owner|other-branch"));assertFalse(notice.matchesOwnerKey("other-company|$owner|$branch"))
        assertFalse(notice.matchesOwnerKey("$company|other-owner|$branch"));assertFalse(notice.matchesOwnerKey(null))
    }

}
