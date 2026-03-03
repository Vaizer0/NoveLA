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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.OutlinedTextField
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.colorAccent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
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
import my.noveldokusha.core.appPreferences.SortOrder
import my.noveldokusha.coreui.components.MyButton
import timber.log.Timber
import java.util.Locale
import my.noveldokusha.core.getLanguageDisplayName
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import my.noveldokusha.coreui.R


@Composable
fun ExtensionsScreen(
    innerPadding: PaddingValues,
    onBackPressed: (() -> Unit)? = null,
    showExtensionsLanguageFilter: Boolean = false,
    onExtensionsLanguageFilterDismiss: () -> Unit = {},
    onRefresh: (() -> Unit)? = null
) {
    val viewModel = hiltViewModel<ExtensionsManagerViewModel>()
    val state by viewModel.state.collectAsStateWithLifecycle()

    UnifiedExtensionsScreen(
        innerPadding = innerPadding,
        state = state,
        viewModel = viewModel,
        onBackPressed = onBackPressed,
        showExtensionsLanguageFilter = showExtensionsLanguageFilter,
        onExtensionsLanguageFilterDismiss = onExtensionsLanguageFilterDismiss,
        onRefresh = onRefresh
    )
}

@Composable
private fun UnifiedExtensionsScreen(
    innerPadding: PaddingValues,
    state: ExtensionsScreenState,
    viewModel: ExtensionsManagerViewModel,
    onBackPressed: (() -> Unit)?,
    showExtensionsLanguageFilter: Boolean = false,
    onExtensionsLanguageFilterDismiss: () -> Unit = {},
    onRefresh: (() -> Unit)? = null
) {
    var languageFilterExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = innerPadding.calculateTopPadding())
    ) {
        // Content area
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
                val filteredExtensions = if (state.selectedLanguages.isEmpty()) {
                    state.availableExtensions
                } else {
                    state.availableExtensions.filter { it.language in state.selectedLanguages }
                }

                // Sort extensions based on sort order
                val sortedExtensions = when (state.sortOrder) {
                    SortOrder.ASCENDING -> filteredExtensions.sortedBy { it.name.lowercase() }
                    SortOrder.DESCENDING -> filteredExtensions.sortedByDescending { it.name.lowercase() }
                }

                val installedExtensions = sortedExtensions.filter { it.isInstalled }
                val availableExtensions = sortedExtensions.filter { !it.isInstalled }

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
                            FilledTonalButton(
                                onClick = { viewModel.onEvent(ExtensionsScreenEvent.OnRefresh) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Refresh")
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 300.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (installedExtensions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Installed",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
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

                        if (availableExtensions.isNotEmpty()) {
                            item {
                                Text(
                                    text = "Available",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
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

    // Repository URL dialog
    if (state.showRepositoryDialog) {
        RepositoryUrlDialog(
            state = state,
            viewModel = viewModel
        )
    }
}

@Composable
private fun RepositoryUrlDialog(
    state: ExtensionsScreenState,
    viewModel: ExtensionsManagerViewModel
) {
    var tempUrl by remember { mutableStateOf(state.repositoryUrl) }

    AlertDialog(
        onDismissRequest = {
            viewModel.onEvent(ExtensionsScreenEvent.OnHideRepositoryDialog)
        },
        title = {
            Text("Repository URL")
        },
        text = {
            OutlinedTextField(
                value = tempUrl,
                onValueChange = { tempUrl = it },
                label = { Text("Extensions Repository URL") },
                placeholder = { Text("https://raw.githubusercontent.com/.../index.json") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    focusedLabelColor = MaterialTheme.colorScheme.primary,
                    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedTextColor = MaterialTheme.colorScheme.onSurface,
                    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                    cursorColor = MaterialTheme.colorScheme.primary,
                )
            )
        },
        confirmButton = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MyButton(
                    text = "Cancel",
                    onClick = {
                        viewModel.onEvent(ExtensionsScreenEvent.OnHideRepositoryDialog)
                    },
                    modifier = Modifier.weight(1f)
                )
                MyButton(
                    text = "Save",
                    onClick = {
                        viewModel.onEvent(ExtensionsScreenEvent.OnUpdateRepositoryUrl(tempUrl))
                    },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    )
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
                            text = " ",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.padding(start = 4.dp)
                        )
                    }
                    if (extension.isInstalled) {
                        Text(
                            text = " ",
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
            Text(
                text = "Language: ${getLanguageDisplayName(extension.language)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            val versionText = if (extension.isUpdateAvailable) {
                "v${extension.version} < v${extension.remoteVersion}"
            } else {
                "v${extension.version}"
            }
            Timber.d("Extension UI: ${extension.name}, version: $versionText, isUpdateAvailable: ${extension.isUpdateAvailable}, remoteVersion: ${extension.remoteVersion}")
            Text(
                text = versionText,
                style = MaterialTheme.typography.bodySmall,
                color = if (extension.isUpdateAvailable)
                    MaterialTheme.colorScheme.tertiary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
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
            when {
                extension.isInstalling -> {
                    MyButton(
                        text = "Installing...",
                        onClick = {},
                        enabled = false,
                        modifier = Modifier.height(40.dp),
                        backgroundColor = MaterialTheme.colorScheme.surface,
                        borderColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                    )
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
                        modifier = Modifier.height(40.dp)
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