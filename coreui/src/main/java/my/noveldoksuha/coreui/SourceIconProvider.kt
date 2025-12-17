package my.noveldoksuha.coreui

import androidx.compose.runtime.Composable
import my.noveldokusha.core.SourceType
import my.noveldokusha.core.rememberResolvedBookImagePath

@Composable
fun SourceType.getPainter(): Any? {
    val iconUrl = getIconUrlValue() ?: return null
    return rememberResolvedBookImagePath(
        bookUrl = "", // не важно для иконок
        imagePath = iconUrl
    )
}
