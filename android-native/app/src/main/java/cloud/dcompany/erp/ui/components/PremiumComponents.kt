package cloud.dcompany.erp.ui.components

import cloud.dcompany.erp.ui.theme.operationalDanger
import cloud.dcompany.erp.ui.theme.operationalDangerContainer
import cloud.dcompany.erp.ui.theme.operationalGood
import cloud.dcompany.erp.ui.theme.operationalGoodContainer
import cloud.dcompany.erp.ui.theme.operationalInformation
import cloud.dcompany.erp.ui.theme.operationalInformationContainer
import cloud.dcompany.erp.ui.theme.operationalWarning
import cloud.dcompany.erp.ui.theme.operationalWarningContainer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

/**
 * Shared high-density application components.
 *
 * These components intentionally own only presentation and interaction
 * semantics. Feature-specific action availability, permissions, totals and
 * offline behaviour remain in each feature's ViewModel.
 */

@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (actions != null && maxWidth < 720.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                PageHeaderIdentity(title, subtitle, eyebrow, Modifier.fillMaxWidth())
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageHeaderIdentity(title, subtitle, eyebrow, Modifier.weight(1f))
                actions?.let {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        content = it,
                    )
                }
            }
        }
    }
}

@Composable
private fun PageHeaderIdentity(
    title: String,
    subtitle: String,
    eyebrow: String?,
    modifier: Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            eyebrow?.let {
                Text(
                    it.uppercase(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
}

@Composable
fun ActionBar(
    modifier: Modifier = Modifier,
    leading: @Composable RowScope.() -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeLg)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        if (trailing != null && maxWidth < 640.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Row(Modifier.fillMaxWidth(), content = leading)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                    content = trailing,
                )
            }
        } else {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leading()
                if (trailing != null) {
                    Spacer(Modifier.weight(1f))
                    trailing()
                }
            }
        }
    }
}

@Composable
fun AdaptiveStatGrid(
    count: Int,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int, modifier: Modifier) -> Unit,
) {
    if (count <= 0) return
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = when {
            maxWidth >= 1_000.dp -> minOf(4, count)
            count == 4 -> 2
            maxWidth >= 720.dp -> minOf(3, count)
            else -> minOf(2, count)
        }
        val rows = (count + columns - 1) / columns
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            repeat(rows) { rowIndex ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    repeat(columns) { columnIndex ->
                        val index = rowIndex * columns + columnIndex
                        if (index < count) content(index, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    subtitle: String? = null,
    icon: ImageVector? = null,
    tone: UiTone = UiTone.Neutral,
    action: (@Composable RowScope.() -> Unit)? = null,
    elevated: Boolean = false,
    contentPadding: androidx.compose.foundation.layout.PaddingValues =
        androidx.compose.foundation.layout.PaddingValues(Spacing.lg),
    contentFillsAvailableHeight: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val accent = when (tone) {
        UiTone.Brand -> MaterialTheme.colorScheme.primary
        UiTone.Success -> MaterialTheme.colorScheme.operationalGood
        UiTone.Warning -> MaterialTheme.colorScheme.operationalWarning
        UiTone.Danger -> MaterialTheme.colorScheme.operationalDanger
        UiTone.Information -> MaterialTheme.colorScheme.operationalInformation
        UiTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = modifier.fillMaxWidth().clip(Radius.shapeLg)
            .background(if (elevated) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .border(
                1.dp,
                if (elevated) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant,
                Radius.shapeLg,
            ),
    ) {
        if (title != null || subtitle != null || icon != null || action != null) {
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (elevated) MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.56f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f),
                    )
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
                horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon?.let {
                    Box(
                        Modifier.size(38.dp).clip(Radius.shapeMd).background(accent.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(it, contentDescription = null, tint = accent, modifier = Modifier.size(20.dp))
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    title?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    }
                    subtitle?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                action?.let {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                        content = it,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column(
            modifier = Modifier.fillMaxWidth()
                .then(if (contentFillsAvailableHeight) Modifier.weight(1f) else Modifier)
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(Spacing.md),
            content = content,
        )
    }
}

@Composable
fun CompactStatCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    icon: ImageVector? = null,
    tone: UiTone = UiTone.Neutral,
) {
    val accent = when (tone) {
        UiTone.Brand -> MaterialTheme.colorScheme.primary
        UiTone.Success -> MaterialTheme.colorScheme.operationalGood
        UiTone.Warning -> MaterialTheme.colorScheme.operationalWarning
        UiTone.Danger -> MaterialTheme.colorScheme.operationalDanger
        UiTone.Information -> MaterialTheme.colorScheme.operationalInformation
        UiTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = modifier.heightIn(min = 82.dp).clip(Radius.shapeLg)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeLg)
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Box(
                Modifier.size(40.dp).clip(Radius.shapeMd).background(accent.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(it, contentDescription = null, tint = accent, modifier = Modifier.size(21.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            NumericValue(value = value, style = MaterialTheme.typography.headlineSmall)
            detail?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
fun SearchInput(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.heightIn(min = 48.dp),
        enabled = enabled,
        singleLine = true,
        shape = Radius.shapeMd,
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        placeholder = { Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surface,
            focusedIndicatorColor = MaterialTheme.colorScheme.primary,
            unfocusedIndicatorColor = MaterialTheme.colorScheme.outline,
            disabledIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
            focusedTextColor = MaterialTheme.colorScheme.onSurface,
            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
            cursorColor = MaterialTheme.colorScheme.primary,
        ),
    )
}

data class TabOption(val id: String, val label: String, val count: Int? = null)

@Composable
fun PremiumTabBar(
    options: List<TabOption>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .clip(Radius.shapeMd).background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, Radius.shapeMd)
            .padding(Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        options.forEach { option ->
            val selected = option.id == selectedId
            Row(
                Modifier.heightIn(min = 48.dp).clip(Radius.shapeSm)
                    .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .border(
                        1.dp,
                        if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        Radius.shapeSm,
                    )
                    .selectable(
                        selected = selected,
                        role = Role.Tab,
                        onClick = { onSelect(option.id) },
                    )
                    .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    option.label,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge,
                )
                option.count?.let {
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Text(
                            it.toString(),
                            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DesignedEmptyState(
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Inbox,
    tone: UiTone = UiTone.Neutral,
    primaryLabel: String? = null,
    onPrimary: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    primaryBusy: Boolean = false,
    primaryIcon: ImageVector? = null,
    secondaryLabel: String? = null,
    onSecondary: (() -> Unit)? = null,
) {
    BoxWithConstraints(modifier.fillMaxWidth().heightIn(min = 216.dp)) {
        val availableWidth = maxWidth
        val accent = when (tone) {
            UiTone.Brand -> MaterialTheme.colorScheme.primary
            UiTone.Success -> MaterialTheme.colorScheme.operationalGood
            UiTone.Warning -> MaterialTheme.colorScheme.operationalWarning
            UiTone.Danger -> MaterialTheme.colorScheme.operationalDanger
            UiTone.Information -> MaterialTheme.colorScheme.operationalInformation
            UiTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
        }
        // Some dense dashboard cards intentionally give the shared empty state
        // less than its preferred 216dp height. Compact the internal rhythm in
        // that case instead of letting the fixed icon/padding push copy outside
        // the card's bounds.
        val compact = maxHeight < 216.dp
        val outerPadding = if (compact) Spacing.md else Spacing.xl
        val iconBoxSize = if (compact) 48.dp else 64.dp
        val iconSize = if (compact) 24.dp else 30.dp

        Column(
            modifier = Modifier.fillMaxSize().padding(outerPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                Modifier.size(iconBoxSize).clip(Radius.shapeLg).background(MaterialTheme.colorScheme.secondaryContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outline, Radius.shapeLg),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(iconSize))
            }
            Spacer(Modifier.height(if (compact) Spacing.sm else Spacing.lg))
            Text(
                title,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(if (compact) Spacing.xs else Spacing.sm))
            Text(
                body,
                modifier = Modifier.widthIn(max = 480.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis,
            )
            if ((primaryLabel != null && onPrimary != null) || (secondaryLabel != null && onSecondary != null)) {
                Spacer(Modifier.height(if (compact) Spacing.sm else Spacing.lg))
                if (availableWidth < 420.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (primaryLabel != null && onPrimary != null) {
                            ErpButton(
                                text = primaryLabel,
                                onClick = onPrimary,
                                modifier = Modifier.fillMaxWidth(),
                                enabled = primaryEnabled,
                                busy = primaryBusy,
                                leadingIcon = primaryIcon,
                            )
                        }
                        if (secondaryLabel != null && onSecondary != null) {
                            ErpButton(
                                secondaryLabel,
                                onSecondary,
                                modifier = Modifier.fillMaxWidth(),
                                intent = ActionIntent.Secondary,
                            )
                        }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        if (secondaryLabel != null && onSecondary != null) {
                            ErpButton(secondaryLabel, onSecondary, intent = ActionIntent.Secondary)
                        }
                        if (primaryLabel != null && onPrimary != null) {
                            ErpButton(
                                text = primaryLabel,
                                onClick = onPrimary,
                                enabled = primaryEnabled,
                                busy = primaryBusy,
                                leadingIcon = primaryIcon,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DataTableHeader(
    columns: List<Pair<String, Float>>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        columns.forEach { (label, weight) ->
            Text(
                label.uppercase(),
                modifier = Modifier.weight(weight),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
        }
    }
}

@Composable
fun DataListRow(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp)
            .background(Color.Transparent)
            .let {
                if (onClick == null) it else it.clip(Radius.shapeSm).clickable(onClick = onClick)
            }
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp), content = content)
        trailing?.let {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
                content = it,
            )
        }
    }
}

@Composable
fun InfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) }
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            modifier = Modifier.weight(1f),
            color = valueColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun PanelDivider(modifier: Modifier = Modifier) {
    HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
}
