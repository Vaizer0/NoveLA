package my.noveldokusha.coreui

import androidx.compose.runtime.Composable
import my.noveldokusha.core.rememberResolvedBookImagePath

@Composable
fun getIconPainter(url: String): Any? {
    // Для получения иконки источника нужно использовать Scraper, который недоступен в coreui
    // Этот метод будет реализован в соответствующем модуле, который имеет доступ к Scraper
    // Пока возвращаем null, реальная реализация будет в месте, где доступен Scraper
    return null
}
