package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import cloud.dcompany.erp.core.auth.CacheScope
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.theme.DCompanyTheme
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Rule
import org.junit.Test

class DeviceHealthPanelUiTest {
    @get:Rule val compose=createComposeRule()
    private val tasks=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    @After fun close(){tasks.cancel()}

    @Test fun reportedStateAndHistoricalIncidentAreReachableWithoutFalseHealthyOrIncidentBuildClaim() {
        val time="2026-10-19T10:00:00Z"
        val profile=MeResponse("owner","qa@example.test","QA Owner",companyId="company",branchId="branch",effectivePermissions=listOf("settings.manage","admin.support"))
        val lease=CacheScopeLease(CacheScope("owner","company","branch","terminal"),1)
        val api=object:DeviceHealthApi {
            override suspend fun installations(limit:Int,offset:Int)=ReportedInstallationPage(time,24,0,emptyList())
            override suspend fun incidents(eventType:String?,limit:Int,offset:Int)=ReportedIncidentPage(listOf(
                ReportedIncident("event","device","anr","critical","2026-10-17T09:00:00Z",time,"3.1.43",51,"app","process_anr",connectivity="unknown")),1,limit,offset,30)
            override suspend fun health()=ReportedSystemHealth("unknown",time,ReportedHealthDependencies("ok","ok","ok"),
                ReportedHealthBackups("unknown",evidenceCode="host_monitor_not_connected"),ReportedHealthDevices(0,0,0,0,0,0),emptyList())
        }
        val vm=DeviceHealthViewModel(api,tasks,{lease},{_,write->write();true},{time})
        compose.setContent {DCompanyTheme {DeviceHealthPanel(profile,vm)}}
        compose.onNodeWithTag("device-health-list").performScrollToNode(hasText("No reporting tablets · coverage unknown"))
        compose.onNodeWithText("No reporting tablets · coverage unknown").assertIsDisplayed()
        compose.onNodeWithTag("device-health-list").performScrollToNode(hasText("Observed/imported by build 51",substring=true))
        compose.onNodeWithText("Observed/imported by build 51",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Reported occurrence",substring=true).assertIsDisplayed()
        compose.onNodeWithText("Server received",substring=true).assertIsDisplayed()
        compose.onNodeWithText("All current").assertDoesNotExist()
    }
}
