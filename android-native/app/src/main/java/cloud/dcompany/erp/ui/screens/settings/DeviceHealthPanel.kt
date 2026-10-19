package cloud.dcompany.erp.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cloud.dcompany.erp.BuildConfig
import cloud.dcompany.erp.core.net.MeResponse
import cloud.dcompany.erp.ui.components.SectionCard
import cloud.dcompany.erp.ui.theme.Spacing
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DeviceHealthPanel(profile:MeResponse?,vm:DeviceHealthViewModel=viewModel()) {
    val authority=deviceHealthAuthority(profile)
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(profile){vm.bind(profile)}
    if(authority==null || state.authority!=authority) {
        Text("Device and support reports require current account verification.",color=MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var selected by remember(authority){mutableStateOf<String?>(null)}
    LazyColumn(Modifier.widthIn(max=1100.dp).fillMaxSize().testTag("device-health-list"),verticalArrangement=Arrangement.spacedBy(Spacing.md),
        contentPadding=PaddingValues(bottom=Spacing.xl)) {
        item {
            SectionCard(title="Devices & health",subtitle="Company-wide server evidence · names are current account/workspace labels") {
                Text("This tablet is running build ${BuildConfig.VERSION_CODE} · ${BuildConfig.VERSION_NAME}",fontWeight=FontWeight.SemiBold)
                Text("Reports describe what a device last sent. They do not prove it is currently online, crash-free or fully updated.",
                    style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton({vm.refresh()},enabled=!state.readingDevices && !state.readingHealth && !state.readingIncidents){Text("Refresh reports")}
            }
        }
        if(authority.support) item {
            SectionCard(title="Service evidence",subtitle=state.health?.serverTime?.let{"Server check · ${statusTime(it)}"} ?: "No service evidence loaded") {
                if(state.readingHealth) LinearProgressIndicator(Modifier.fillMaxWidth())
                state.healthError?.let{Text("Refresh failed: $it. ${if(state.health!=null) "Retained evidence remains as of its displayed check." else "No authorized service evidence is shown."}",color=MaterialTheme.colorScheme.error)}
                state.health?.let { health ->
                    StatusFact("API / database / cache","${health.dependencies.api} / ${health.dependencies.database} / ${health.dependencies.redis}")
                    StatusFact("Tablet coverage",health.devices.coverageEvidence())
                    StatusFact("Stale reports","${health.devices.stale} · ${health.devices.stalePending} last reported automatic work pending; present work is unknown")
                    StatusFact("Recently reporting with pending automatic Sync",health.devices.withPending.toString())
                    StatusFact("Host backups",if(health.backups.evidenceCode=="host_monitor_not_connected") "Host monitor not connected · backup and restore-test health unknown" else health.backups.status)
                    health.backups.lastSuccessAt?.let{StatusFact("Last backup evidence",statusTime(it))}
                    health.backups.restoreTestedAt?.let{StatusFact("Last restore-test evidence",statusTime(it))}
                    health.recommendations.forEach{Text(it,style=MaterialTheme.typography.bodySmall)}
                }
            }
        }
        if(authority.devices) {
            item {
                SectionCard(title="Last reported tablet state",subtitle=state.installations?.let{"${it.items.size} of ${it.total} loaded · server check ${statusTime(it.serverTime)}"} ?: "No tablet report loaded") {
                    if(state.readingDevices) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.installationsError?.let{Text("Refresh failed: $it. ${if(state.installations!=null) "Retained rows remain last-reported evidence." else "No authorized tablet rows are shown."}",color=MaterialTheme.colorScheme.error)}
                    Text("Target download build, last launch and a healthy launch after installation are not retained in these reports. They remain unknown; a heartbeat is not installation acceptance.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.installations?.total==0) Text("No reporting tablets · current fleet state unknown.")
                }
            }
            items(state.installations?.items.orEmpty(),key={"device-${it.installationId}"}) { device ->
                SectionCard(title=device.terminalName ?: "Workspace label unavailable",subtitle="${device.platform} · ${device.distributionChannel}") {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(Spacing.md)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(Spacing.sm)) {
                            StatusFact("Last reported build","${device.versionCode} · ${device.versionName}")
                            StatusFact("Last seen",statusTime(device.lastSeenAt))
                            StatusFact("Report freshness at check",if(device.isStale) "Stale · current build, activity and pending work unknown" else "Within ${state.installations?.staleAfterHours ?: 24} hours at the displayed server check")
                        }
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(Spacing.sm)) {
                            StatusFact("Last reported update stage",statusLabel(device.updateState))
                            StatusFact("Automatic outbox at last report",device.pendingOutboxCount.toString())
                            StatusFact("Last successful Sync",device.lastSuccessfulSyncAt?.let(::statusTime) ?: "Not reported")
                            StatusFact("Last account label",device.lastUserName ?: "Not available")
                        }
                    }
                    device.updateErrorCode?.let{StatusFact("Last reported update issue",statusLabel(it))}
                    Text("Installation ${device.installationId}",style=MaterialTheme.typography.labelSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if(state.installations?.let{it.items.size<it.total}==true) item {
                TextButton({vm.moreDevices()},enabled=!state.readingDevices){Text("Older tablet reports")}
            }
        }
        if(authority.support) {
            item {
                SectionCard(title="Reported incidents",subtitle=state.incidentReadAt?.let{"Client read at ${statusTime(it)} · ${state.incidents?.retentionDays ?: "unknown"} days of retained evidence"} ?: "No incident evidence loaded") {
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(Spacing.sm)) {
                        listOf(null to "All reports","crash" to "Crashes","anr" to "Not responding","api_failure" to "Service requests","sync_stall" to "Sync stalls").forEach { (type,label)->
                            FilterChip(selected=state.requestedType==type,onClick={selected=null;vm.incidents(type)},label={Text(label)},enabled=!state.readingIncidents)
                        }
                    }
                    if(state.readingIncidents) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if(state.requestedType!=state.appliedType && state.incidents!=null) Text("Previous applied results: ${state.appliedType?.let(::statusLabel) ?: "All reports"}. The requested filter has not replaced them.",style=MaterialTheme.typography.bodySmall)
                    state.incidentsError?.let{Text("Refresh failed: $it",color=MaterialTheme.colorScheme.error)}
                    Text("No received reports does not mean no crashes. Offline, stopped or unreported devices may have incidents not yet uploaded.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(state.incidents?.total==0) Text("No retained reports match the applied filter.")
                }
            }
            items(state.incidents?.items.orEmpty(),key={"incident-${it.id}"}) { incident ->
                SectionCard(title="${statusLabel(incident.eventType)} · ${statusLabel(incident.component)}",subtitle="Reported occurrence ${statusTime(incident.occurredAt)} · unverified device time") {
                    Text(incident.buildEvidence(),style=MaterialTheme.typography.bodyMedium)
                    Text("Server received ${statusTime(incident.receivedAt)} · ${statusLabel(incident.severity)}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton({selected=if(selected==incident.id)null else incident.id}){Text(if(selected==incident.id)"Hide evidence" else "Show evidence")}
                    if(selected==incident.id) {
                        StatusFact("Recorded reason",statusLabel(incident.reasonCode))
                        StatusFact("Connectivity at event",statusLabel(incident.connectivity))
                        StatusFact("Automatic pending work at event",incident.pendingOutboxCount?.toString() ?: "Not reported")
                        incident.httpStatus?.let{StatusFact("HTTP status",it.toString())}
                        incident.durationBucket?.let{StatusFact("Retained duration bucket",statusLabel(it))}
                        StatusFact("Installation",incident.installationId)
                        StatusFact("Report identity",incident.id)
                    }
                }
            }
            if(state.incidents?.let{it.items.size<it.total}==true) item {
                TextButton({vm.incidents(state.appliedType,more=true)},enabled=!state.readingIncidents && state.requestedType==state.appliedType){Text("Older reported incidents")}
            }
        }
    }
}

@Composable private fun StatusFact(label:String,value:String) {
    Column(verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(label,style=MaterialTheme.typography.labelMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value,style=MaterialTheme.typography.bodyMedium)
    }
}
internal fun statusLabel(value:String):String=value.replace('_',' ').replaceFirstChar{it.titlecase(Locale.ENGLISH)}
internal fun statusTime(value:String):String=runCatching{
    DateTimeFormatter.ofPattern("dd MMM yyyy · HH:mm:ss 'UTC'",Locale.ENGLISH).withZone(ZoneOffset.UTC).format(Instant.parse(value))
}.getOrDefault("Timestamp unavailable; original value was not replaced")
