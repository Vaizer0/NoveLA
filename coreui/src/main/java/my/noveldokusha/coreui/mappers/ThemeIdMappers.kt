package my.noveldokusha.coreui.mappers

import my.noveldokusha.coreui.theme.AppTheme
import my.noveldokusha.coreui.theme.DarkMode
import my.noveldokusha.coreui.theme.Themes
import my.noveldokusha.core.appPreferences.PreferenceThemes

val PreferenceThemes.toDarkMode: DarkMode
    get() = when (this) {
        PreferenceThemes.Light -> DarkMode.LIGHT
        PreferenceThemes.Dark -> DarkMode.DARK
        PreferenceThemes.Black -> DarkMode.BLACK
    }

// Old system mappers (backward compatibility)
val PreferenceThemes.toTheme: Themes
    get() = when (this) {
        PreferenceThemes.Light -> Themes.LIGHT
        PreferenceThemes.Dark -> Themes.DARK
        PreferenceThemes.Black -> Themes.BLACK
    }

val Themes.toPreferenceTheme: PreferenceThemes
    get() = when (this) {
        Themes.LIGHT -> PreferenceThemes.Light
        Themes.DARK -> PreferenceThemes.Dark
        Themes.BLACK -> PreferenceThemes.Black
    }

val DarkMode.toOldTheme: Themes
    get() = when (this) {
        DarkMode.LIGHT -> Themes.LIGHT
        DarkMode.DARK -> Themes.DARK
        DarkMode.BLACK -> Themes.BLACK
        DarkMode.SYSTEM -> Themes.LIGHT // fallback
    }

val Themes.toDarkMode: DarkMode
    get() = when (this) {
        Themes.LIGHT -> DarkMode.LIGHT
        Themes.DARK -> DarkMode.DARK
        Themes.BLACK -> DarkMode.BLACK
    }
