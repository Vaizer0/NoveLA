package my.noveldokusha.coreui

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.asLiveData
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.drop
import my.noveldokusha.coreui.mappers.toTheme
import my.noveldokusha.coreui.theme.ThemeProvider
import my.noveldokusha.coreui.theme.Themes
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.core.Toasty
import javax.inject.Inject

@AndroidEntryPoint
open class BaseActivity : AppCompatActivity() {

    val appPreferences: AppPreferences by lazy { AppPreferences(applicationContext) }

    @Inject
    lateinit var themeProvider: ThemeProvider

    @Inject
    lateinit var toasty: Toasty

    private val defaultBackPressedCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            // Вызываем стандартное поведение onBackPressed
            if (hasWindowFocus()) {
                finish()
            }
        }
    }

    init {
        // Добавляем стандартный callback при создании активности
        onBackPressedDispatcher.addCallback(this, defaultBackPressedCallback)
    }

    private fun getAppTheme(): Int {
        val theme = appPreferences.THEME_ID.value.toTheme
        if (!appPreferences.THEME_FOLLOW_SYSTEM.value)
            return theme.themeId

        val isSystemThemeLight = !isSystemInDarkTheme()
        if (isSystemThemeLight && !theme.isLight) return Themes.LIGHT.themeId
        if (!isSystemThemeLight && theme.isLight) return Themes.DARK.themeId
        return theme.themeId
    }

    private fun isSystemInDarkTheme(): Boolean {
        val uiMode = resources.configuration.uiMode
        return (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    // This will remain until Reader Screen has no View XML usages
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getAppTheme())
        appPreferences.THEME_ID.flow().drop(1).asLiveData().observe(this) { recreate() }
        appPreferences.THEME_FOLLOW_SYSTEM.flow().drop(1).asLiveData().observe(this) { recreate() }
        super.onCreate(savedInstanceState)
    }

    protected fun addOnBackPressedCallback(callback: OnBackPressedCallback) {
        onBackPressedDispatcher.addCallback(this, callback)
    }

    protected fun removeOnBackPressedCallback(callback: OnBackPressedCallback) {
        callback.remove()
    }
}