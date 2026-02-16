package my.noveldokusha.core

import androidx.annotation.StringRes

/**
 * ISO 639-1 codes
 * https://en.wikipedia.org/wiki/List_of_ISO_639-1_codes
 */
enum class LanguageCode(
    @Suppress("PropertyName") val iso639_1: String,
    @StringRes val nameResId: Int
) {
    ENGLISH(iso639_1 = "en", nameResId = R.string.language_english),
    PORTUGUESE(iso639_1 = "pt", nameResId = R.string.language_portuguese),
    SPANISH(iso639_1 = "es", nameResId = R.string.language_spanish),
    FRENCH(iso639_1 = "fr", nameResId = R.string.language_french),
    INDONESIAN(iso639_1 = "id", nameResId = R.string.language_indonesian),
    CHINESE(iso639_1 = "zh", nameResId = R.string.language_chinese),
    RUSSIAN(iso639_1 = "ru", nameResId = R.string.language_russian),
    VIETNAMESE(iso639_1 = "vi", nameResId = R.string.language_vietnamese),
    GERMAN(iso639_1 = "de", nameResId = R.string.language_german),
    TURKISH(iso639_1 = "tr", nameResId = R.string.language_turkish),
    POLISH(iso639_1 = "pl", nameResId = R.string.language_polish),
    // JAPANESE(iso639_1 = "ja", nameResId = R.string.language_japanese),
    // KOREAN(iso639_1 = "ko", nameResId = R.string.language_korean)
}
