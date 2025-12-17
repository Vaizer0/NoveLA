package my.noveldokusha.core.appPreferences

import java.util.Locale

enum class AppLanguage(val locale: Locale, val displayName: String) {
    ENGLISH(Locale.ENGLISH, "English"),
    RUSSIAN(Locale("ru"), "Русский");

    companion object {
        val DEFAULT = ENGLISH

        fun fromLocale(locale: Locale): AppLanguage {
            return when (locale.language) {
                "ru" -> RUSSIAN
                else -> ENGLISH
            }
        }
    }
}
