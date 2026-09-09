from pathlib import Path

VOICE = Path("features/reader/src/main/java/my/noveldokusha/features/reader/ui/settingDialogs/VoiceReaderSettingDialog.kt")
text = VOICE.read_text(encoding="utf-8")
marker = """\n        }\n    }\n}\n\n@Composable\nprivate fun FloatingTtsToggleCard("""
insert = """\n                // Actual synthesized chapter-duration timeline.\n                // It lives in the same TTS card as the playback controls and is hidden when disabled.\n                if (state.ttsDurationEnabled.value) {\n                    TtsChapterDurationPanel(\n                        totalMs = state.chapterTtsDurationMs.value,\n                        currentMs = state.chapterTtsDurationCurrentMs.value,\n                        remainingMs = state.chapterTtsDurationRemainingMs.value,\n                        loading = state.chapterTtsDurationLoading.value,\n                        provisional = state.chapterTtsDurationProvisional.value,\n                    )\n                }\n        }\n    }\n}\n\n@Composable\nprivate fun FloatingTtsToggleCard("""
if text.count(marker) != 1:
    raise SystemExit(f"expected one insertion marker, found {text.count(marker)}")
text = text.replace(marker, insert, 1)
VOICE.write_text(text, encoding="utf-8")
print("Inserted dedicated reader duration panel.")
