from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]

more = ROOT / "features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/MoreSettingDialog.kt"
voice = ROOT / "features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt"
bar = ROOT / "features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreenBottomBarDialogs.kt"

# Move the duration switch out of the voice-reader controls. Require exactly one
# match so a future source-layout change fails loudly instead of corrupting UI.
voice_text = voice.read_text()
row = re.compile(
    r'\n\s*Row\(\n'
    r'\s*horizontalArrangement = Arrangement\.spacedBy\(8\.dp\),\n'
    r'\s*verticalAlignment = Alignment\.CenterVertically,\n'
    r'\s*modifier = Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 8\.dp\),\n'
    r'\s*\) \{\n'
    r'\s*Text\(\n'
    r'\s*text = stringResource\(R\.string\.tts_chapter_duration\),\n'
    r'.*?\n\s*\}\n'
    r'\s*}\n\n(?=\s*Row\(\n\s*horizontalArrangement = Arrangement\.Center\b)',
    re.S,
)
new_voice, count = row.subn("\n", voice_text)
if count != 1:
    # Fall back to the exact block used by the duration implementation.
    exact = re.compile(
        r'\n\s*Row\(\n\s*horizontalArrangement = Arrangement\.spacedBy\(8\.dp\),\n'
        r'\s*verticalAlignment = Alignment\.CenterVertically,\n\s*modifier = Modifier\.fillMaxWidth\(\)\.padding\(horizontal = 8\.dp\),\n\s*\) \{\n'
        r'\s*Text\(\n\s*text = stringResource\(R\.string\.tts_chapter_duration\),\n'
        r'.*?\n\s*}\n\s*}\n', re.S)
    new_voice, count = exact.subn("\n", voice_text)
if count != 1:
    raise SystemExit(f"Expected exactly one misplaced duration row in {voice}, found {count}")
voice.write_text(new_voice)

# Add the duration toggle to MoreSettingDialog immediately before Manual Highlight.
more_text = more.read_text()
if "ttsDurationEnabled: Boolean" not in more_text:
    sig = "internal fun MoreSettingDialog(\n"
    if sig not in more_text:
        raise SystemExit("Could not find MoreSettingDialog signature")
    more_text = more_text.replace(sig, sig + "    ttsDurationEnabled: Boolean = false,\n    onTtsDurationEnabledChange: (Boolean) -> Unit = {},\n", 1)

marker = "        // Manual highlight\n"
duration_block = '''        // TTS chapter duration\n        SlimListItem(\n            modifier = Modifier\n                .clickable { onTtsDurationEnabledChange(!ttsDurationEnabled) },\n            headlineContent = {\n                Text(text = stringResource(id = R.string.tts_chapter_duration))\n            },\n            leadingContent = {\n                Icon(\n                    Icons.Outlined.AccessTime,\n                    null,\n                    tint = MaterialTheme.colorScheme.onSurfaceVariant\n                )\n            },\n            trailingContent = {\n                Switch(\n                    checked = ttsDurationEnabled,\n                    onCheckedChange = onTtsDurationEnabledChange,\n                    colors = SwitchDefaults.colors(\n                        checkedThumbColor = colorAccent(),\n                        checkedTrackColor = colorAccent().copy(alpha = 0.4f),\n                    )\n                )\n            }\n        )\n\n'''
if "// TTS chapter duration" not in more_text:
    if marker not in more_text:
        raise SystemExit("Could not find Manual highlight marker")
    more_text = more_text.replace(marker, duration_block + marker, 1)

if "import androidx.compose.material.icons.outlined.AccessTime" not in more_text:
    anchor = "import androidx.compose.material.icons.outlined.Highlight\n"
    if anchor not in more_text:
        raise SystemExit("Could not find icon import anchor")
    more_text = more_text.replace(anchor, "import androidx.compose.material.icons.outlined.AccessTime\n" + anchor, 1)
more.write_text(more_text)

# Wire the More menu directly to the existing persisted TTS-duration setter.
bar_text = bar.read_text()
if "ttsDurationEnabled = settings.textToSpeech.ttsDurationEnabled.value" not in bar_text:
    needle = "                    ReaderScreenState.Settings.Type.More -> MoreSettingDialog(\n"
    if needle not in bar_text:
        raise SystemExit("Could not find MoreSettingDialog call")
    replacement = needle + "                        ttsDurationEnabled = settings.textToSpeech.ttsDurationEnabled.value,\n                        onTtsDurationEnabledChange = settings.textToSpeech.setTtsDurationEnabled,\n"
    bar_text = bar_text.replace(needle, replacement, 1)
bar.write_text(bar_text)

print("TTS duration toggle moved to More menu above Manual Highlight and wired to existing state setter.")
