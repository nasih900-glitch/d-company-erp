package cloud.dcompany.erp.ui.screens.gaming

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.components.ActionIntent
import cloud.dcompany.erp.ui.components.ErpButton
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

@Composable
internal fun PauseSessionDialog(onDismiss: () -> Unit, onPause: (String) -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    val normalizedReason = reason.trim()
    val maxHeight = gamingDialogBodyMaxHeight(LocalConfiguration.current.screenHeightDp)
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.92f)
            .statusBarsPadding().navigationBarsPadding().imePadding(),
        title = { Text("Pause session") },
        text = {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxHeight)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                Text("Play time will pause on all connected devices. The booked package price stays unchanged. Your reason is recorded in the audit history.")
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(500) },
                    label = { Text("Pause reason") },
                    supportingText = { Text("Enter 3–500 characters · ${reason.length}/500") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                // Keep actions scrollable above the tablet's software keyboard.
                ErpButton(
                    text = "Pause session",
                    onClick = { onPause(normalizedReason) },
                    enabled = normalizedReason.length in 3..500,
                    intent = ActionIntent.Primary,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }
        },
        confirmButton = {},
    )
}
