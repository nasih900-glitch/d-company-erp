package cloud.dcompany.erp.ui.screens.gaming

internal enum class GamingDialogBodyFit { Unknown, Fits, ScrollChrome }

internal fun gamingPinnedBodyCapacityPx(
    safeHeightPx: Int,
    titleHeightPx: Int?,
    footerHeightPx: Int?,
    verticalPaddingPx: Int,
    gapsPx: Int,
    bodyCapPx: Int,
): Int? {
    if (safeHeightPx <= 0 || safeHeightPx == Int.MAX_VALUE || titleHeightPx == null || footerHeightPx == null ||
        titleHeightPx < 0 || footerHeightPx < 0 || verticalPaddingPx < 0 || gapsPx < 0 || bodyCapPx < 0
    ) return null
    return minOf(
        bodyCapPx.toLong(),
        (safeHeightPx.toLong() - titleHeightPx - footerHeightPx - verticalPaddingPx - gapsPx).coerceAtLeast(0),
    ).toInt()
}

internal fun gamingDialogBodyFit(capacityPx: Int?, minimumOwnerHeightPx: Int): GamingDialogBodyFit = when {
    capacityPx == null || capacityPx < 0 || minimumOwnerHeightPx <= 0 -> GamingDialogBodyFit.Unknown
    minimumOwnerHeightPx <= capacityPx -> GamingDialogBodyFit.Fits
    else -> GamingDialogBodyFit.ScrollChrome
}
