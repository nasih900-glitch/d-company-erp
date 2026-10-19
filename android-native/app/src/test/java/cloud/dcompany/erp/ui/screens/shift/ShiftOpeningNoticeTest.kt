package cloud.dcompany.erp.ui.screens.shift

import cloud.dcompany.erp.core.db.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ShiftOpeningNoticeTest {
    private val message="Shift saved on this tablet; waiting for confirmation."
    private val captured=ShiftOpeningNotice("local-open","terminal",message)
    private fun resolved(state:String=ShiftState.OPEN_SYNCED,id:String="local-open",terminal:String="terminal",server:String?="server-open")=
        ShiftResolutionPolicy.resolve(LocalShiftEntity(id,server,terminal,"branch",0,1000,state=state),null)

    @Test fun onlySameServerConfirmedOpenResolvesOwnedNotice() {
        val notice=MutableStateFlow<String?>(message)
        assertFalse(clearConfirmedShiftOpeningNotice(resolved(ShiftState.OPEN_PENDING,server=null),captured,notice))
        assertEquals(message,notice.value)
        assertFalse(clearConfirmedShiftOpeningNotice(resolved(id="other"),captured,notice))
        assertFalse(clearConfirmedShiftOpeningNotice(resolved(terminal="other"),captured,notice))
        assertTrue(clearConfirmedShiftOpeningNotice(resolved(),captured,notice));assertNull(notice.value)
    }
    @Test fun newerCloseOrRecoveryNoticeIsNeverErased() {
        val notice=MutableStateFlow<String?>("Saved close needs recovery")
        assertTrue(clearConfirmedShiftOpeningNotice(resolved(),captured,notice))
        assertEquals("Saved close needs recovery",notice.value)
        assertFalse(clearConfirmedShiftOpeningNotice(resolved(ShiftState.CLOSE_REJECTED),captured,notice))
    }
}
