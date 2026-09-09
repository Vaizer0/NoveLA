from pathlib import Path

path = Path("features/reader/src/main/java/my/noveldokusha/features/reader/ui/ReaderScreen.kt")
text = path.read_text()

anchor = '''        LaunchedEffect(\n            state.settings.floatingTts.showOutsideApp.value,\n            state.settings.floatingTts.opacity.value,\n            state.settings.ttsHighlight.isEnabled.value,\n            state.settings.ttsHighlight.highlightColor.value,\n        ) {'''

insert = '''        // Keep the already-running floating player synchronized when TTS chapter duration\n        // is toggled from the More menu. The duration state lives inside the same\n        // TextToSpeechSettingData instance, but an explicit service update/recompose here\n        // also covers a long-lived overlay service that was created before the toggle.\n        LaunchedEffect(state.settings.textToSpeech.ttsDurationEnabled.value) {\n            if (FloatingTtsService.isRunning(context)) {\n                FloatingTtsService.activityWindowToken = windowToken\n                FloatingTtsService.ttsState.value = state.settings.textToSpeech\n                FloatingTtsService.recreateOverlay()\n            }\n        }\n\n'''

if anchor not in text:
    raise SystemExit("ReaderScreen sync anchor not found")
if "Keep the already-running floating player synchronized" not in text:
    text = text.replace(anchor, insert + anchor, 1)

path.write_text(text)
print("Added explicit TTS-duration synchronization for a running FloatingTtsService.")
