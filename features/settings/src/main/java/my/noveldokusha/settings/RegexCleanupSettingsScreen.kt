package my.noveldokusha.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegexCleanupSettingsScreen(
    viewModel: RegexCleanupSettingsViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState
    val editingRule by viewModel.editingRule
    val validationError by viewModel.validationError

    var showDeleteConfirmation by remember { mutableStateOf<String?>(null) }
    val showEditingScreen = editingRule != null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (showEditingScreen) stringResource(id = R.string.edit_rule) else stringResource(id = R.string.regex_cleanup),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (showEditingScreen) viewModel.finishEditingRule() else onNavigateBack()
                    }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = if (showEditingScreen) stringResource(id = R.string.cancel_editing) else stringResource(id = R.string.go_back),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (showEditingScreen) {
                RegexRuleEditScreen(
                    rule = editingRule,
                    onSave = { rule ->
                        val isNew = editingRule?.pattern.isNullOrEmpty() && editingRule?.replacement.isNullOrEmpty()
                        if (isNew) viewModel.addRule(rule.pattern, rule.replacement)
                        else viewModel.updateRule(editingRule!!.pattern, rule.pattern, rule.replacement)
                        viewModel.finishEditingRule()
                    },
                    onCancel = { viewModel.finishEditingRule() },
                    validationError = validationError,
                    onClearError = { viewModel.clearValidationError() },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Column(modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                ) {
                    Text(
                        text = stringResource(id = R.string.manage_regex_rules_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    if (uiState.rules.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(id = R.string.no_regex_rules_yet),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(uiState.rules) { index, rule ->
                                RegexRuleItem(
                                    rule = rule,
                                    onEdit = { viewModel.startEditingRule(it) },
                                    onDelete = { showDeleteConfirmation = it.pattern },
                                    onMoveUp = { if (index > 0) viewModel.moveRule(index, index - 1) },
                                    onMoveDown = { if (index < uiState.rules.size - 1) viewModel.moveRule(index, index + 1) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = { viewModel.startEditingRule(null) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        )
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(id = R.string.add_new_rule))
                    }
                }
            }
        }
    }

    if (showDeleteConfirmation != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = null },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteRule(showDeleteConfirmation!!)
                        showDeleteConfirmation = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                ) { Text(stringResource(id = R.string.delete)) }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteConfirmation = null },
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) { Text(stringResource(id = R.string.cancel)) }
            },
            title = { Text(stringResource(id = R.string.delete_rule), color = MaterialTheme.colorScheme.onSurface) },
            text = { Text(stringResource(id = R.string.delete_rule_confirmation, showDeleteConfirmation!!), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        )
    }
}
