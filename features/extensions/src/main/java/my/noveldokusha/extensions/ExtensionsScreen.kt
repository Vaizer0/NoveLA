package my.noveldokusha.extensions

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.colorAccent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.skydoves.landscapist.glide.GlideImage
import my.noveldokusha.core.Extension
import my.noveldokusha.coreui.components.MyButton
import my.noveldokusha.coreui.R

@Composable
fun ExtensionsScreen(
    innerPadding: PaddingValues,
    onBackPressed: (() -> Unit)? = null,
    showExtensionsLanguageFilter: Boolean = false,
    onExtensionsLanguageFilterDismiss: () -> Unit = {}
) {
    val viewModel = hiltViewModel<ExtensionsManagerViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    UnifiedExtensionsScreen(
        innerPadding = innerPadding,
        state = state,
        viewModel = viewModel,
        onBackPressed = onBackPressed,
        showExtensionsLanguageFilter = showExtensionsLanguageFilter,
        onExtensionsLanguageFilterDismiss = onExtensionsLanguageFilterDismiss
    )
}

@Composable
private fun UnifiedExtensionsScreen(
    innerPadding: PaddingValues,
    state: ExtensionsScreenState,
    viewModel: ExtensionsManagerViewModel,
    onBackPressed: (() -> Unit)?,
    showExtensionsLanguageFilter: Boolean = false,
    onExtensionsLanguageFilterDismiss: () -> Unit = {}
) {
    // Extensions content for tab - uses available space, no fillMaxSize()
    Column(modifier = Modifier.fillMaxWidth()) {

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            state.error != null -> {
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = state.error ?: "Unknown error",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Button(onClick = { viewModel.onEvent(ExtensionsScreenEvent.OnRefresh) }) {
                            Text("Retry")
                        }
                    }
                }
            }
            else -> {
                // Filter extensions based on selected languages
                val filteredExtensions = if (state.selectedLanguages.isEmpty()) {
                    emptyList<ExtensionInfo>() // Show nothing if no languages selected
                } else {
                    state.availableExtensions.filter { it.language in state.selectedLanguages }
                }

                // Split extensions into installed and available sections
                val installedExtensions = filteredExtensions.filter { it.isInstalled }
                val availableExtensions = filteredExtensions.filter { !it.isInstalled }

                if (filteredExtensions.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            Text(
                                text = "No extensions available",
                                style = MaterialTheme.typography.bodyLarge,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = { viewModel.onEvent(ExtensionsScreenEvent.OnRefresh) }) {
                                Text("Refresh")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(paddingValues = innerPadding),
                        contentPadding = PaddingValues(bottom = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Installed extensions section
                        if (installedExtensions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Installed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ColorAccent,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 8.dp),
                                )
                            }

                            items(installedExtensions, key = { extension -> extension.id }) { extension ->
                                ExtensionListItem(
                                    extension = extension,
                                    viewModel = viewModel
                                )
                            }
                        }

                        // Available extensions section
                        if (availableExtensions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Available",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = ColorAccent,
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(top = 8.dp),
                                )
                            }

                            items(availableExtensions, key = { extension -> extension.id }) { extension ->
                                ExtensionListItem(
                                    extension = extension,
                                    viewModel = viewModel
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExtensionListItem(
    extension: ExtensionInfo,
    viewModel: ExtensionsManagerViewModel
) {
    val context = LocalContext.current

    ListItem(
        modifier = Modifier.fillMaxWidth(),
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = extension.name,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Row {
                    if (extension.isUpdateAvailable) {
                        Text(
                            text = " ⬆",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (extension.isInstalled) {
                        Text(
                            text = " ✓",
                            style = MaterialTheme.typography.titleSmall,
                            color = if (extension.isUpdateAvailable)
                                MaterialTheme.colorScheme.tertiary
                            else
                                MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                }
            }
        },
        supportingContent = {
            // Show language instead of author
            Text(
                text = "Language: ${extension.language}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "v${extension.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(4.dp))
            ) {
                if (extension.iconUrl?.isNotBlank() == true) {
                    GlideImage(
                        imageModel = { "${extension.iconUrl}" },
                        modifier = Modifier.fillMaxSize(),
                        loading = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.Center),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        failure = {
                            Icon(
                                imageVector = Icons.Default.Settings,
                                contentDescription = null,
                                modifier = Modifier
                                    .size(16.dp)
                                    .align(Alignment.Center),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier
                            .size(16.dp)
                            .align(Alignment.Center),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        trailingContent = {
            // Action button with improved visibility
            when {
                extension.isInstalling -> {
                    OutlinedButton(
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text("Installing...")
                    }
                }
                extension.isUpdateAvailable -> {
                    FilledTonalButton(
                        onClick = {
                            viewModel.onEvent(ExtensionsScreenEvent.OnExtensionInstall(extension.id))
                        },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Update")
                    }
                }
                extension.isInstalled -> {
                    FilledTonalButton(
                        onClick = {
                            viewModel.onEvent(ExtensionsScreenEvent.OnExtensionUninstallById(extension.id))
                        },
                        modifier = Modifier.height(40.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Uninstall")
                    }
                }
                else -> {
                    FilledTonalButton(
                        onClick = {
                            viewModel.onEvent(ExtensionsScreenEvent.OnExtensionInstall(extension.id))
                        },
                        modifier = Modifier.height(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.size(6.dp))
                        Text("Install")
                    }
                }
            }
        }
    )
}

@Composable
fun ExtensionsLanguageFilterDropDown(
    expanded: Boolean,
    availableLanguages: List<ExtensionLanguage>,
    selectedLanguages: Set<String>,
    onLanguageToggle: (String) -> Unit,
    onClearAll: () -> Unit,
    onRefresh: () -> Unit,
    onDismiss: () -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier.background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(8.dp)
                .widthIn(min = 128.dp)
        ) {
            Text(text = "Select Languages")

            // Refresh button
            FilledTonalButton(
                onClick = {
                    onRefresh()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.size(6.dp))
                Text("Refresh Plugins")
            }

            OutlinedCard {
                availableLanguages.forEach { language ->
                    MyButton(
                        text = "${language.name} (${language.count})",
                        onClick = { onLanguageToggle(language.code) },
                        selected = selectedLanguages.contains(language.code),
                        selectedBackgroundColor = MaterialTheme.colorScheme.secondaryContainer,
                        borderWidth = Dp.Unspecified,
                        textAlign = TextAlign.Center,
                        outerPadding = 0.dp,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(0.dp),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface
                        ),
                    )
                }
            }

            // Clear selection button
            if (selectedLanguages.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Clear Selection")
                }
            }
        }
    }
}
