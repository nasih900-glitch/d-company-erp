package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/** A visual mask alone does not disable an IME's word suggestions or learning. */
internal val ownerVerificationKeyboardOptions = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    imeAction = ImeAction.Done,
)
