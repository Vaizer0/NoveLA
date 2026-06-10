package my.noveldokusha.coreui.theme

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import my.noveldokusha.coreui.theme.colorscheme.BaseColorScheme
import my.noveldokusha.coreui.theme.colorscheme.CatppuccinColorScheme
import my.noveldokusha.coreui.theme.colorscheme.CloudflareColorScheme
import my.noveldokusha.coreui.theme.colorscheme.CottoncandyColorScheme
import my.noveldokusha.coreui.theme.colorscheme.DoomColorScheme
import my.noveldokusha.coreui.theme.colorscheme.GreenAppleColorScheme
import my.noveldokusha.coreui.theme.colorscheme.LavenderColorScheme
import my.noveldokusha.coreui.theme.colorscheme.MatrixColorScheme
import my.noveldokusha.coreui.theme.colorscheme.MidnightDuskColorScheme
import my.noveldokusha.coreui.theme.colorscheme.MochaColorScheme
import my.noveldokusha.coreui.theme.colorscheme.MonetColorScheme
import my.noveldokusha.coreui.theme.colorscheme.MonochromeColorScheme
import my.noveldokusha.coreui.theme.colorscheme.NordColorScheme
import my.noveldokusha.coreui.theme.colorscheme.SapphireColorScheme
import my.noveldokusha.coreui.theme.colorscheme.StrawberryDaiquiriColorScheme
import my.noveldokusha.coreui.theme.colorscheme.TachiyomiColorScheme
import my.noveldokusha.coreui.theme.colorscheme.TakoColorScheme
import my.noveldokusha.coreui.theme.colorscheme.TealTurquoiseColorScheme
import my.noveldokusha.coreui.theme.colorscheme.TidalWaveColorScheme
import my.noveldokusha.coreui.theme.colorscheme.YinYangColorScheme
import my.noveldokusha.coreui.theme.colorscheme.YotsubaColorScheme
import my.noveldokusha.coreui.mappers.toDarkMode

@Composable
fun Theme(
    themeProvider: ThemeProvider,
    content: @Composable () -> @Composable Unit,
) {
    val appTheme = themeProvider.currentAppTheme(rememberCoroutineScope()).value
    val darkMode = themeProvider.currentDarkMode(rememberCoroutineScope()).value

    val isDark = when (darkMode) {
        DarkMode.SYSTEM -> isSystemInDarkTheme()
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        DarkMode.BLACK -> true
    }
    val isAmoled = darkMode == DarkMode.BLACK

    InternalTheme(
        appTheme = appTheme,
        isDark = isDark,
        isAmoled = isAmoled,
        content = content,
    )
}

@Composable
fun Theme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    darkMode: DarkMode = DarkMode.SYSTEM,
    themeProvider: ThemeProvider? = null,
    content: @Composable () -> @Composable Unit,
) {
    val resolvedDarkMode = if (themeProvider != null) {
        val isSystemDark = !isSystemInDarkTheme()
        val followSystem = themeProvider.followSystem(rememberCoroutineScope())
        val selectedTheme = themeProvider.currentTheme(rememberCoroutineScope())

        when {
            followSystem.value -> when {
                isSystemDark && !selectedTheme.value.isLight -> DarkMode.LIGHT
                !isSystemDark && selectedTheme.value.isLight -> DarkMode.DARK
                else -> selectedTheme.value.toDarkMode
            }
            else -> selectedTheme.value.toDarkMode
        }
    } else {
        darkMode
    }

    val isDark = when (resolvedDarkMode) {
        DarkMode.LIGHT -> false
        DarkMode.DARK -> true
        DarkMode.BLACK -> true
        DarkMode.SYSTEM -> isSystemInDarkTheme()
    }
    val isAmoled = resolvedDarkMode == DarkMode.BLACK

    InternalTheme(
        appTheme = appTheme,
        isDark = isDark,
        isAmoled = isAmoled,
        content = content,
    )
}

@Composable
fun InternalTheme(
    appTheme: AppTheme = AppTheme.DEFAULT,
    isDark: Boolean = isSystemInDarkTheme(),
    isAmoled: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    val colorScheme = remember(context, appTheme, isDark, isAmoled) {
        val baseScheme = getBaseColorScheme(appTheme, context)
        baseScheme.getColorScheme(
            isDark = isDark,
            isAmoled = isAmoled,
            overrideDarkSurfaceContainers = appTheme != AppTheme.MONET,
        )
    }

    DisposableEffect(appTheme, isDark) {
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { isDark },
            navigationBarStyle = SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ) { isDark }
        )
        onDispose {}
    }

    val textSelectionColors = remember {
        TextSelectionColors(
            handleColor = ColorAccent,
            backgroundColor = ColorAccent.copy(alpha = 0.3f)
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = shapes,
    ) {
        CompositionLocalProvider(
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
            LocalTextSelectionColors provides textSelectionColors,
            content = content
        )
    }
}

@Composable
fun InternalTheme(
    theme: Themes,
    content: @Composable () -> Unit,
) {
    InternalTheme(
        appTheme = AppTheme.DEFAULT,
        isDark = !theme.isLight,
        isAmoled = theme == Themes.BLACK,
        content = content,
    )
}

private fun getBaseColorScheme(appTheme: AppTheme, context: Context): BaseColorScheme {
    return when (appTheme) {
        AppTheme.DEFAULT -> TachiyomiColorScheme
        AppTheme.MONET -> MonetColorScheme(context)
        AppTheme.GREEN_APPLE -> GreenAppleColorScheme
        AppTheme.LAVENDER -> LavenderColorScheme
        AppTheme.MIDNIGHT_DUSK -> MidnightDuskColorScheme
        AppTheme.STRAWBERRY_DAIQUIRI -> StrawberryDaiquiriColorScheme
        AppTheme.TAKO -> TakoColorScheme
        AppTheme.TEALTURQUOISE -> TealTurquoiseColorScheme
        AppTheme.TIDAL_WAVE -> TidalWaveColorScheme
        AppTheme.YOTSUBA -> YotsubaColorScheme
        AppTheme.MONOCHROME -> MonochromeColorScheme
        AppTheme.CATPPUCCIN -> CatppuccinColorScheme
        AppTheme.NORD -> NordColorScheme
        AppTheme.YINYANG -> YinYangColorScheme
        AppTheme.CLOUDFLARE -> CloudflareColorScheme
        AppTheme.COTTONCANDY -> CottoncandyColorScheme
        AppTheme.DOOM -> DoomColorScheme
        AppTheme.MATRIX -> MatrixColorScheme
        AppTheme.MOCHA -> MochaColorScheme
        AppTheme.SAPPHIRE -> SapphireColorScheme
    }
}

// Re-export old function for backward compatibility
@Composable
fun ThemeOld(
    themeProvider: ThemeProvider,
    content: @Composable () -> @Composable Unit,
) {
    Theme(
        appTheme = AppTheme.DEFAULT,
        darkMode = DarkMode.SYSTEM,
        themeProvider = themeProvider,
        content = content,
    )
}