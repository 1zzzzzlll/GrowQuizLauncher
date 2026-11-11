package dev.plume

/**
 * What kind of device this build runs on. One apk serves TVs, phones and tablets: a parent
 * side-loads it onto an old phone as a study machine as often as onto a TV. Reports could not tell
 * the two apart before, so a phone's problem and a TV's problem looked the same in the same fields —
 * and the fixes they need are opposite (a TV wants its home screen covered, a phone that is also the
 * parent's own does not).
 *
 * Threshold taken from Android's own tablet split: smallest width 600 dp.
 */
object DeviceForm {
    const val TV = "tv"
    const val PHONE = "phone"
    const val TABLET = "tablet"
    const val UNKNOWN = "unknown"

    const val TABLET_MIN_WIDTH_DP = 600

    /**
     * A set that declares the television hardware or the leanback software says tv — and so does one
     * whose running configuration is the television UI mode, which is the third answer a firmware
     * can give and the only one left when both feature flags are missing (TCL ak30a5, Android 9:
     * neither flag, yet a 1920x1080 set-top screen). Otherwise a touch screen tells phone from
     * tablet. None of the three means unknown: calling a domestic TV a phone would let a phone-only
     * rule reach a TV.
     */
    fun of(
        leanback: Boolean,
        television: Boolean,
        touchscreen: Boolean,
        smallestWidthDp: Int,
        uiModeTv: Boolean = false,
    ): String = when {
        television || leanback || uiModeTv -> TV
        touchscreen && smallestWidthDp >= TABLET_MIN_WIDTH_DP -> TABLET
        touchscreen -> PHONE
        else -> UNKNOWN
    }
}
