package cloud.dcompany.erp.ui.screens

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.core.net.Terminal
import cloud.dcompany.erp.core.auth.TerminalPurpose
import cloud.dcompany.erp.ui.components.liquidWorkspaceSurface
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundSlot
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundUiState
import cloud.dcompany.erp.ui.theme.WorkspaceBackgroundRenderer
import cloud.dcompany.erp.ui.theme.WorkspaceGlassRole

/**
 * First-run terminal assignment shown before any cached workspace is rendered.
 *
 * A terminal is an accounting and shift boundary, not a cosmetic preference.
 * The employee must make an explicit choice when a branch has multiple tills;
 * silently selecting the first response could attach sales to the wrong shift.
 */
@Composable
internal fun TerminalSelectionScreen(
    employeeName: String,
    selectionCompanyId: String,
    selectionBranchId: String?,
    selectionUserId: String,
    terminals: List<Terminal>,
    choosing: Boolean,
    error: String?,
    isReassignment: Boolean = false,
    previousTerminalName: String? = null,
    onConfirm: (Terminal) -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
    backgroundState: WorkspaceBackgroundUiState,
    trailingOverlay: @Composable BoxScope.() -> Unit = {},
) {
    val terminalMembership = terminals.map(Terminal::id).distinct().sorted()
    val selectionSaver = remember(
        selectionCompanyId,
        selectionBranchId,
        selectionUserId,
        terminalMembership,
    ) {
        listSaver<TerminalSelectionSaveableState, String>(
            save = { state ->
                buildList {
                    add(selectionCompanyId)
                    add(if (selectionBranchId == null) "0" else "1")
                    add(selectionBranchId.orEmpty())
                    add(selectionUserId)
                    add(if (state.selectedId == null) "0" else "1")
                    add(state.selectedId.orEmpty())
                    addAll(terminalMembership)
                }
            },
            restore = { saved ->
                val sameAuthorityAndMembership = saved.size >= TerminalSelectionSavedHeaderSize &&
                    saved[0] == selectionCompanyId &&
                    saved[1] == (if (selectionBranchId == null) "0" else "1") &&
                    saved[2] == selectionBranchId.orEmpty() &&
                    saved[3] == selectionUserId &&
                    saved.drop(TerminalSelectionSavedHeaderSize) == terminalMembership
                TerminalSelectionSaveableState(
                    selectedId = saved.getOrNull(5)?.takeIf {
                        sameAuthorityAndMembership &&
                            saved.getOrNull(4) == "1" &&
                            it in terminalMembership
                    },
                )
            },
        )
    }
    var selectionState by rememberSaveable(
        selectionCompanyId,
        selectionBranchId,
        selectionUserId,
        terminalMembership,
        stateSaver = selectionSaver,
    ) {
        mutableStateOf(TerminalSelectionSaveableState())
    }
    val selectedId = selectionState.selectedId
    val selected = terminals.firstOrNull { it.id == selectedId }
    val scrollState = rememberScrollState()
    val content: @Composable () -> Unit = {
        Box(
            modifier = Modifier.fillMaxSize().testTag("terminal-selection-root"),
            contentAlignment = Alignment.Center,
        ) {
            TerminalSelectionContent(
                employeeName = employeeName,
                terminals = terminals,
                choosing = choosing,
                error = error,
                isReassignment = isReassignment,
                previousTerminalName = previousTerminalName,
                selectedId = selectedId,
                scrollState = scrollState,
                onSelected = { selectionState = TerminalSelectionSaveableState(it) },
                onConfirm = { selected?.let(onConfirm) },
                onRefresh = onRefresh,
                onExit = onExit,
            )
            trailingOverlay()
        }
    }

    if (backgroundState.initializing) {
        Box(
            Modifier.fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .testTag("terminal-selection-background-initializing"),
        ) {
            content()
        }
    } else {
        WorkspaceBackgroundRenderer(
            image = backgroundState.image(WorkspaceBackgroundSlot.WORKSPACE),
            modifier = Modifier.fillMaxSize(),
            testTagPrefix = "terminal-selection",
            content = content,
        )
    }
}

private const val TerminalSelectionSavedHeaderSize = 6

private data class TerminalSelectionSaveableState(
    val selectedId: String? = null,
)

@Composable
private fun TerminalSelectionContent(
    employeeName: String,
    terminals: List<Terminal>,
    choosing: Boolean,
    error: String?,
    isReassignment: Boolean,
    previousTerminalName: String?,
    selectedId: String?,
    scrollState: ScrollState,
    onSelected: (String) -> Unit,
    onConfirm: () -> Unit,
    onRefresh: () -> Unit,
    onExit: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .clip(Radius.shapeLg)
                .liquidWorkspaceSurface(Radius.shapeLg, WorkspaceGlassRole.PANEL)
                .verticalScroll(scrollState)
                .padding(24.dp)
                .testTag("terminal-selection-panel"),
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
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "Signed in as $employeeName",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    )
                }
                terminals.forEachIndexed { index, terminal ->
                    val isSelected = terminal.id == selectedId
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(Radius.shapeMd)
                            .liquidWorkspaceSurface(
                                Radius.shapeMd,
                                if (isSelected) {
                                    WorkspaceGlassRole.SELECTED_OUTLINE
                                } else {
                                    WorkspaceGlassRole.UTILITY
                                },
                            )
                            .selectable(
                                selected = isSelected,
                                enabled = !choosing,
                                role = Role.RadioButton,
                                onClick = { onSelected(terminal.id) },
                            )
                            .testTag("terminal-selection-choice-$index"),
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
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.testTag("terminal-selection-choice-title-$index"),
                                )
                                Text(
                                    TerminalPurpose.displayLabel(terminal.purpose),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (TerminalPurpose.isKnown(terminal.purpose)) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.operationalWarning
                                    },
                                    modifier = Modifier.testTag("terminal-selection-choice-purpose-$index"),
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
                    modifier = Modifier.testTag("terminal-selection-error"),
                )
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !choosing,
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                        .testTag("terminal-selection-refresh"),
                ) {
                    Text("Refresh till list")
                }
            }

            Button(
                onClick = onConfirm,
                enabled = selectedId != null && !choosing,
                modifier = Modifier.fillMaxWidth().height(52.dp)
                    .testTag("terminal-selection-confirm"),
            ) {
                if (choosing) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.background,
                    )
                } else {
                    Text(if (isReassignment) "Change to selected till" else "Use selected till")
                }
            }

            TextButton(
                onClick = onExit,
                enabled = !choosing,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("terminal-selection-exit"),
            ) {
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
