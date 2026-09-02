package my.noveldokusha.settings

enum class SettingsDestination(val title: String, val summary: String) {
    HOME("Settings", "Choose a category"),
    GENERAL("General", "Language and library behavior"),
    APPEARANCE("Appearance", "Theme and display appearance"),
    TRANSLATION("Translation", "Providers, models, prompts and limits"),
    TEXT_CLEANUP("Text Cleanup", "Regex cleanup rules for imported text"),
    AUDIO_DOWNLOADS("Audio Downloads", "Voice, speed, source and audio folder"),
    VIDEO_DOWNLOADS("Video Downloads", "Preview, appearance, slideshow and output"),
    NETWORK("Network", "Scraper, Cloudflare and request delays"),
    BACKUP_DATA("Backup & Data", "Backup, restore and cache cleanup"),
    LIBRARY("Library", "Automatic library update behavior"),
    APP_UPDATES("App Updates", "Version checks and update settings"),
}
