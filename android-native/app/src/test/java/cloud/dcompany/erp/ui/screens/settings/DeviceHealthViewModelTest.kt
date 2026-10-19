package cloud.dcompany.erp.ui.screens.settings

import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.MeResponse
import java.io.IOException
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class DeviceHealthViewModelTest {
    private val profile=MeResponse("owner","qa@example.test","QA Owner",companyId="company",branchId="branch",effectivePermissions=listOf("settings.manage","admin.support"))
    private val lease=CacheScopeLease(CacheScope("owner","company","branch","terminal"),1)
    private val time="2026-10-19T10:00:00Z"
    private fun device(id:String="device")=ReportedInstallation(id,"android","direct","3.1.42",50,0,null,"installed",null,time,false)
    private fun event(id:String="event",reason:String="process_anr")=ReportedIncident(id,"device","anr","critical","2026-10-17T10:00:00Z",time,"3.1.43",51,"app",reason,connectivity="offline")
    private fun health()=ReportedSystemHealth("unknown",time,ReportedHealthDependencies("ok","ok","ok"),ReportedHealthBackups("unknown",evidenceCode="host_monitor_not_connected"),ReportedHealthDevices(1,0,1,0,0,0),emptyList())
    private inner class Api:DeviceHealthApi {
        var deviceCalls=0;var eventCalls=0;var healthCalls=0
        var eventFailure=false;var rejected=false;var blockedDevices:CompletableDeferred<Unit>?=null
        override suspend fun installations(limit:Int,offset:Int):ReportedInstallationPage {
            deviceCalls++
            val blocked=blockedDevices
            if(blocked!=null) withContext(NonCancellable){blocked.await()}
            return ReportedInstallationPage(time,24,1,listOf(device()))
        }
        override suspend fun incidents(eventType:String?,limit:Int,offset:Int):ReportedIncidentPage {
            eventCalls++;if(rejected) throw cloud.dcompany.erp.core.net.ApiException("Access no longer granted",403);if(eventFailure) throw IOException("Temporary network failure")
            return ReportedIncidentPage(listOf(event()),1,limit,offset,30)
        }
        override suspend fun health():ReportedSystemHealth {healthCalls++;return this@DeviceHealthViewModelTest.health()}
    }
    private suspend fun idle(vm:DeviceHealthViewModel)=withTimeout(3000){while(vm.state.value.readingDevices || vm.state.value.readingHealth || vm.state.value.readingIncidents) delay(1)}

    @Test fun eachExistingPermissionControlsOnlyItsOwnReadSurface()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val vm=DeviceHealthViewModel(api,tasks,{lease},{_,write->write();true},{time})
            vm.bind(profile.copy(effectivePermissions=listOf("settings.manage")));idle(vm)
            assertEquals(1,api.deviceCalls);assertEquals(0,api.eventCalls);assertEquals(0,api.healthCalls)
            vm.bind(profile.copy(effectivePermissions=listOf("admin.support")));idle(vm)
            assertEquals(1,api.deviceCalls);assertEquals(1,api.eventCalls);assertEquals(1,api.healthCalls)
            assertNull(vm.state.value.installations)
            vm.bind(profile.copy(effectivePermissions=emptyList()));assertNull(vm.state.value.authority)
            assertNull(vm.state.value.incidents);assertNull(vm.state.value.health)
        } finally {tasks.cancel()}
    }
    @Test fun changedFilterFailureRetainsAppliedEvidenceWithTruthfulScope()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val vm=DeviceHealthViewModel(api,tasks,{lease},{_,write->write();true},{time})
            vm.bind(profile);idle(vm)
            assertEquals("event",vm.state.value.incidents?.items?.single()?.id)
            api.eventFailure=true;vm.incidents("crash");idle(vm)
            assertNull(vm.state.value.appliedType);assertEquals("crash",vm.state.value.requestedType)
            assertEquals("event",vm.state.value.incidents?.items?.single()?.id)
            assertEquals("Temporary network failure",vm.state.value.incidentsError)
        } finally {tasks.cancel()}
    }
    @Test fun accountAndPermissionReplacementHideRowsAndRejectLateOldResponses()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val blocker=CompletableDeferred<Unit>();api.blockedDevices=blocker
            var current=lease
            val vm=DeviceHealthViewModel(api,tasks,{current},{captured,write->if(captured==current){write();true}else false},{time})
            vm.bind(profile);yield()
            current=CacheScopeLease(lease.scope.copy(userId="other"),2)
            vm.bind(profile.copy(userId="other",effectivePermissions=emptyList()))
            blocker.complete(Unit);yield();delay(5)
            assertNull(vm.state.value.authority);assertNull(vm.state.value.installations)
            assertNull(vm.state.value.health);assertNull(vm.state.value.incidents)
        } finally {tasks.cancel()}
    }
    @Test fun historicalOsExitBuildIsObserverNotGuessedIncidentBuild() {
        for(reason in listOf("process_anr","java_crash_exit","native_crash_exit","low_memory_exit","excessive_resource_exit")) {
            val row=event(reason=reason)
            assertTrue(row.buildEvidence().contains("Observed/imported by build 51"))
            assertTrue(row.buildEvidence().contains("Actual incident build was not retained"))
            assertEquals(51,row.versionCode);assertEquals("2026-10-17T10:00:00Z",row.occurredAt)
        }
        assertEquals("Captured event build 51 · 3.1.43",event(reason="unhandled_java_exception").copy(eventType="crash").buildEvidence())
        assertTrue(event(reason="unclassified_legacy_reason").buildEvidence().contains("provenance is unavailable"))
    }
    @Test fun noInstallationsOrStaleOnlyCannotClaimEntireFleetIsCurrent() {
        assertEquals("No reporting tablets · coverage unknown",ReportedHealthDevices(0,0,0,0,0,0).coverageEvidence())
        assertTrue(ReportedHealthDevices(3,0,3,0,0,1).coverageEvidence().contains("current status unknown"))
        assertTrue(ReportedHealthDevices(3,1,2,0,0,1).coverageEvidence().contains("stale tablets remain unknown"))
    }
    @Test fun refusedSupportReadRemovesPriorIncidentEvidenceInsteadOfPresentingItAsAuthorized()=runBlocking {
        val tasks=CoroutineScope(coroutineContext+SupervisorJob())
        try {
            val api=Api();val vm=DeviceHealthViewModel(api,tasks,{lease},{_,write->write();true},{time})
            vm.bind(profile);idle(vm);assertNotNull(vm.state.value.incidents)
            api.rejected=true;vm.incidents(null);idle(vm)
            assertNull(vm.state.value.incidents);assertEquals("Access no longer granted",vm.state.value.incidentsError)
        } finally {tasks.cancel()}
    }

}
