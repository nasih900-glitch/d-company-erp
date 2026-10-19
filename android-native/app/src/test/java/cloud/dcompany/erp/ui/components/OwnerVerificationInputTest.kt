package cloud.dcompany.erp.ui.components

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.*
import org.junit.Test

class OwnerVerificationInputTest {
    @Test fun protectedOwnerEntryRequestsPasswordImeWithoutWordCorrectionOrCapitalization() {
        assertEquals(KeyboardType.Password, ownerVerificationKeyboardOptions.keyboardType)
        assertEquals(false, ownerVerificationKeyboardOptions.autoCorrectEnabled)
        assertEquals(KeyboardCapitalization.None, ownerVerificationKeyboardOptions.capitalization)
        assertEquals(ImeAction.Done, ownerVerificationKeyboardOptions.imeAction)
    }
}
