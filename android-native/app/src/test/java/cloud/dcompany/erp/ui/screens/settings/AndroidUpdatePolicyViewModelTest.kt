package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class AndroidUpdatePolicyViewModelTest {
    private val profile=MeResponse("owner","qa@example.test","Synthetic Owner",companyId="company",branchId="branch",releaseControlAccess=true)
    private val lease=CacheScopeLease(CacheScope("owner","company","branch","terminal"),1)
    private val policy=AndroidUpdatePolicyDto("inherit",4,54,54)
    private inner class Api:AndroidUpdatePolicyApi {
        var reads=0;var blocked:CompletableDeferred<Unit>?=null;var rejected=false
        val writes=mutableListOf<AndroidUpdatePolicyBody>()
        override suspend fun androidUpdatePolicy():AndroidUpdatePolicyDto {
            reads++;blocked?.let{withContext(NonCancellable){it.await()}}
            if(rejected)throw ApiException("Access no longer granted",403)
            return policy
        }
        override suspend fun setAndroidUpdatePolicy(body:AndroidUpdatePolicyBody):AndroidUpdatePolicyDto {
            writes+=body
            if(rejected)throw ApiException("Policy changed",409)
            return policy.copy(mode=body.mode,revision=5)
        }
    }
    private suspend fun idle(vm:AndroidUpdatePolicyViewModel)=withTimeout(3000){while(vm.state.value.busy)delay(1)}
    @Test fun authorityNeverComesFromGenericOwnerRoleAndReviewIsRequired()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val vm=AndroidUpdatePolicyViewModel(api,tasks,{lease},{_,write->write();true})
            vm.bind(profile.copy(releaseControlAccess=false,roles=listOf("owner")));yield()
            assertEquals(0,api.reads);assertNull(vm.state.value.authority)
            vm.bind(profile);idle(vm);vm.reason("Allow supported builds");vm.apply();yield()
            assertTrue(api.writes.isEmpty())
            vm.reviewed(true);vm.apply();idle(vm)
            assertEquals(listOf(AndroidUpdatePolicyBody("optional",4,"Allow supported builds")),api.writes)
            assertEquals(5,vm.state.value.policy?.revision)
        }finally{tasks.cancel()}
    }
    @Test fun revokedOrChangedAccountClearsDraftAndDropsLateRead()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val blocker=CompletableDeferred<Unit>();api.blocked=blocker
            var current=lease
            val vm=AndroidUpdatePolicyViewModel(api,tasks,{current},{captured,write->if(captured==current){write();true}else false})
            vm.bind(profile);yield()
            current=CacheScopeLease(lease.scope.copy(userId="other"),2)
            vm.bind(profile.copy(userId="other",releaseControlAccess=false))
            blocker.complete(Unit);yield();delay(5)
            assertNull(vm.state.value.authority);assertNull(vm.state.value.policy)
            assertEquals("",vm.state.value.reason);assertFalse(vm.state.value.reviewed)
        }finally{tasks.cancel()}
    }
    @Test fun uncertainWriteRequiresRefreshInsteadOfReusingStaleCasReview()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val vm=AndroidUpdatePolicyViewModel(api,tasks,{lease},{_,write->write();true})
            vm.bind(profile);idle(vm);vm.reason("Reviewed policy");vm.reviewed(true)
            api.rejected=true;vm.apply();idle(vm)
            assertNull(vm.state.value.policy);assertFalse(vm.state.value.reviewed)
            vm.reviewed(true);vm.apply();yield();assertEquals(1,api.writes.size)
        }finally{tasks.cancel()}
    }
    @Test fun requiredPayloadUsesOnlyExactActiveServerFingerprint() {
        assertNull(policyWriteBody(policy,"required","Reviewed"))
        val release=PolicyActiveRelease("release",55,"3.1.47","active","cd".repeat(32),"ab".repeat(32))
        assertEquals(AndroidUpdatePolicyBody("required",4,"Reviewed","release","ab".repeat(32)),
            policyWriteBody(policy.copy(activeRelease=release),"required","Reviewed"))
        assertNull(policyWriteBody(policy.copy(activeRelease=release.copy(status="withdrawn")),"required","Reviewed"))
    }
}
