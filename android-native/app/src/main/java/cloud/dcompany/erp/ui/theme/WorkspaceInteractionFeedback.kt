package cloud.dcompany.erp.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private data class FocusRingColors(val accent: Color, val contrast: Color)
private val LocalFocusRingColors = compositionLocalOf { FocusRingColors(NightPalette.Gold, NightPalette.Background) }

/** Nested MaterialTheme hosts replace LocalIndication, so each host reinstalls this policy. */
@Composable
internal fun WorkspaceInteractionFeedback(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides WorkspaceFocusIndication,
        LocalFocusRingColors provides FocusRingColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
        content = content,
    )
}

private data object WorkspaceFocusIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = WorkspaceFocusNode(interactionSource)
}

private class WorkspaceFocusNode(private val source: InteractionSource) : Modifier.Node(),
    DrawModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {
    private val activeFocus = mutableSetOf<FocusInteraction.Focus>()
    private var focused = false
    private var colors = FocusRingColors(NightPalette.Gold, NightPalette.Background)

    override fun onAttach() {
        observeColors()
        // Subscribe before the attaching clickable can publish its initial focus interaction.
        coroutineScope.launch(start = CoroutineStart.UNDISPATCHED) {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is FocusInteraction.Focus -> activeFocus.add(interaction)
                    is FocusInteraction.Unfocus -> activeFocus.remove(interaction.focus)
                    else -> Unit
                }
                val next = activeFocus.isNotEmpty()
                if (next != focused) {
                    focused = next
                    invalidateDraw()
                }
            }
        }
    }

    override fun onObservedReadsChanged() = observeColors()

    private fun observeColors() = observeReads {
        val next = currentValueOf(LocalFocusRingColors)
        if (colors != next) {
            colors = next
            if (focused) invalidateDraw()
        }
    }

    override fun onDetach() {
        activeFocus.clear()
        focused = false
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        val outer = 2.dp.toPx()
        val inner = 1.dp.toPx()
        fun ring(color: Color, inset: Float, width: Float) {
            val bounds = Size(size.width - inset * 2, size.height - inset * 2)
            if (bounds.width > 0 && bounds.height > 0) {
                drawRect(color, Offset(inset, inset), bounds, style = Stroke(width))
            }
        }
        ring(colors.accent, outer / 2, outer)
        // The contrasting inner edge remains visible on a selected accent-colored row.
        ring(colors.contrast, outer + inner / 2, inner)
    }
}
