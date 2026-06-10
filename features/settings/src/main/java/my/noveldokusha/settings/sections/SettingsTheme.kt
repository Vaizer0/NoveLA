package my.noveldokusha.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BrightnessMedium
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import my.noveldokusha.coreui.theme.AppTheme
import my.noveldokusha.coreui.theme.ColorAccent
import my.noveldokusha.coreui.theme.DarkMode
import my.noveldokusha.coreui.theme.textPadding
import my.noveldokusha.settings.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsTheme(
    currentAppTheme: AppTheme,
    currentDarkMode: DarkMode,
    onAppThemeChange: (AppTheme) -> Unit,
    onDarkModeChange: (DarkMode) -> Unit,
) {
    Column {
        Text(
            text = stringResource(id = R.string.theme),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.textPadding(),
            color = ColorAccent
        )

        // Mode chips (System/Light/Dark/Black) — above Color Scheme
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DarkMode.entries.forEach { mode ->
                FilterChip(
                    selected = mode == currentDarkMode,
                    onClick = { onDarkModeChange(mode) },
                    label = { Text(text = stringResource(id = mode.titleRes)) },
                    leadingIcon = {
                        Icon(
                            imageVector = when (mode) {
                                DarkMode.SYSTEM -> Icons.Outlined.BrightnessMedium
                                DarkMode.LIGHT -> Icons.Outlined.LightMode
                                DarkMode.DARK -> Icons.Outlined.DarkMode
                                DarkMode.BLACK -> Icons.Outlined.Nightlight
                            },
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // Color schemes section
        Text(
            text = "Color Scheme",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Theme preview grid
        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            AppTheme.entries.forEach { theme ->
                ThemePreviewChip(
                    theme = theme,
                    isSelected = theme == currentAppTheme,
                    onClick = { onAppThemeChange(theme) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemePreviewChip(
    theme: AppTheme,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val accentColor = when (theme) {
        AppTheme.DEFAULT -> Color(0xFF0088FF)
        AppTheme.MONET -> Color(0xFF6750A4)
        AppTheme.GREEN_APPLE -> Color(0xFF188140)
        AppTheme.LAVENDER -> Color(0xFFA177FF)
        AppTheme.MIDNIGHT_DUSK -> Color(0xFFF02475)
        AppTheme.STRAWBERRY_DAIQUIRI -> Color(0xFFED4A65)
        AppTheme.TAKO -> Color(0xFFF3B375)
        AppTheme.TEALTURQUOISE -> Color(0xFF40E0D0)
        AppTheme.TIDAL_WAVE -> Color(0xFF5ed4fc)
        AppTheme.YOTSUBA -> Color(0xFFAE3200)
        AppTheme.MONOCHROME -> Color(0xFF000000)
        AppTheme.CATPPUCCIN -> Color(0xFFCBA6F7)
        AppTheme.NORD -> Color(0xFF88C0D0)
        AppTheme.YINYANG -> Color(0xFF000000)
        AppTheme.CLOUDFLARE -> Color(0xFFF38020)
        AppTheme.COTTONCANDY -> Color(0xFFFFCBCB)
        AppTheme.DOOM -> Color(0xFFFF0000)
        AppTheme.MATRIX -> Color(0xFF00FF00)
        AppTheme.MOCHA -> Color(0xFFBF9270)
        AppTheme.SAPPHIRE -> Color(0xFF1E88E5)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .width(72.dp)
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .border(
                    width = if (isSelected) 2.dp else 0.dp,
                    color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(4.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
            Spacer(Modifier.width(2.dp))
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface)
                    .border(0.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
            )
        }

        Spacer(Modifier.height(2.dp))

        Text(
            text = stringResource(id = theme.titleRes),
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
    }
}