package my.noveldokusha.features.reader.video

/**
 * Keeps the exporter frame loop in Long space while the renderer API remains Int-based.
 * The exporter bounds the frame count from the positive timeline duration, so the
 * resulting frame index is safely representable by the renderer's Int API.
 */
internal fun CinematicFrameRenderer.frameAt(frameIndex: Long) = frameAt(
    frameIndex.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
)
