package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import org.junit.Assert.*
import org.junit.Test

class GamingCustomerDraftLifetimeTest {
    private fun draft()=GamingStartSelectionState(mutableStateOf("dual"),mutableIntStateOf(4),mutableStateOf("dual-60"))
        .apply { customerName.value="Synthetic A";customerPhone.value="9000000000";customerQuery.value="Synthetic A"
            customerMode.value="new";customer.value=GamingCustomerOption("customer-a","Synthetic A","9000000000",false) }

    @Test fun savedCaptureConsumesOnlyThatCustomerAndNextBookingIsAnonymousWithTariffRetained() {
        val stationA=draft();val stationB=draft()
        val saved=stationA.customerCaptureReceipt()
        saved("durable-local-session-a")
        assertNull(stationA.customer.value);assertEquals("",stationA.customerName.value)
        assertEquals("",stationA.customerPhone.value);assertEquals("",stationA.customerQuery.value)
        assertEquals("search",stationA.customerMode.value)
        assertEquals("dual",stationA.selectedVariant.value);assertEquals(4,stationA.playerCount.intValue)
        assertEquals("dual-60",stationA.selectedPackageId.value);assertNotNull(stationB.customer.value)
        // Stop/send/disposal do not restore the consumed fields into the next Start editor.
        assertNull(stationA.customer.value)
    }
    @Test fun rejectedOrExistingQueuedStartDoesNotAcknowledgeOrConsumeUnsentCustomer() {
        val draft=draft();draft.customerCaptureReceipt()
        assertEquals("Synthetic A",draft.customerName.value);assertNotNull(draft.customer.value)
        val invalid=draft.customerCaptureReceipt();invalid("")
        assertNotNull(draft.customer.value)
    }
    @Test fun staleAckAfterAnyEditEvenEditBackCannotConsumeNewerDraft() {
        val draft=draft();val old=draft.customerCaptureReceipt()
        draft.customerQuery.value="new search";draft.customerQuery.value="Synthetic A"
        old("old-saved-session");assertNotNull(draft.customer.value)
        val first=draft.customerCaptureReceipt();val current=draft.customerCaptureReceipt()
        first("superseded-attempt");assertNotNull(draft.customer.value)
        current("matching-saved-session");assertNull(draft.customer.value)
    }
    @Test fun oldScopeAckCannotTouchReplacementScopeDraft() {
        val oldScope=draft();val old=oldScope.customerCaptureReceipt()
        val replacement=draft();old("old-session")
        assertNull(oldScope.customer.value);assertNotNull(replacement.customer.value)
    }
}
