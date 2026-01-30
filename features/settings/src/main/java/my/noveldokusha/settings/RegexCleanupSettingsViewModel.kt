package my.noveldokusha.settings

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import my.noveldokusha.core.models.RegexRule
import my.noveldokusha.core.appPreferences.AppPreferences
import javax.inject.Inject

@HiltViewModel
class RegexCleanupSettingsViewModel @Inject constructor(
    private val appPreferences: AppPreferences
) : ViewModel() {

    val uiState = mutableStateOf(RegexCleanupUiState())

    private val _editingRule = mutableStateOf<RegexRule?>(null)
    val editingRule = _editingRule

    private val _validationError = mutableStateOf<String?>(null)
    val validationError = _validationError

    init {
        loadRules()
    }

    private fun loadRules() {
        viewModelScope.launch {
            val rules = appPreferences.USER_REGEX_CLEANUP_RULES.value
            uiState.value = uiState.value.copy(rules = rules.toMutableStateList())
        }
    }

    fun addRule(pattern: String, replacement: String) {
        if (!validateRegex(pattern)) {
            _validationError.value = "Invalid regex pattern: $pattern"
            return
        }

        _validationError.value = null

        viewModelScope.launch {
            val currentRules = appPreferences.USER_REGEX_CLEANUP_RULES.value.toMutableList()
            currentRules.add(RegexRule(pattern, replacement))
            appPreferences.USER_REGEX_CLEANUP_RULES.value = currentRules

            loadRules()
        }
    }

    fun updateRule(oldPattern: String, newPattern: String, replacement: String) {
        if (!validateRegex(newPattern)) {
            _validationError.value = "Invalid regex pattern: $newPattern"
            return
        }

        _validationError.value = null

        viewModelScope.launch {
            val currentRules = appPreferences.USER_REGEX_CLEANUP_RULES.value.toMutableList()
            val index = currentRules.indexOfFirst { it.pattern == oldPattern }
            if (index != -1) {
                currentRules[index] = RegexRule(newPattern, replacement)
                appPreferences.USER_REGEX_CLEANUP_RULES.value = currentRules
            }

            loadRules()
        }
    }

    fun deleteRule(pattern: String) {
        viewModelScope.launch {
            val currentRules = appPreferences.USER_REGEX_CLEANUP_RULES.value.toMutableList()
            currentRules.removeAll { it.pattern == pattern }
            appPreferences.USER_REGEX_CLEANUP_RULES.value = currentRules

            loadRules()
        }
    }

    fun moveRule(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val currentRules = appPreferences.USER_REGEX_CLEANUP_RULES.value.toMutableList()
            if (fromIndex in currentRules.indices && toIndex in currentRules.indices) {
                val item = currentRules.removeAt(fromIndex)
                currentRules.add(toIndex, item)
                appPreferences.USER_REGEX_CLEANUP_RULES.value = currentRules
            }

            loadRules()
        }
    }

    fun startEditingRule(rule: RegexRule?) {
        // Если rule == null, значит создаем новое правило, используем пустое RegexRule
        _editingRule.value = rule ?: RegexRule("", "")
        _validationError.value = null
    }

    fun finishEditingRule() {
        _editingRule.value = null
    }

    fun clearValidationError() {
        _validationError.value = null
    }

    private fun validateRegex(pattern: String): Boolean {
        return try {
            Regex(pattern)
            true
        } catch (e: Exception) {
            false
        }
    }
}

data class RegexCleanupUiState(
    val rules: MutableList<RegexRule> = mutableListOf()
)

fun <T> List<T>.toMutableStateList(): MutableList<T> {
    val mutableList = mutableStateListOf<T>()
    mutableList.addAll(this)
    return mutableList
}
