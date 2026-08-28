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
import androidx.compose.foundation.LocalIndication
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
            .let { if (onClick != null) it.heightIn(min = 48.dp) else it }
            .clip(Radius.shapeLg)
            .background(bg)
            .border(1.dp, borderColor, Radius.shapeLg)
            .let {
                if (onClick != null) {
                    it.clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        onClick = onClick,
                    )
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
    ShimmerBlock(modifier, shape, rememberShimmerBrush())
}

@Composable
private fun rememberShimmerBrush(): Brush {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translate by transition.animateFloat(
        initialValue = -400f,
        targetValue = 400f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerTranslate",
    )
    return Brush.linearGradient(
        colors = listOf(Brand.SurfaceRaised, Brand.SurfaceOverlay, Brand.SurfaceRaised),
        start = Offset(translate - 200f, 0f),
        end = Offset(translate + 200f, 200f),
    )
}

@Composable
private fun ShimmerBlock(
    modifier: Modifier,
    shape: RoundedCornerShape,
    brush: Brush,
) {
    Box(modifier.clip(shape).background(brush))
}

@Composable
fun LoadingSkeleton(modifier: Modifier = Modifier, lines: Int = 4) {
    // One animation clock drives the whole placeholder. Starting a separate
    // infinite transition for every row needlessly multiplies frame work on
    // the tablet while a slow network response is already under pressure.
    val brush = rememberShimmerBrush()
    Column(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = "Loading content"
                stateDescription = "In progress"
                liveRegion = LiveRegionMode.Polite
            }
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        repeat(lines) { i ->
            ShimmerBlock(
                Modifier.fillMaxWidth().height(if (i == 0) 28.dp else 18.dp),
                Radius.shapeMd,
                brush,
            )
        }
    }
}

@Composable
fun Loading(modifier: Modifier = Modifier) = Box(modifier.fillMaxSize(), Alignment.Center) {
    CircularProgressIndicator(
        color = Brand.Gold,
        modifier = Modifier.semantics {
            contentDescription = "Loading"
            stateDescription = "In progress"
            liveRegion = LiveRegionMode.Polite
        },
    )
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier) {
    DesignedEmptyState(
        title = title,
        body = body,
        modifier = modifier.fillMaxSize(),
    )
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
        Text(text, color = if (rejected) Brand.Danger else Brand.Warning, modifier = Modifier.weight(1f))
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
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    singleLine: Boolean = true,
    isError: Boolean = false,
    supportingText: String? = null,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    keyboardActions: androidx.compose.foundation.text.KeyboardActions = androidx.compose.foundation.text.KeyboardActions.Default,
    visualTransformation: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = singleLine,
        enabled = enabled,
        isError = isError,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        visualTransformation = visualTransformation,
        supportingText = supportingText?.let { message ->
            {
                Text(
                    message,
                    modifier = Modifier.semantics {
                        if (isError) liveRegion = LiveRegionMode.Assertive
                    },
                )
            }
        },
        shape = Radius.shapeMd,
        colors = fieldColors(),
        modifier = modifier.fillMaxWidth(),
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
fun DecimalField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    allowNegative: Boolean = false,
) {
    Field(
        label = label,
        value = value,
        modifier = modifier,
        onChange = { onValueChange(filterDecimal(it, allowNegative)) },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
        ),
    )
}

private fun filterDecimal(raw: String, allowNegative: Boolean): String {
    val sb = StringBuilder()
    var dotSeen = false
    raw.forEachIndexed { index, c ->
        when {
            c.isDigit() -> sb.append(c)
            c == '.' && !dotSeen -> { dotSeen = true; sb.append(c) }
            c == '-' && allowNegative && index == 0 -> sb.append(c)
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
        shape = Radius.shapeMd,
        colors = ButtonDefaults.buttonColors(
            containerColor = Brand.Gold,
            contentColor = Brand.Background,
            disabledContainerColor = Brand.SurfaceRaised,
            disabledContentColor = Brand.ForegroundFaint,
        ),
        modifier = modifier.heightIn(min = 48.dp),
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
    width: androidx.compose.ui.unit.Dp = 520.dp,
    confirmEnabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        modifier = Modifier.widthIn(max = width).fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text(title, color = Brand.Foreground) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(Spacing.md),
            ) {
                error?.let {
                    Text(
                        it,
                        color = Brand.Danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
                Column(
                    modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md),
                ) {
                    content()
                }
            }
        },
        confirmButton = {
            PrimaryButton(onClick = onConfirm, enabled = confirmEnabled && !busy) {
                Text(if (busy) "$confirmLabel…" else confirmLabel)
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
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth(0.92f).imePadding(),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        containerColor = Brand.SurfaceOverlay,
        shape = Radius.shapeLg,
        title = { Text(title, color = Brand.Foreground) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(body, color = Brand.ForegroundMuted)
                error?.let {
                    Text(
                        it,
                        color = Brand.Danger,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm, enabled = !busy,
                shape = Radius.shapeMd,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (danger) Brand.Danger else Brand.Gold,
                    contentColor = Brand.Background,
                ),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") } },
    )
}
