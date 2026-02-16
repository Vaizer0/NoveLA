package my.noveldokusha.scraper.sources

import my.noveldokusha.core.LanguageCode
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.templates.WtrLabScraperTemplate

/**
 * WTR-Lab scraper for English translation
 */
class WtrLabEn(networkClient: NetworkClient) : WtrLabScraperTemplate(networkClient) {

    override val id = "wtrlab_en"
    override val baseUrl = "https://wtr-lab.com/"
    override val nameStrId = R.string.source_name_wtrlab_en
    override val language = LanguageCode.ENGLISH
    override val translationLanguage = "en"
}
