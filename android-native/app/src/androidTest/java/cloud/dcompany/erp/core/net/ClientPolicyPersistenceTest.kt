package cloud.dcompany.erp.core.net

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ClientPolicyPersistenceTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val name="test-policy-${UUID.randomUUID()}"
    private val isolated=object:ContextWrapper(app) {
        override fun getSharedPreferences(ignored:String,mode:Int):SharedPreferences = app.getSharedPreferences(name,mode)
    }
    @After fun cleanOnlyOwnedTestPreferences(){ app.deleteSharedPreferences(name) }
    @Test fun requiredThenOptionalRetainsHighWaterAcrossNewStoreAndGateInstances()=runBlocking {
        val store=ClientUpdateRequirementStore(isolated,55)
        val required=ClientUpdateNotice("Required",null,55,56,56,5)
        assertTrue(store.persistDecision(ClientPolicyAuthority(5,56,true),required))
        assertEquals(required,ClientUpdateRequirementStore(isolated,55).restore())
        assertTrue(store.persistDecision(ClientPolicyAuthority(6,54,false),null))
        val recreated=ClientUpdateRequirementStore(isolated,55)
        assertNull(recreated.restore());assertEquals(ClientPolicyAuthority(6,54,false),recreated.restoreAuthority())
        val gate=ClientCompatibilityGate({throw IllegalStateException("Offline")},installedVersionCode=55,
            initialRequiredNotice=recreated.restore(),initialPolicyAuthority=recreated.restoreAuthority(),
            persistPolicyDecision=recreated::persistDecision)
        gate.requireUpdate(required);gate.checkAtStartup()
        assertEquals(ClientCompatibilityState.Supported,gate.state.value)
        assertFalse(recreated.persistDecision(ClientPolicyAuthority(5,56,true),required))
        assertNull(recreated.restore())
    }
    @Test fun requiredStampWithoutNoticeAndInvalidAuthorityRemainConservativelyBlocked(){
        val prefs=app.getSharedPreferences(name,Context.MODE_PRIVATE)
        prefs.edit().putInt("authority_build",55).putInt("authority_revision",6)
            .putString("authority_json","""{"revision":6,"minimum":56,"required":true}""").commit()
        val store=ClientUpdateRequirementStore(isolated,55)
        assertNotNull(store.restore());assertEquals(6,store.restore()?.policyRevision)
        prefs.edit().putInt("installed_version_code",54).commit()
        assertNotNull(store.restore());assertEquals(6,store.restore()?.policyRevision)
        assertEquals(56,store.restore()?.minimumSupportedVersionCode)
        prefs.edit().remove("installed_version_code").commit()
        prefs.edit().putString("authority_json","""{"revision":6,"minimum":0,"required":false}""").commit()
        assertNull(store.restoreAuthority());assertNotNull(store.restore())
        assertNull(ClientUpdateRequirementStore(isolated,56).restore())
    }
}
