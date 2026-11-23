package cloud.dcompany.erp.ui.screens.gaming

/** Test-only physical pixels: no rounding or new geometry tolerance. */
internal data class DialRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val size get() = (right - left) to (bottom - top)
    fun inside(parent: DialRect) = left >= parent.left && top >= parent.top &&
        right <= parent.right && bottom <= parent.bottom && right > left && bottom > top
    fun relativeTo(parent: DialRect) = copy(
        left = left - parent.left, right = right - parent.left,
        top = top - parent.top, bottom = bottom - parent.top,
    )
}

internal data class GamingDialSnapshot(
    val ring: DialRect,
    val pane: DialRect,
    val header: DialRect?,
    val root: DialRect,
    val viewport: DialRect,
    val ids: Map<String, Int>,
    val nativeOwner: Any,
    val nativeView: Any,
    val nativeContext: String,
    val boardScroll: Triple<Float, Float, Boolean>,
    val selection: Pair<String, String?>,
)

/** Editor scrolling/IME may translate the ancestor, never the instrument within it. */
internal fun assertEditorDialUnchanged(before: GamingDialSnapshot, after: GamingDialSnapshot) {
    val required = setOf("root", "ring", "pane", "board", "expansion")
    fun demand(value: Boolean, message: String) { if (!value) throw AssertionError(message) }
    fun validOwners(sample: GamingDialSnapshot) = sample.ids.keys.containsAll(required) &&
        sample.ids.keys.all { it in required || it == "header" } &&
        ((sample.header != null) == sample.ids.containsKey("header"))
    demand(validOwners(before) && validOwners(after), "Incomplete geometry owners")
    demand(before.nativeOwner === after.nativeOwner && before.nativeView === after.nativeView,
        "The native dial owner changed")
    demand(listOf("root", "ring", "pane", "expansion").all { before.ids[it] == after.ids[it] },
        "A permanent dial/pane owner changed")
    demand(before.selection == after.selection, "The selected station/session changed")
    demand(before.ring.inside(before.pane) && after.ring.inside(after.pane),
        "The permanent dial escaped its control pane")
    demand(before.ring.size == after.ring.size, "The permanent dial resized")
    demand(before.ring.relativeTo(before.pane) == after.ring.relativeTo(after.pane),
        "The dial moved within its control pane")
    demand(before.pane.left == after.pane.left && before.pane.top == after.pane.top ||
        before.nativeContext != after.nativeContext || before.boardScroll != after.boardScroll,
        "The editor ancestor moved without a native-window or board-scroll change")
}

/** A status-only mutation must preserve its immediately preceding root/context snapshot. */
internal fun assertStatusDialUnchanged(before: GamingDialSnapshot, after: GamingDialSnapshot) {
    assertEditorDialUnchanged(before, after)
    if (before.header == null || after.header == null ||
        before.ids != after.ids || before.nativeContext != after.nativeContext ||
        before.boardScroll != after.boardScroll || before.root != after.root ||
        before.viewport != after.viewport || before.pane != after.pane ||
        before.ring != after.ring || before.header != after.header
    ) throw AssertionError("A status transition changed dial geometry or its native/board context")
}
