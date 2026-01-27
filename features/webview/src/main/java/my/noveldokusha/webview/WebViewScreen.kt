package my.noveldokusha.webview

import android.content.Context
import android.view.View
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : View> WebViewScreen(
    toolbarTitle: String,
    isReady: Boolean,
    onDoneClicked: () -> Unit,
    webViewFactory: (Context) -> T,
    onBackClicked: () -> Unit,
    onReloadClicked: () -> Unit,
    onClearCookiesClicked: () -> Unit,
    onCopyUrlClicked: () -> Unit
) {
    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Unspecified,
                    ),
                    title = {
                        Text(
                            text = toolbarTitle,
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBackClicked) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    },
                    actions = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (isReady) {
                                Button(
                                    onClick = onDoneClicked,
                                    modifier = Modifier
                                        .padding(end = 8.dp)
                                        .height(36.dp),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.primary
                                    )
                                ) {
                                    Text(
                                        text = "DONE",
                                        style = MaterialTheme.typography.labelLarge.copy(
                                            fontWeight = FontWeight.ExtraBold
                                        )
                                    )
                                }
                            }

                            // Иконки управления (без изменений)
                            IconButton(onClick = onCopyUrlClicked) {
                                Icon(Icons.Default.ContentCopy, "Copy")
                            }

                            IconButton(onClick = onReloadClicked) {
                                Icon(Icons.Default.Refresh, "Reload")
                            }

                            IconButton(onClick = onClearCookiesClicked) {
                                Icon(Icons.Default.Delete, "Clear")
                            }
                        }
                    }
                )
            }
        },
        content = { padding ->
            AndroidView(
                modifier = Modifier.padding(padding),
                factory = webViewFactory
            )
        }
    )
}