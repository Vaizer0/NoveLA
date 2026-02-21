package my.noveldokusha.scraper.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.templates.WtrLabScraperTemplate

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
class WtrLabSource(
    networkClient: NetworkClient,
    appPreferences: AppPreferences
) : WtrLabScraperTemplate(networkClient, appPreferences), SourceInterface.Configurable {

    private data class ModeItem(val code: String, val nameResId: Int, val icon: ImageVector)

    private val modes = listOf(
        ModeItem("ai",  R.string.wtr_lab_mode_ai,  Icons.Outlined.AutoAwesome),
        ModeItem("raw", R.string.wtr_lab_mode_raw, Icons.Outlined.Code),
    )

    private val languages = listOf(
        "none" to R.string.wtr_lab_lang_none,
        "en"   to R.string.wtr_lab_lang_en,
        "es"   to R.string.wtr_lab_lang_es,
        "ru"   to R.string.wtr_lab_lang_ru,
        "de"   to R.string.wtr_lab_lang_de,
        "id"   to R.string.wtr_lab_lang_id,
        "tr"   to R.string.wtr_lab_lang_tr,
        "pl"   to R.string.wtr_lab_lang_pl,
        "it"   to R.string.wtr_lab_lang_it,
        "fr"   to R.string.wtr_lab_lang_fr,
    )

    @Composable
    override fun ScreenConfig() {
        val currentMode by appPreferences.WTR_LAB_MODE.flow()
            .collectAsState(initial = appPreferences.WTR_LAB_MODE.value)
        val currentLang by appPreferences.WTR_LAB_LANGUAGE.flow()
            .collectAsState(initial = appPreferences.WTR_LAB_LANGUAGE.value)

        var langMenuExpanded by remember { mutableStateOf(false) }

        val currentLangName = languages
            .find { it.first == currentLang }
            ?.second
            ?.let { stringResource(it) }
            ?: currentLang

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── Режим ────────────────────────────────────────────────────────
            Text(
                text = stringResource(R.string.wtr_lab_mode),
                style = MaterialTheme.typography.titleSmall,
                color = ColorAccent,
            )
            Spacer(Modifier.height(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(androidx.compose.foundation.layout.IntrinsicSize.Max)
            ) {
                modes.forEach { mode ->
                    val selected = currentMode == mode.code
                    Surface(
                        onClick = { appPreferences.WTR_LAB_MODE.value = mode.code },
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) ColorAccent.copy(alpha = 0.12f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        border = BorderStroke(
                            width = if (selected) 1.5.dp else 1.dp,
                            color = if (selected) ColorAccent
                            else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                        ),
                        modifier = Modifier.weight(1f).fillMaxHeight()
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp, horizontal = 12.dp)
                        ) {
                            Icon(
                                imageVector = mode.icon,
                                contentDescription = null,
                                tint = if (selected) ColorAccent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = stringResource(mode.nameResId),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                color = if (selected) ColorAccent
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            // ── Язык — выпадающее меню ────────────────────────────────────────
            Text(
                text = stringResource(R.string.wtr_lab_language),
                style = MaterialTheme.typography.titleSmall,
                color = ColorAccent,
            )
            Spacer(Modifier.height(10.dp))
            Box {
                OutlinedButton(
                    onClick = { langMenuExpanded = true },
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(
                        width = 1.5.dp,
                        color = ColorAccent.copy(alpha = 0.6f)
                    ),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface,
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = null,
                        tint = ColorAccent,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = currentLangName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f)
                    )
                }
                DropdownMenu(
                    expanded = langMenuExpanded,
                    onDismissRequest = { langMenuExpanded = false },
                ) {
                    languages.forEach { (code, nameResId) ->
                        val selected = currentLang == code
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(nameResId),
                                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                    color = if (selected) ColorAccent
                                    else MaterialTheme.colorScheme.onSurface,
                                )
                            },
                            trailingIcon = if (selected) ({
                                Icon(
                                    imageVector = Icons.Filled.Check,
                                    contentDescription = null,
                                    tint = ColorAccent,
                                )
                            }) else null,
                            onClick = {
                                appPreferences.WTR_LAB_LANGUAGE.value = code
                                langMenuExpanded = false
                            },
                        )
                    }
                }
            }
        }
    }
}