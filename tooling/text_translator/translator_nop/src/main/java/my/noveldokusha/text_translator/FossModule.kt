package my.noveldokusha.text_translator

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import my.noveldokusha.core.AppCoroutineScope
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.text_translator.domain.TranslationManager
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
object FossModule {

    @Provides
    @Singleton
    fun provideTranslationManager(
        appCoroutineScope: AppCoroutineScope,
        appPreferences: AppPreferences
    ): TranslationManager {
        val geminiManager     = TranslationManagerGemini(appCoroutineScope, appPreferences)
        val googleFreeManager = TranslationManagerGoogleFree(appCoroutineScope)
        val googlePAManager   = TranslationManagerGooglePA(appCoroutineScope, appPreferences)

        return TranslationManagerComposite(
            coroutineScope    = appCoroutineScope,
            geminiManager     = geminiManager,
            googleFreeManager = googleFreeManager,
            googlePAManager   = googlePAManager,
            appPreferences    = appPreferences
        )
    }
}