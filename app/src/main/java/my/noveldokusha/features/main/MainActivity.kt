package my.noveldokusha.features.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import my.noveldokusha.core.LocaleManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

import androidx.core.content.IntentCompat
import dagger.hilt.android.AndroidEntryPoint
import my.noveldokusha.coreui.BaseActivity
import my.noveldokusha.coreui.components.AnimatedTransition
import my.noveldokusha.coreui.theme.Theme
import my.noveldokusha.R
import my.noveldokusha.catalogexplorer.CatalogExplorerScreen
import my.noveldokusha.libraryexplorer.LibraryScreen
import my.noveldokusha.settings.SettingsScreen
import my.noveldokusha.tooling.epub_importer.EpubImportService

private data class Page(
    @DrawableRes val iconRes: Int,
    @StringRes val stringRes: Int,
)

private val pages = listOf(
    Page(iconRes = R.drawable.ic_baseline_home_24, stringRes = R.string.title_library),
    Page(iconRes = R.drawable.ic_baseline_menu_book_24, stringRes = R.string.title_finder),
    Page(iconRes = R.drawable.ic_twotone_settings_24, stringRes = R.string.title_settings),
)


@OptIn(ExperimentalAnimationApi::class)
@AndroidEntryPoint
open class MainActivity : BaseActivity() {

    private val requestNotificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Apply saved language preference
        val language = appPreferences.APP_LANGUAGE.value
        LocaleManager.applyLocale(this, language)

        requestPushNotificationPermission()

        // Check if language was changed and recreate if needed
        if (savedInstanceState == null) { // Only on first creation
            // This is handled by the system
        }

        setContent {
            var activePageIndex by rememberSaveable { mutableIntStateOf(0) }

            BackHandler(enabled = activePageIndex != 0) {
                activePageIndex = 0
            }

            Theme(themeProvider = themeProvider) {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        AnimatedTransition(targetState = activePageIndex) {
                            when (it) {
                                0 -> LibraryScreen()
                                1 -> CatalogExplorerScreen()
                                2 -> SettingsScreen(onRestartApp = { recreate() })
                            }
                        }
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = 16.dp),
                        contentAlignment = androidx.compose.ui.Alignment.Center
                    ) {
                        Surface(
                            modifier = Modifier
                                .padding(horizontal = 56.dp)
                                .height(64.dp),
                            shape = RoundedCornerShape(32.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shadowElevation = 10.dp
                        ) {
                            NavigationBar(
                                containerColor = Color.Transparent,
                                tonalElevation = 0.dp,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                pages.forEachIndexed { pageIndex, page ->
                                    NavigationBarItem(
                                        icon = {
                                            Icon(
                                                painter = painterResource(id = page.iconRes),
                                                contentDescription = stringResource(id = page.stringRes)
                                            )
                                        },
                                        selected = activePageIndex == pageIndex,
                                        onClick = {
                                            activePageIndex = pageIndex
                                        },
                                        alwaysShowLabel = false,
                                        colors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        handleIntent(intent)
    }

    private fun requestPushNotificationPermission() {
        // check if sdk level is more than 33
        if (VERSION.SDK_INT < VERSION_CODES.TIRAMISU) {
            return
        }

        val result = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
        if (result != PackageManager.PERMISSION_GRANTED) {
            requestNotificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIntent(intent: Intent) {
        val action = intent.action ?: return
        val type = intent.type

        when (action) {
            Intent.ACTION_SEND -> {
                if (type == "application/epub+zip") {
                    handleSharedEpub(intent)
                }
            }

            Intent.ACTION_VIEW -> {
                handleViewedEpub(intent)
            }
        }
    }

    private fun handleViewedEpub(intent: Intent) {
        val epubUri: Uri? = intent.data
        if (epubUri != null) {
            EpubImportService.start(ctx = this, uri = epubUri)
        }
    }

    private fun handleSharedEpub(intent: Intent) {
        val epubUri: Uri? = IntentCompat.getParcelableExtra(
            intent, Intent.EXTRA_STREAM, Uri::class.java
        )
        if (epubUri != null) {
            EpubImportService.start(ctx = this, uri = epubUri)
        }
    }
}
