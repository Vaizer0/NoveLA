# Task 6: Update Notification with Seek Bar

## Context
This is Task 6 of 8 for the TTS Reader Playback Improvements feature. You are working in the NoveLA Android reader app on the `feat/tts-playback-improvements` branch.

Tasks 1-5 created the seek bar component and integrated it into the UI. This task updates the notification/lock screen controls.

## Goal
Add seek bar to notification, sync position/duration with in-app values, and handle seek from lock screen.

## Files to Modify
- `features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsNotification.kt`
- `features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsCallback.kt`

## Requirements
1. Update `refreshMediaSessionMetadata()` to report actual duration (not -1)
2. Update `PlaybackState` to include actual position and playback speed
3. Add `onSeekTo` handler in `NarratorMediaControlsCallback`
4. Update notification text to show elapsed/total or elapsed/remaining
5. All numbers must match in-app values

## Implementation Steps

### Step 1: Update NarratorMediaControlsCallback.kt

Add `onSeekTo` handler:

```kotlin
override fun onSeekTo(pos: Long) {
    val seconds = (pos / 1000).toInt()
    readerTextToSpeech.seekToPosition(seconds)
}
```

### Step 2: Update NarratorMediaControlsNotification.kt

#### 2a: Update refreshMediaSessionMetadata

Replace the existing method to use actual duration:

```kotlin
private fun refreshMediaSessionMetadata() {
    val durationMs = (readerManager.session?.readerTextToSpeech?.chapterTotalSeconds?.value?.toLong() ?: -1L) * 1000
    val builder = MediaMetadataCompat.Builder()
        .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, durationMs)
    currentChapterTitle?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, it) }
    currentBookTitle?.let { builder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it) }
    currentCoverBitmap?.let {
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it)
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
        builder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
    }
    mediaSession?.setMetadata(builder.build())
}
```

#### 2b: Update PlaybackState with position

In the scope.launch for isPlaying updates, add position tracking:

```kotlin
scope.launch {
    snapshotFlow { readerSession.readerTextToSpeech.state.isPlaying.value }
        .collectLatest { isPlaying ->
            notificationsCenter.modifyNotification(
                builder = notificationBuilder,
                notificationId = notificationId,
            ) {
                defineActions(isPlaying = isPlaying)
            }
            val playbackState = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
            val position = readerSession.readerTextToSpeech.chapterElapsedSeconds.value.toLong() * 1000
            val stateBuilder = PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_STOP or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_REWIND or
                    PlaybackStateCompat.ACTION_FAST_FORWARD or
                    PlaybackStateCompat.ACTION_SEEK_TO
                )
                .setState(playbackState, position, if (isPlaying) 1.0f else 0.0f)
                .setActivePlaybackSpeed(readerSession.readerTextToSpeech.state.voiceSpeed.value)
            this@NarratorMediaControlsNotification.mediaSession?.setPlaybackState(stateBuilder.build())
        }
}
```

#### 2c: Update notification text to show progress

In the scope.launch for progress updates, replace the text formatting:

```kotlin
scope.launch {
    combine(
        snapshotFlow { readerSession.speakerStats.value },
        snapshotFlow { readerSession.readerTextToSpeech.state.estimatedRemainingSeconds.value },
        snapshotFlow { readerSession.readerTextToSpeech.chapterElapsedSeconds.value },
        snapshotFlow { readerSession.readerTextToSpeech.chapterTotalSeconds.value },
    ) { stats, _, elapsed, total -> Triple(stats, elapsed, total) }
        .collectLatest { triple ->
            val stats = triple.first ?: return@collectLatest
            val elapsed = triple.second
            val total = triple.third
            val chapterPos = context.getString(
                R.string.chapter_x_over_n,
                stats.chapterIndex + 1,
                stats.chapterCount
            )
            val progressText = if (total > 0) {
                "${formatDuration(elapsed)} / ${formatDuration(total)}"
            } else {
                context.getString(R.string.progress_x_percentage, stats.chapterReadPercentage())
            }
            notificationsCenter.modifyNotification(
                builder = notificationBuilder,
                notificationId = notificationId,
            ) {
                text = "$chapterPos  $progressText"
            }
        }
}
```

## Verification
- File should compile without errors
- Notification should show seek bar with position
- Lock screen should show same progress as in-app
- Seek from lock screen should work

## Commit
```bash
git add features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsNotification.kt
git add features/reader/src/main/java/my/noveldokusha/features/reader/services/NarratorMediaControlsCallback.kt
git commit -m "feat(tts): add seek bar and position sync to notification"
```
