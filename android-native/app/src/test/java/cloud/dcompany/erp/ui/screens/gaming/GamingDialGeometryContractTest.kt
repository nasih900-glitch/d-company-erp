package cloud.dcompany.erp.ui.screens.gaming

import org.junit.Assert.assertThrows
import org.junit.Test

class GamingDialGeometryContractTest {
    private fun sample() = GamingDialSnapshot(
        ring = DialRect(120f, 220f, 364f, 464f),
        pane = DialRect(100f, 200f, 900f, 600f),
        header = DialRect(0f, 0f, 1000f, 100f),
        root = DialRect(0f, 0f, 1000f, 800f),
        viewport = DialRect(0f, 100f, 1000f, 800f),
        ids = listOf("root", "ring", "pane", "header", "board", "expansion")
            .mapIndexed { index, key -> key to index }.toMap(),
        nativeOwner = Any(), nativeView = Any(), nativeContext = "attached/focused/IME-hidden",
        boardScroll = Triple(1f, 8f, false), selection = "station" to "session",
    )

    @Test fun unchangedStatusPasses() {
        val before = sample()
        assertStatusDialUnchanged(before, before.copy())
    }

    @Test fun ancestorTranslationIsAllowedOnlyDuringEditorInteraction() {
        val before = sample()
        fun DialRect.up() = copy(top = top - 100f, bottom = bottom - 100f)
        val translated = before.copy(ring = before.ring.up(), pane = before.pane.up(),
            nativeContext = "attached/focused/IME-visible", boardScroll = Triple(2f, 8f, false))
        assertEditorDialUnchanged(before, translated)
        assertThrows(AssertionError::class.java) { assertStatusDialUnchanged(before, translated) }
        assertThrows(AssertionError::class.java) {
            assertEditorDialUnchanged(before, translated.copy(
                nativeContext = before.nativeContext, boardScroll = before.boardScroll,
            ))
        }
    }

    @Test fun ringOnlyTranslationAndResizeFailEvenInEditorPhase() {
        val before = sample()
        for (ring in listOf(before.ring.copy(top = 221f, bottom = 465f),
            before.ring.copy(right = 365f))) {
            assertThrows(AssertionError::class.java) {
                assertEditorDialUnchanged(before, before.copy(ring = ring))
            }
        }
    }

    @Test fun anUncomposedEditorHeaderIsExplicitlyAbsentAndCannotServeAsStatusEvidence() {
        val before = sample()
        val withoutHeader = before.copy(header = null, ids = before.ids - "header")
        assertEditorDialUnchanged(before, withoutHeader)
        assertThrows(AssertionError::class.java) { assertStatusDialUnchanged(before, withoutHeader) }
        assertThrows(AssertionError::class.java) { assertStatusDialUnchanged(withoutHeader, withoutHeader) }
        assertThrows(AssertionError::class.java) {
            assertEditorDialUnchanged(before, before.copy(header = null))
        }
    }

    @Test fun lostContainmentAndMissingOrRecreatedOwnersFail() {
        val before = sample()
        for (after in listOf(
            before.copy(pane = before.pane.copy(right = 300f)),
            before.copy(ids = before.ids - "ring"),
            before.copy(ids = before.ids + ("pane" to 99)),
            before.copy(nativeOwner = Any()), before.copy(nativeView = Any()),
            before.copy(selection = "other" to "session"),
        )) assertThrows(AssertionError::class.java) { assertEditorDialUnchanged(before, after) }
    }

    @Test fun statusCannotAcceptNewViewportScrollHeaderOrOwnerContext() {
        val before = sample()
        for (after in listOf(
            before.copy(nativeContext = "different physical window"),
            before.copy(viewport = before.viewport.copy(bottom = 700f)),
            before.copy(boardScroll = Triple(2f, 8f, false)),
            before.copy(boardScroll = Triple(1f, 9f, false)),
            before.copy(header = checkNotNull(before.header).copy(top = 1f, bottom = 101f)),
            before.copy(ids = before.ids + ("header" to 99)),
        )) assertThrows(AssertionError::class.java) { assertStatusDialUnchanged(before, after) }
    }
}
