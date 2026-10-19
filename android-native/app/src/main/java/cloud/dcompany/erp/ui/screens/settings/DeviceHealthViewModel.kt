package cloud.dcompany.erp.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.auth.CacheScopeLease
import cloud.dcompany.erp.core.auth.EffectivePermissions
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.core.net.MeResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal data class DeviceHealthAuthority(val key:String,val devices:Boolean,val support:Boolean)
internal fun deviceHealthAuthority(profile:MeResponse?):DeviceHealthAuthority? {
    if(profile==null) return null
    val permissions=EffectivePermissions.from(profile)
    val devices=permissions.has("settings.manage")
    val support=permissions.has("admin.support")
    return if(!devices && !support) null else DeviceHealthAuthority(
        "${profile.companyId}|${profile.userId}|${profile.branchId.orEmpty()}|$devices|$support",devices,support)
}
internal data class DeviceHealthState(
    val authority:DeviceHealthAuthority?=null,
    val installations:ReportedInstallationPage?=null,val installationsError:String?=null,val readingDevices:Boolean=false,
    val health:ReportedSystemHealth?=null,val healthError:String?=null,val readingHealth:Boolean=false,
    val incidents:ReportedIncidentPage?=null,val incidentsError:String?=null,val readingIncidents:Boolean=false,
    val requestedType:String?=null,val appliedType:String?=null,
    val incidentReadAt:String?=null,
)

/** No protected data crosses account/permission generations; read failures keep labelled prior results. */
internal class DeviceHealthViewModel(
    private val api:DeviceHealthApi=ApiClient.create(),
    private val tasks:CoroutineScope?=null,
    private val lease:()->CacheScopeLease?={DCompanyApp.instance.cacheIsolation.currentLease()},
    private val commit:suspend(CacheScopeLease,()->Unit)->Boolean={captured,write->DCompanyApp.instance.cacheIsolation.commitIfCurrent(captured,write)},
    private val now:()->String={java.time.Instant.now().toString()},
):ViewModel() {
    private val mutable=MutableStateFlow(DeviceHealthState())
    val state:StateFlow<DeviceHealthState> = mutable.asStateFlow()
    private val scope get()=tasks ?: viewModelScope
    private var generation=0L
    private var eventGeneration=0L
    private var reads:Job?=null
    private var eventRead:Job?=null

    fun bind(profile:MeResponse?) {
        val next=deviceHealthAuthority(profile)
        if(next==mutable.value.authority) return
        ++generation;++eventGeneration;reads?.cancel();eventRead?.cancel()
        mutable.value=DeviceHealthState(authority=next)
        if(next!=null) refresh()
    }
    fun refresh() {
        val authority=mutable.value.authority ?: return
        val captured=verifiedLease(authority) ?: return
        val operation=++generation
        reads?.cancel()
        mutable.value=mutable.value.copy(readingDevices=authority.devices,readingHealth=authority.support,
            installationsError=null,healthError=null)
        reads=scope.launch {
            coroutineScope {
                if(authority.devices) launch {
                    try { val page=api.installations();accept(captured,authority,operation) {
                        copy(installations=page,readingDevices=false,installationsError=null)
                    } } catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){accept(captured,authority,operation){copy(installations=if(error.authorityRejected()) null else installations,readingDevices=false,installationsError=error.message ?: "Tablet reports could not refresh.")}}
                }
                if(authority.support) launch {
                    try {val page=api.health();accept(captured,authority,operation){copy(health=page,readingHealth=false,healthError=null)}}
                    catch(cancelled:CancellationException){throw cancelled}
                    catch(error:Exception){accept(captured,authority,operation){copy(health=if(error.authorityRejected()) null else health,readingHealth=false,healthError=error.message ?: "Health evidence could not refresh.")}}
                }
            }
        }
        incidents(mutable.value.requestedType)
    }
    fun moreDevices() {
        val before=mutable.value;val authority=before.authority ?: return
        val page=before.installations ?: return
        if(!authority.devices || before.readingDevices || page.items.size>=page.total) return
        val captured=verifiedLease(authority) ?: return
        val operation=generation
        mutable.value=before.copy(readingDevices=true,installationsError=null)
        scope.launch {
            try {
                val next=api.installations(offset=page.items.size)
                require(next.items.none {row->page.items.any{it.installationId==row.installationId}}){"Tablet report pages changed; refresh before loading more."}
                accept(captured,authority,operation){copy(installations=next.copy(items=page.items+next.items),readingDevices=false)}
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){accept(captured,authority,operation){copy(installations=if(error.authorityRejected()) null else installations,readingDevices=false,installationsError=error.message ?: "Older tablet reports could not load.")}}
        }
    }
    fun incidents(type:String?,more:Boolean=false) {
        require(type==null || type in setOf("crash","anr","api_failure","sync_stall"))
        val before=mutable.value;val authority=before.authority ?: return
        if(!authority.support || (more && (before.readingIncidents || before.requestedType!=before.appliedType))) return
        val captured=verifiedLease(authority) ?: return
        val prior=before.incidents
        if(more && (prior==null || prior.items.size>=prior.total)) return
        val operation=++eventGeneration;val ownerGeneration=generation
        eventRead?.cancel()
        mutable.value=before.copy(requestedType=type,readingIncidents=true,incidentsError=null)
        eventRead=scope.launch {
            try {
                val offset=if(more) prior!!.items.size else 0
                val page=api.incidents(type,offset=offset)
                require(page.offset==offset && page.limit in 1..200 && page.items.map{it.id}.distinct().size==page.items.size){"Incident paging was not confirmed by the server."}
                if(more) require(page.items.none{row->prior!!.items.any{it.id==row.id}}){"Incident pages changed; refresh before loading more."}
                commit(captured) {
                    if(ownerGeneration==generation && operation==eventGeneration && mutable.value.authority==authority) {
                        mutable.value=mutable.value.copy(incidents=page.copy(items=if(more)prior!!.items+page.items else page.items),
                            appliedType=type,readingIncidents=false,incidentsError=null,incidentReadAt=now())
                    }
                }
            } catch(cancelled:CancellationException){throw cancelled}
            catch(error:Exception){commit(captured){if(ownerGeneration==generation && operation==eventGeneration && mutable.value.authority==authority) {
                mutable.value=mutable.value.copy(incidents=if(error.authorityRejected()) null else mutable.value.incidents,readingIncidents=false,incidentsError=error.message ?: "Incident reports could not refresh.")
            }}}
        }
    }
    private fun verifiedLease(authority:DeviceHealthAuthority):CacheScopeLease? = lease()?.takeIf {
        authority.key.startsWith("${it.scope.companyId}|${it.scope.userId}|${it.scope.branchId.orEmpty()}|")
    }
    private suspend fun accept(captured:CacheScopeLease,authority:DeviceHealthAuthority,operation:Long,
        transform:DeviceHealthState.()->DeviceHealthState) {
        commit(captured){if(operation==generation && mutable.value.authority==authority) mutable.value=mutable.value.transform()}
    }
}

private fun Exception.authorityRejected():Boolean =
    (this as? ApiException)?.status in setOf(401,403) || (this as? retrofit2.HttpException)?.code() in setOf(401,403)
