package my.noveldokusha.core

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import my.noveldokusha.core.appPreferences.AppLanguage
import java.util.Locale

object LocaleManager {

    fun applyLocale(context: Context, language: AppLanguage) {
        val locale = language.locale
        Locale.setDefault(locale)

        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)

        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }

    fun applyLocaleAndRecreate(activity: androidx.activity.ComponentActivity, language: AppLanguage) {
        applyLocale(activity, language)
        activity.recreate()
    }

    fun getCurrentLocale(context: Context): Locale {
        return context.resources.configuration.locale
    }
}
