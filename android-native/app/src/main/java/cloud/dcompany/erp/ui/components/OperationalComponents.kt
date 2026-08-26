package cloud.dcompany.erp.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cloud.dcompany.erp.ui.theme.Brand
import cloud.dcompany.erp.ui.theme.Radius
import cloud.dcompany.erp.ui.theme.Spacing

enum class UiTone { Neutral, Brand, Success, Warning, Danger, Information }

enum class ActionIntent { Primary, Secondary, Success, Warning, Destructive, Quiet }

private data class ToneColors(
    val foreground: Color,
    val container: Color,
    val border: Color,
)

private fun toneColors(tone: UiTone): ToneColors = when (tone) {
    UiTone.Neutral -> ToneColors(Brand.ForegroundMuted, Brand.SurfaceHover, Brand.Border)
    UiTone.Brand -> ToneColors(Brand.Gold, Brand.Gold.copy(alpha = 0.12f), Brand.Gold.copy(alpha = 0.38f))
    UiTone.Success -> ToneColors(Brand.Good, Brand.GoodMuted, Brand.Good.copy(alpha = 0.34f))
    UiTone.Warning -> ToneColors(Brand.Warning, Brand.WarningMuted, Brand.Warning.copy(alpha = 0.36f))
    UiTone.Danger -> ToneColors(Brand.Danger, Brand.DangerMuted, Brand.Danger.copy(alpha = 0.36f))
    UiTone.Information -> ToneColors(
        Brand.Information,
        Brand.InformationMuted,
        Brand.Information.copy(alpha = 0.36f),
    )
}

/** Colour, icon and text are always supplied together so operational status
 * remains understandable for colour-blind users and screen readers. */
@Composable
fun OperationalStatusBadge(
    label: String,
    tone: UiTone,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Circle,
) {
    val colors = toneColors(tone)
    Row(
        modifier.clip(Radius.shapePill).background(colors.container)
            .border(1.dp, colors.border, Radius.shapePill)
            .semantics { contentDescription = "Status: $label" }
            .padding(horizontal = Spacing.sm, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(9.dp))
        Text(
            label.uppercase(),
            color = colors.foreground,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    detail: String,
    icon: ImageVector,
    tone: UiTone = UiTone.Brand,
    modifier: Modifier = Modifier,
) {
    val colors = toneColors(tone)
    Row(
        modifier.heightIn(min = 88.dp).clip(Radius.shapeLg).background(Brand.Surface)
            .border(1.dp, Brand.BorderSubtle, Radius.shapeLg)
            .padding(Spacing.md),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(Radius.shapeMd).background(colors.container),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(23.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                title,
                color = Brand.ForegroundMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                value,
                color = Brand.Foreground,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
            )
            Text(
                detail,
                color = colors.foreground,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
fun OperationalBanner(
    title: String,
    detail: String,
    tone: UiTone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    action: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = toneColors(tone)
    Row(
        modifier.fillMaxWidth().clip(Radius.shapeMd).background(colors.container)
            .border(1.dp, colors.border, Radius.shapeMd)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Icon(icon, contentDescription = null, tint = colors.foreground, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = Brand.Foreground, style = MaterialTheme.typography.labelLarge)
            Text(detail, color = Brand.ForegroundMuted, style = MaterialTheme.typography.labelSmall)
        }
        action?.invoke(this)
    }
}

@Composable
fun ErpButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    intent: ActionIntent = ActionIntent.Primary,
    enabled: Boolean = true,
    busy: Boolean = false,
    leadingIcon: ImageVector? = null,
) {
    val (container, content) = when (intent) {
        ActionIntent.Primary -> Brand.Gold to Brand.Background
        ActionIntent.Secondary -> Brand.SurfaceHover to Brand.Foreground
        ActionIntent.Success -> Brand.Good to Brand.Background
        ActionIntent.Warning -> Brand.Warning to Brand.Background
        ActionIntent.Destructive -> Brand.Danger to Brand.Background
        ActionIntent.Quiet -> Color.Transparent to Brand.ForegroundMuted
    }
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = Radius.shapeMd,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = content,
            disabledContainerColor = Brand.SurfaceRaised,
            disabledContentColor = Brand.Disabled,
        ),
        modifier = modifier.heightIn(min = 48.dp),
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = content)
        } else {
            leadingIcon?.let {
                Icon(it, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(Spacing.sm))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

@Composable
fun NumericValue(
    value: String,
    modifier: Modifier = Modifier,
    color: Color = Brand.Foreground,
    style: TextStyle = MaterialTheme.typography.headlineMedium,
) {
    Text(
        value,
        modifier = modifier,
        color = color,
        style = style.copy(fontFeatureSettings = "tnum"),
        fontWeight = FontWeight.Bold,
        maxLines = 1,
    )
}
