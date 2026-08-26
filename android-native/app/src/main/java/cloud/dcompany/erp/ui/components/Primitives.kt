package cloud.dcompany.erp.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Motion
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

/**
 * Shared visual primitives — every screen this rebuild touched had its own
 * private copy of Panel/Field/FormDialog/etc. (several with a class-doc
 * comment anticipating exactly this consolidation). One polished, animated
 * implementation here means a design refresh happens once, not per-screen.
 */

// ============================================================================
// SURFACES
// ============================================================================

/** A card that lifts very slightly on press (scale + border brighten) so
 * tapping anything on this app's dark surfaces gives real tactile feedback,
 * not just a flat color swap. Non-interactive by default — pass [onClick]
 * to opt in. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    elevated: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed && onClick != null) 0.985f else 1f,
        tween(Motion.fast, easing = Motion.emphasized),
        label = "panelScale",
    )
    val borderColor by animateColorAsState(
        if (pressed && onClick != null) Brand.GoldMuted else Brand.BorderSubtle,
        tween(Motion.fast),
        label = "panelBorder",
    )
    val bg = if (elevated) Brand.SurfaceOverlay else Brand.Surface
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .fillMaxWidth()
            .clip(Radius.shapeLg)
            .background(bg)
            .border(1.dp, borderColor, Radius.shapeLg)
            .let {
                if (onClick != null) {
                    it.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else it
            }
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        color = Brand.Foreground,
        modifier = modifier.padding(bottom = Spacing.xs),
    )
}

// ============================================================================
// LOADING / EMPTY STATES
// ============================================================================

/** A shimmering placeholder instead of a bare spinner — reads as "content is
 * arriving here" rather than "the app froze," and matches the shape of what's
 * about to load so the layout doesn't jump when it arrives. */
@Composable
fun ShimmerBlock(modifier: Modifier = Modifier, shape: RoundedCornerShape = Radius.shapeMd) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -400f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerTranslate",
    )
    val brush = Brush.linearGradient(
        colors = listOf(Brand.SurfaceRaised, Brand.SurfaceOverlay, Brand.SurfaceRaised),
        start = Offset(translate - 200f, 0f),
        end = Offset(translate + 200f, 200f),
    )
    Box(modifier.clip(shape).background(brush))
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, lines: Int = 4) {
    Column(modifier.fillMaxWidth().padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        repeat(lines) { i ->
            ShimmerBlock(Modifier.fillMaxWidth().height(if (i == 0) 28.dp else 18.dp))
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) = Box(modifier.fillMaxSize(), Alignment.Center) {
    CircularProgressIndicator(color = Brand.Gold)
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), Alignment.Center) {
        Column(
            modifier = Modifier.width(420.dp).padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Text(title, style = MaterialTheme.typography.titleLarge, color = Brand.Foreground)
            Text(body, color = Brand.ForegroundMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ============================================================================
// BANNERS
// ============================================================================

@Composable
fun NoticeBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = true, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.SurfaceRaised)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, color = Brand.Foreground, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Dismiss") }
        }
    }
}

/** The recurring "queued / rejected, retry" row used across every offline
 * outbox screen (Events, Memberships, Settings, ...). */
@Composable
fun PendingBanner(text: String, rejected: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(Radius.shapeMd).background(Brand.SurfaceRaised)
            .semantics {
                liveRegion = if (rejected) LiveRegionMode.Assertive else LiveRegionMode.Polite
            }
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = if (rejected) Brand.Danger else Brand.GoldMuted, modifier = Modifier.weight(1f))
        if (rejected) TextButton(onClick = onRetry) { Text("Retry") }
    }
}

// ============================================================================
// CHIPS
// ============================================================================

@Composable
fun Chip(text: String, color: Color = Brand.SurfaceRaised, contentColor: Color = Brand.Foreground) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = contentColor,
        modifier = Modifier.clip(Radius.shapePill).background(color).padding(horizontal = Spacing.sm, vertical = 3.dp),
    )
}

// ============================================================================
// FORM FIELDS
// ============================================================================

@Composable
fun Field(
    label: String,
    value: String,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        keyboardOptions = keyboardOptions,
        shape = Radius.shapeMd,
        colors = fieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
fun fieldColors() = TextFieldDefaults.colors(
    focusedContainerColor = Brand.SurfaceRaised,
    unfocusedContainerColor = Brand.SurfaceRaised,
    disabledContainerColor = Brand.Surface,
    focusedIndicatorColor = Brand.Gold,
    unfocusedIndicatorColor = Brand.Border,
    focusedLabelColor = Brand.Gold,
    unfocusedLabelColor = Brand.ForegroundMuted,
    cursorColor = Brand.Gold,
    focusedTextColor = Brand.Foreground,
    unfocusedTextColor = Brand.Foreground,
)

@Composable
fun PickerField(
    label: String,
    selectedLabel: String,
    options: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
    onSelect: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (open) 180f else 0f, tween(Motion.fast), label = "chevron")
    Column(modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Brand.ForegroundMuted)
        Spacer(Modifier.height(Spacing.xs))
        Box {
            Row(
                Modifier.fillMaxWidth().clip(Radius.shapeMd)
                    .background(Brand.SurfaceRaised)
                    .semantics {
                        role = Role.DropdownList
                        contentDescription = "$label, $selectedLabel"
                        stateDescription = if (open) "Expanded" else "Collapsed"
                    }
                    .clickable(enabled = options.isNotEmpty()) { open = true }
                    .padding(horizontal = Spacing.md, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    selectedLabel,
                    color = if (options.isEmpty()) Brand.ForegroundMuted else Brand.Foreground,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = null,
                    tint = Brand.Gold,
                    modifier = Modifier.graphicsLayer { rotationZ = rotation },
                )
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { (id, text) ->
                    DropdownMenuItem(text = { Text(text) }, onClick = { open = false; onSelect(id) })
                }
            }
        }
    }
}

@Composable
fun DecimalField(value: String, onValueChange: (String) -> Unit, label: String) {
    Field(
        label = label,
        value = value,
        onChange = { onValueChange(filterDecimal(it)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        ),
    )
}

private fun filterDecimal(raw: String): String {
    val sb = StringBuilder()
    var dotSeen = false
    for (c in raw) {
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> { dotSeen = true; sb.append(c) }
        }
    }
    return sb.toString()
}

// ============================================================================
// BUTTONS
// ============================================================================

@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = Radius.shapePill,
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.Gold,
            contentColor = Brand.Background,
            disabledContainerColor = Brand.SurfaceRaised,
            disabledContentColor = Brand.ForegroundFaint,
        ),
        modifier = modifier,
        content = content,
    )
}

// ============================================================================
// DIALOGS
// ============================================================================

@Composable
fun FormDialog(
    title: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    width: androidx.compose.ui.unit.Dp = 480.dp,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.width(width),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text(title, color = Brand.Foreground) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                content()
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            PrimaryButton(onClick = onConfirm, enabled = !busy) {
                Text(if (busy) "Working…" else confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    busy: Boolean,
    error: String?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = true,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text(title, color = Brand.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(body, color = Brand.ForegroundMuted)
                error?.let { Text(it, color = Brand.Danger) }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = !busy,
                shape = Radius.shapePill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (danger) Brand.Danger else Brand.Gold,
                    contentColor = if (danger) Brand.Foreground else Brand.Background,
                ),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
