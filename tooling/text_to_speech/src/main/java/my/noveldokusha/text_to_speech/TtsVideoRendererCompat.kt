package my.noveldokusha.text_to_speech

import android.graphics.Canvas

/** Compatibility bridge for the native video encoder to the shared composition renderer. */
fun TtsVideoCompositionRenderer.renderFrame(
    canvas: Canvas,
    timeline: TtsVideoTimeline,
    settings: TtsVideoVisualSettings,
    snapshot: TtsVideoVisualSnapshot,
    timeUs: Long,
) {
    render(canvas, timeline, settings, snapshot, timeUs)
}
