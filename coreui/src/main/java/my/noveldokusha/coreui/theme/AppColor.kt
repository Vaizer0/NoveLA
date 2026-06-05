package my.noveldokusha.coreui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class AppColor(
    val tabSurface: Color,
    val bookSurface: Color,
    val checkboxPositive: Color,
    val checkboxNegative: Color,
    val checkboxNeutral: Color,
    val tintedSurface: Color,
    val tintedSelectedSurface: Color,
)

val light_appColor = AppColor(
    tabSurface = BgLight,
    bookSurface = SubBgLight,
    checkboxPositive = Success500,
    checkboxNegative = Error500,
    checkboxNeutral = Grey900,
    tintedSurface = SubBgLight.mix(HighlightLight, 0.65f),
    tintedSelectedSurface = SubBgLight.mix(HighlightLight, 0.75f),
)

val dark_appColor = AppColor(
    tabSurface = BgDark,
    bookSurface = SubBgDark,
    checkboxPositive = Success500,
    checkboxNegative = Error500,
    checkboxNeutral = Grey900,
    tintedSurface = SubBgDark.mix(HighlightDark, 0.65f),
    tintedSelectedSurface = SubBgDark.mix(HighlightDark, 0.75f),
)

val black_appColor = AppColor(
    tabSurface = BgBlack,
    bookSurface = SubBgBlack,
    checkboxPositive = Success500,
    checkboxNegative = Error500,
    checkboxNeutral = Grey900,
    tintedSurface = SubBgBlack.mix(HighlightDark, 0.65f),
    tintedSelectedSurface = SubBgBlack.mix(HighlightDark, 0.75f),
)

val LocalAppColor = compositionLocalOf { light_appColor }

@Suppress("UnusedReceiverParameter")
val MaterialTheme.colorApp: AppColor
    @Composable
    @ReadOnlyComposable
    get() = LocalAppColor.current