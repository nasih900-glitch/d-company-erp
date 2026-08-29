package cloud.dcompany.erp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.ui.theme.Brand

/**
 * First-run terminal assignment shown before any cached workspace is rendered.
 *
 * A terminal is an accounting and shift boundary, not a cosmetic preference.
 * The employee must make an explicit choice when a branch has multiple tills;
 * silently selecting the first response could attach sales to the wrong shift.
 */
@Composable
fun TerminalSelectionScreen(
    employeeName: String,
    terminals: List<Terminal>,
    choosing: Boolean,
    error: String?,
    isReassignment: Boolean = false,
    previousTerminalName: String? = null,
    onConfirm: (Terminal) -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
) {
    var selectedId by rememberSaveable(terminals.map(Terminal::id)) {
        mutableStateOf<String?>(null)
    }
    val selected = terminals.firstOrNull { it.id == selectedId }

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    if (isReassignment) "Change this tablet's till" else "Select this tablet's till",
                    style = MaterialTheme.typography.headlineSmall,
                    color = Brand.Foreground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Signed in as $employeeName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                    textAlign = TextAlign.Center,
                )
                Text(
                    if (isReassignment) {
                        "Choose the new physical till this tablet is beside. Nothing is selected " +
                            "automatically, and the previous assignment remains available until you confirm."
                    } else {
                        "Choose the physical till this tablet is beside. Sales, shifts and receipts " +
                            "will be recorded against it."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Brand.ForegroundMuted,
                    textAlign = TextAlign.Center,
                )
            }

            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (terminals.isEmpty()) {
                    Text(
                        if (isReassignment) {
                            "No other till is currently available for this branch."
                        } else {
                            "No till is currently available for this branch."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Brand.ForegroundMuted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
                terminals.forEach { terminal ->
                    val isSelected = terminal.id == selectedId
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = isSelected,
                                enabled = !choosing,
                                role = Role.RadioButton,
                                onClick = { selectedId = terminal.id },
                            ),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isSelected) {
                                Brand.Gold.copy(alpha = 0.14f)
                            } else {
                                Brand.Surface
                            },
                        ),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            RadioButton(selected = isSelected, onClick = null, enabled = !choosing)
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(
                                    terminal.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Brand.Foreground,
                                )
                                Text(
                                    TerminalPurpose.displayLabel(terminal.purpose),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (TerminalPurpose.isKnown(terminal.purpose)) {
                                        Brand.ForegroundMuted
                                    } else {
                                        Brand.Warning
                                    },
                                )
                            }
                        }
                    }
                }
            }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !choosing,
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                ) {
                    Text("Refresh till list")
                }
            }

            Button(
                onClick = { selected?.let(onConfirm) },
                enabled = selected != null && !choosing,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (choosing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = Brand.Background,
                    )
                } else {
                    Text(if (isReassignment) "Change to selected till" else "Use selected till")
                }
            }

            TextButton(onClick = onExit, enabled = !choosing) {
                Text(
                    if (isReassignment) {
                        "Keep ${previousTerminalName?.takeIf(String::isNotBlank) ?: "previous till"}"
                    } else {
                        "Sign in as someone else"
                    },
                )
            }
        }
    }
}
