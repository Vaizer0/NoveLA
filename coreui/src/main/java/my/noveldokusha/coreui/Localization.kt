package my.noveldokusha.coreui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import my.noveldokusha.core.appPreferences.AppLanguage
import my.noveldokusha.core.appPreferences.AppPreferences
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

val LocalAppLanguage = compositionLocalOf<AppLanguage> { AppLanguage.ENGLISH }

@Composable
fun ProvideAppLanguage(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current

    val appPreferences = remember {
        EntryPointAccessors.fromApplication<AppPreferencesEntryPoint>(context)
            .appPreferences()
    }

    val currentLanguage = remember {
        appPreferences.APP_LANGUAGE.value
    }

    CompositionLocalProvider(
        LocalAppLanguage provides currentLanguage,
        content = content
    )
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface AppPreferencesEntryPoint {
    fun appPreferences(): AppPreferences
}
