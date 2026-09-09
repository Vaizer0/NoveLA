from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
more = ROOT / "features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/MoreSettingDialog.kt"
voice = ROOT / "features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt"
bar = ROOT / "features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreenBottomBarDialogs.kt"

# The previous implementation may already have removed the misplaced row.
# Either state is accepted, but the final VoiceReader settings must never own it.
voice_text = voice.read_text()
if "tts_chapter_duration" in voice_text or "ttsDurationEnabled" in voice_text:
    raise SystemExit("TTS duration toggle is still inside VoiceReaderSettingDialog; abort rather than applying a broad edit")

more_text = more.read_text()
if "ttsDurationEnabled: Boolean" not in more_text:
    sig = "internal fun MoreSettingDialog(\n"
    if sig not in more_text:
        raise SystemExit("Could not find MoreSettingDialog signature")
    more_text = more_text.replace(
        sig,
        sig + "    ttsDurationEnabled: Boolean = false,\n    onTtsDurationEnabledChange: (Boolean) -> Unit = {},\n",
        1,
    )

if "// TTS chapter duration" not in more_text:
    marker = "        // Manual highlight\n"
    if marker not in more_text:
        raise SystemExit("Could not find Manual highlight marker")
    duration_block = '''        // TTS chapter duration
        SlimListItem(
            modifier = Modifier
                .clickable { onTtsDurationEnabledChange(!ttsDurationEnabled) },
            headlineContent = {
                Text(text = stringResource(id = R.string.tts_chapter_duration))
            },
            leadingContent = {
                Icon(
                    Icons.Outlined.AccessTime,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingContent = {
                Switch(
                    checked = ttsDurationEnabled,
                    onCheckedChange = onTtsDurationEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colorAccent(),
                        checkedTrackColor = colorAccent().copy(alpha = 0.4f),
                    )
                )
            }
        )

'''
    more_text = more_text.replace(marker, duration_block + marker, 1)

if "import androidx.compose.material.icons.outlined.AccessTime" not in more_text:
    anchor = "import androidx.compose.material.icons.outlined.Highlight\n"
    if anchor not in more_text:
        raise SystemExit("Could not find MoreSettingDialog icon import anchor")
    more_text = more_text.replace(
        anchor,
        "import androidx.compose.material.icons.outlined.AccessTime\n" + anchor,
        1,
    )
more.write_text(more_text)

bar_text = bar.read_text()
if "ttsDurationEnabled = settings.textToSpeech.ttsDurationEnabled.value" not in bar_text:
    needle = "                    ReaderScreenState.Settings.Type.More -> MoreSettingDialog(\n"
    if needle not in bar_text:
        raise SystemExit("Could not find MoreSettingDialog call")
    bar_text = bar_text.replace(
        needle,
        needle
        + "                        ttsDurationEnabled = settings.textToSpeech.ttsDurationEnabled.value,\n"
        + "                        onTtsDurationEnabledChange = settings.textToSpeech.setTtsDurationEnabled,\n",
        1,
    )
bar.write_text(bar_text)

print("TTS duration UI is owned by MoreSettingDialog, immediately above Manual Highlight, and wired to the existing persisted setter.")
