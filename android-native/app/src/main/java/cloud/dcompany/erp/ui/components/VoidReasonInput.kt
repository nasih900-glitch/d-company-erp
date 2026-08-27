package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.Brand
import kotlinx.coroutines.launch

internal const val VOID_REASON_MAX_LENGTH = 500
internal const val VOID_REASON_OTHER_ID = "other"
private val VOID_REASON_MAX_EDITOR_HEIGHT = 420.dp
internal val VOID_REASON_COMPACT_EDITOR_HEIGHT = 120.dp

internal data class VoidReasonPreset(
    val id: String,
    val reason: String,
)

/**
 * Fast, audit-readable reasons for the cancellation paths used at the counter.
 * The value shown to staff is also the value recorded by the backend; keeping
 * the two identical avoids opaque reason codes in receipts and audit exports.
 */
internal val VOID_REASON_PRESETS = listOf(
    VoidReasonPreset("guest_changed_mind", "Guest changed mind"),
    VoidReasonPreset("entered_by_mistake", "Entered by mistake"),
    VoidReasonPreset("service_unavailable", "Service unavailable"),
    VoidReasonPreset("duplicate_entry", "Duplicate entry"),
    VoidReasonPreset("test_or_training", "Test or training session"),
)

internal fun limitVoidReasonInput(raw: String): String = raw.take(VOID_REASON_MAX_LENGTH)

internal fun resolvedVoidReason(selectedId: String?, customReason: String): String =
    when (selectedId) {
        VOID_REASON_OTHER_ID -> limitVoidReasonInput(customReason).trim()
        null -> ""
        else -> VOID_REASON_PRESETS.firstOrNull { it.id == selectedId }?.reason.orEmpty()
    }

/**
 * An IME-resilient cancellation reason editor.
 *
 * Most counter cancellations can be completed with one accessible preset tap,
 * so a broken, hidden, or hardware-only OEM keyboard does not block service.
 * "Other" remains editable and explicitly requests the keyboard; the retry
 * button gives staff a visible recovery action if the first IME request is
 * ignored by Android.
 */
@Composable
fun VoidReasonInput(
    selectedId: String?,
    customReason: String,
    onPresetSelected: (String) -> Unit,
    onCustomReasonChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    compactCustomLayout: Boolean = false,
    onExitCustomMode: () -> Unit = {},
    compactCustomActions: (@Composable RowScope.() -> Unit)? = null,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val customFocus = remember { FocusRequester() }
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val customSelected = selectedId == VOID_REASON_OTHER_ID

    LaunchedEffect(customSelected, compactCustomLayout) {
        if (customSelected) {
            customFocus.requestFocus()
            // Some OEM IMEs ignore a show request made in the same frame that
            // focus enters a newly composed field. Retrying after one frame is
            // deterministic and avoids an arbitrary delay.
            withFrameNanos { }
            keyboard?.show()
            withFrameNanos { }
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(
                max = if (compactCustomLayout && customSelected) {
                    VOID_REASON_COMPACT_EDITOR_HEIGHT
                } else {
                    VOID_REASON_MAX_EDITOR_HEIGHT
                },
            )
            .verticalScroll(scrollState)
            // Standard forms can consume the IME inset normally. Compact
            // custom mode intentionally does not: its 120dp field/action body
            // must stay deterministic even when a Dialog reports no inset,
            // and must not scroll past its actions when an OEM does report it.
            .then(
                if (compactCustomLayout && customSelected) {
                    Modifier
                } else {
                    Modifier.imePadding()
                },
            ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!(compactCustomLayout && customSelected)) {
            Text("Choose a reason", color = Brand.ForegroundMuted)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "Cancellation reason choices"
                        stateDescription = resolvedVoidReason(selectedId, customReason)
                            .ifBlank { "No reason selected" }
                    },
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                VOID_REASON_PRESETS.chunked(2).forEach { rowPresets ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        rowPresets.forEach { preset ->
                            FilterChip(
                                selected = selectedId == preset.id,
                                onClick = { onPresetSelected(preset.id) },
                                label = { Text(preset.reason) },
                                modifier = Modifier
                                    .weight(1f)
                                    .semantics {
                                        role = Role.RadioButton
                                        contentDescription = "Cancellation reason: ${preset.reason}"
                                    },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Brand.Gold,
                                    selectedLabelColor = Brand.Background,
                                ),
                            )
                        }
                        if (rowPresets.size == 1) {
                            // Keep the final preset aligned with the two-column
                            // rows without stretching it across the whole dialog.
                            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                        }
                    }
                }
                FilterChip(
                    selected = customSelected,
                    onClick = { onPresetSelected(VOID_REASON_OTHER_ID) },
                    label = { Text("Other / add details") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics {
                            role = Role.RadioButton
                            contentDescription = "Cancellation reason: Other or add details"
                        },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Brand.Gold,
                        selectedLabelColor = Brand.Background,
                    ),
                )
            }
        }

        if (customSelected) {
            OutlinedTextField(
                value = customReason,
                onValueChange = { onCustomReasonChange(limitVoidReasonInput(it)) },
                label = { Text("Custom cancellation reason") },
                supportingText = if (compactCustomLayout) {
                    null
                } else {
                    { Text("Required · ${customReason.length}/$VOID_REASON_MAX_LENGTH") }
                },
                trailingIcon = if (compactCustomLayout) {
                    {
                        TextButton(
                            onClick = {
                                keyboard?.hide()
                                onExitCustomMode()
                            },
                            modifier = Modifier.heightIn(min = 48.dp),
                        ) { Text("Presets", maxLines = 1) }
                    }
                } else {
                    null
                },
                minLines = if (compactCustomLayout) 1 else 2,
                maxLines = if (compactCustomLayout) 1 else Int.MAX_VALUE,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { keyboard?.hide() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(customFocus)
                    .semantics {
                        contentDescription = "Custom cancellation reason"
                        stateDescription = if (customReason.isBlank()) {
                            "Required, empty"
                        } else {
                            "${customReason.length} of $VOID_REASON_MAX_LENGTH characters"
                        }
                    },
            )
            if (compactCustomLayout && compactCustomActions != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            customFocus.requestFocus()
                            keyboard?.show()
                            coroutineScope.launch {
                                withFrameNanos { }
                                scrollState.scrollTo(scrollState.maxValue)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = "Show keyboard for custom cancellation reason"
                            },
                    ) {
                        Text("Keyboard", maxLines = 1)
                    }
                    compactCustomActions()
                }
            } else {
                OutlinedButton(
                    onClick = {
                        customFocus.requestFocus()
                        keyboard?.show()
                        coroutineScope.launch {
                            withFrameNanos { }
                            scrollState.scrollTo(scrollState.maxValue)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .semantics {
                            contentDescription = "Show keyboard for custom cancellation reason"
                        },
                ) {
                    Text("Show keyboard")
                }
            }
        }
    }
}
