package cloud.dcompany.erp.core.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ClientPolicyMonotonicTest {
    private fun response(revision:Int, required:Boolean=false, available:Boolean=false) = ClientCompatibilityResponse(
        platform="android", currentVersionCode=55, minimumSupportedVersionCode=if(required)56 else 54,
        latestVersionCode=56, policyRevision=revision,
        status=if(required)"update_required" else if(available)"update_available" else"supported",
        updateUrl="https://updates.example.test/app.apk", latestVersionName="3.1.48", apkSha256="ab".repeat(32),
        apkSizeBytes=123, apkSigningCertSha256="cd".repeat(32),message="Policy",checkedAt="2026-10-22T00:00:00Z")
    @Test fun requiredFiveOptionalSixLate426FiveCannotReblockAcrossRecreation() = runBlocking {
        var next=response(5,required=true)
        var stamp:ClientPolicyAuthority?=null
        var notice:ClientUpdateNotice?=null
        val persist:(ClientPolicyAuthority,ClientUpdateNotice?)->Boolean={s,n->stamp=s;notice=n;true}
        val gate=ClientCompatibilityGate({next},installedVersionCode=55,persistPolicyDecision=persist)
        gate.checkAtStartup(); assertTrue(gate.state.value is ClientCompatibilityState.UpdateRequired)
        val stale=(gate.state.value as ClientCompatibilityState.UpdateRequired).notice
        next=response(6,available=true);gate.recheckNonBlocking()
        assertTrue(gate.state.value is ClientCompatibilityState.UpdateAvailable);assertNull(notice)
        gate.requireUpdate(stale);assertTrue(gate.state.value is ClientCompatibilityState.UpdateAvailable)
        val restored=ClientCompatibilityGate({next},installedVersionCode=55,initialPolicyAuthority=stamp,persistPolicyDecision=persist)
        restored.requireUpdate(stale);assertFalse(restored.state.value is ClientCompatibilityState.UpdateRequired)
        restored.checkAtStartup();assertTrue(restored.state.value is ClientCompatibilityState.UpdateAvailable)
    }
    @Test fun optionalFiveRequiredSixLateOptionalFiveCannotRelax() = runBlocking {
        var next=response(5)
        val gate=ClientCompatibilityGate({next},installedVersionCode=55)
        gate.checkAtStartup();next=response(6,required=true);gate.recheckNonBlocking()
        val required=gate.state.value
        next=response(5,available=true);gate.recheckNonBlocking()
        assertEquals(required,gate.state.value)
        next=response(6);gate.recheckNonBlocking();assertEquals(required,gate.state.value)
    }
    @Test fun malformedOptionalCannotAdvanceRevisionOrClearRequirement() = runBlocking {
        var next=response(5,required=true)
        val gate=ClientCompatibilityGate({next},installedVersionCode=55)
        gate.checkAtStartup()
        next=response(99,available=true).copy(apkSha256=null)
        gate.recheckNonBlocking();assertTrue(gate.state.value is ClientCompatibilityState.UpdateRequired)
        next=response(6);gate.recheckNonBlocking();assertEquals(ClientCompatibilityState.Supported,gate.state.value)
    }
    @Test fun identicalRequiredPollsPersistOnlyOnceAndFailedCommitStillBlocksMemory() = runBlocking {
        var writes=0
        val gate=ClientCompatibilityGate({response(5,required=true)},installedVersionCode=55,
            persistPolicyDecision={_,_->writes++;true})
        repeat(3){gate.recheckNonBlocking()};assertEquals(1,writes)
        val failed=ClientCompatibilityGate({response(5,required=true)},installedVersionCode=55,
            persistPolicyDecision={_,_->false})
        failed.checkAtStartup();assertTrue(failed.state.value is ClientCompatibilityState.UpdateRequired)
    }
    @Test fun unversioned426BlocksConservativelyUntilCompleteProbeWithoutErasingHighWater() = runBlocking {
        var next=response(6)
        val gate=ClientCompatibilityGate({next},installedVersionCode=55)
        gate.checkAtStartup()
        gate.requireUpdate(ClientUpdateNotice("Unversioned block",null,55,null,null))
        assertTrue(gate.state.value is ClientCompatibilityState.UpdateRequired)
        gate.recheckNonBlocking();assertEquals(ClientCompatibilityState.Supported,gate.state.value)
        gate.requireUpdate(response(5,required=true).let{ClientUpdateNotice(it.message,it.updateUrl,55,56,56,5)})
        assertEquals(ClientCompatibilityState.Supported,gate.state.value)
    }
    @Test fun failedRequiredCommitRetriesIdenticalDecisionUntilDurableThenDeduplicates() = runBlocking {
        var writes=0
        val gate=ClientCompatibilityGate({response(5,required=true)},installedVersionCode=55,
            persistPolicyDecision={_,_->++writes>1})
        gate.recheckNonBlocking();assertTrue(gate.state.value is ClientCompatibilityState.UpdateRequired)
        gate.recheckNonBlocking();assertEquals(2,writes)
        gate.recheckNonBlocking();assertEquals(2,writes)
    }

}
