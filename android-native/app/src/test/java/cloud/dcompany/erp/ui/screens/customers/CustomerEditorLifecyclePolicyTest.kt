package cloud.dcompany.erp.ui.screens.customers

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomerEditorLifecyclePolicyTest {
    @Test
    fun `add or edit cannot replace an open or saving customer draft`() {
        assertTrue(canOpenCustomerEditor(saving = false, editorOpen = false))
        assertFalse(canOpenCustomerEditor(saving = false, editorOpen = true))
        assertFalse(canOpenCustomerEditor(saving = true, editorOpen = false))
        assertFalse(canOpenCustomerEditor(saving = true, editorOpen = true))
    }
}
