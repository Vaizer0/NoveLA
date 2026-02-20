package my.noveldokusha.scraper.sources

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.templates.WtrLabScraperTemplate
import my.noveldokusha.coreui.theme.ColorAccent

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
class WtrLabSource(
    networkClient: NetworkClient,
    appPreferences: AppPreferences
) : WtrLabScraperTemplate(networkClient, appPreferences), SourceInterface.Configurable {

    private val modes = listOf(
        "ai"  to R.string.wtr_lab_mode_ai,
        "raw" to R.string.wtr_lab_mode_raw,
    )

    // "none" = без перевода через Google Translate
    private val languages = listOf(
        "none" to R.string.wtr_lab_lang_none,
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // ── Режим ─────────────────────────────────────────────────────────
            Text(
                text  = stringResource(R.string.wtr_lab_mode),
                style = MaterialTheme.typography.titleSmall,
                color = ColorAccent,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                modes.forEachIndexed { index, (code, nameResId) ->
                    SegmentedButton(
                        selected = currentMode == code,
                        onClick  = { appPreferences.WTR_LAB_MODE.value = code },
                        shape    = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = modes.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = ColorAccent.copy(alpha = 0.15f),
                            activeContentColor   = ColorAccent,
                            activeBorderColor    = ColorAccent,
                            inactiveBorderColor  = MaterialTheme.colorScheme.outline,
                        ),
                    ) {
                        Text(stringResource(nameResId))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))

            // ── Язык Google Translate ──────────────────────────────────────────
            Text(
                text  = stringResource(R.string.wtr_lab_language),
                style = MaterialTheme.typography.titleSmall,
                color = ColorAccent,
            )
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                languages.forEach { (code, nameResId) ->
                    FilterChip(
                        selected = currentLang == code,
                        onClick  = { appPreferences.WTR_LAB_LANGUAGE.value = code },
                        label    = { Text(stringResource(nameResId)) },
                    )
                }
            }
        }
    }
}