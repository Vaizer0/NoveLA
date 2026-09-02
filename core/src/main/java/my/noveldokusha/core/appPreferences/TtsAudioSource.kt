package my.noveldokusha.core.appPreferences

/**
 * Какой текст главы синтезировать в аудиофайл для загрузки:
 *  - [ORIGINAL] — исходный текст главы (тело из кэша);
 *  - [TRANSLATED] — переведённый текст (только из кэша перевода, см. [READER_TEXT_TO_SPEECH]);
 *  - [ASK_EVERY_TIME] — спрашивать пользователя при каждом запуске загрузки.
 *
 * Это отдельный источник АУДИО-ЗАГРУЗКИ и не влияет на режимы озвучки читалки.
 */
enum class TtsAudioSource {
    ORIGINAL,
    TRANSLATED,
    ASK_EVERY_TIME,
}
