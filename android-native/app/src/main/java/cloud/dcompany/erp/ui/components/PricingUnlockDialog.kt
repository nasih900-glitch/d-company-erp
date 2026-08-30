package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.auth.PricingApi
import cloud.dcompany.erp.core.auth.PricingLock
import cloud.dcompany.erp.core.auth.PricingUnlockRequest
import cloud.dcompany.erp.DCompanyApp
import cloud.dcompany.erp.core.net.ApiClient
import cloud.dcompany.erp.core.net.ApiException
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.remote.RemoteSensitiveContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The shared "re-enter your password to change a price" gate — first built
 * here for Menu, reused as-is by Events/Memberships/Gaming stations later.
 * A screen that needs a live [cloud.dcompany.erp.core.auth.PricingLock]
 * token shows this, and on [onUnlocked] can proceed with whatever price
 * write it was about to make (the unlock itself doesn't do that write —
 * this dialog's only job is getting a token into [PricingLock]).
 */
@Composable
fun PricingUnlockDialog(onDismiss: () -> Unit, onUnlocked: () -> Unit) {
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val api = remember { ApiClient.create<PricingApi>() }
    val tokens = DCompanyApp.instance.tokens

    RemoteSensitiveContent {
    AlertDialog(
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text("Unlock pricing") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Re-enter your account password to confirm it's you before changing prices.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !loading,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(error!!, color = Brand.Danger, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !loading,
                onClick = {
                    if (password.isBlank()) {
                        error = "Enter your account password to continue."
                    } else {
                        val session = tokens.currentPricingSession()
                        if (session == null) {
                            error = "Your sign-in changed. Sign in again before changing prices."
                            return@Button
                        }
                        loading = true
                        error = null
                        scope.launch {
                            try {
                                val result = api.unlock(PricingUnlockRequest(password))
                                if (!tokens.isCurrent(session)) {
                                    PricingLock.lock()
                                    loading = false
                                    error = "Your sign-in changed. Unlock pricing again."
                                    return@launch
                                }
                                PricingLock.unlock(result.pricingToken, result.expiresIn, session)
                                loading = false
                                onUnlocked()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                loading = false
                                error = (e as? ApiException)?.message ?: "Could not unlock pricing."
                            }
                        }
                    }
                },
            ) { Text(if (loading) "Unlocking…" else "Unlock") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !loading) { Text("Cancel") }
        },
        containerColor = Brand.SurfaceRaised,
        titleContentColor = Brand.Foreground,
        textContentColor = Brand.Foreground,
    )
    }
}
