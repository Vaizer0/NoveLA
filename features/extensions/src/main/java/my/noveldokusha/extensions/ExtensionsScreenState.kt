package my.noveldokusha.extensions

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import my.noveldokusha.core.Extension

@Immutable
data class ExtensionsScreenState(
    val extensions: List<Extension> = emptyList(),
    val availableExtensions: List<ExtensionInfo> = emptyList(),
    val availableLanguages: List<ExtensionLanguage> = emptyList(), // Dynamic list of available languages
    val selectedLanguages: Set<String> = emptySet(), // empty = show all, specific language codes = filter by languages
    val isLoading: Boolean = false,
    val error: String? = null
)

@Immutable
data class ExtensionInfo(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val codeUrl: String,
    val iconUrl: String,
    val language: String,
    val isInstalled: Boolean = false,
    val isEnabled: Boolean = false,
    val isInstalling: Boolean = false,
    val isUpdateAvailable: Boolean = false
)

@Immutable
data class ExtensionLanguage(
    val code: String,
    val name: String,
    val count: Int
)

@Stable
sealed interface ExtensionsScreenEvent {
    data class OnExtensionToggle(val extensionId: String, val enabled: Boolean) : ExtensionsScreenEvent
    data class OnExtensionUninstall(val extensionId: String) : ExtensionsScreenEvent
    data class OnExtensionConfigure(val extensionId: String) : ExtensionsScreenEvent
    data object OnRefresh : ExtensionsScreenEvent

    // Filter and navigation events
    data class OnLanguageFilterToggle(val languageCode: String) : ExtensionsScreenEvent // toggle language in filter
    data class OnLanguageFilterClear(val languageCode: String?) : ExtensionsScreenEvent // null = clear all
    data object OnBackPressed : ExtensionsScreenEvent // New event for back navigation
    data class OnExtensionInstall(val extensionId: String) : ExtensionsScreenEvent
    data class OnExtensionUninstallById(val extensionId: String) : ExtensionsScreenEvent
}
