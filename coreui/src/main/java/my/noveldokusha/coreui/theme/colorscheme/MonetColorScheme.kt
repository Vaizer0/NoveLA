package my.noveldokusha.coreui.theme.colorscheme

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme

/**
 * Material You dynamic color scheme based on system wallpaper.
 * Falls back to TachiyomiColorScheme on older Android versions.
 */
internal class MonetColorScheme(context: Context) : BaseColorScheme() {

    private val monet = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        MonetDynamicColorScheme(context)
    } else {
        TachiyomiColorScheme
    }

    override val darkScheme
        get() = monet.darkScheme

    override val lightScheme
        get() = monet.lightScheme
}

@RequiresApi(Build.VERSION_CODES.S)
private class MonetDynamicColorScheme(context: Context) : BaseColorScheme() {
    override val lightScheme = dynamicLightColorScheme(context)
    override val darkScheme = dynamicDarkColorScheme(context)
}