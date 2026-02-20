package my.noveldokusha.webview

import android.content.ClipboardManager
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun <T : View> WebViewScreen(
    toolbarTitle: String,
    isReady: Boolean,
    onDoneClicked: () -> Unit,
    onNavigateToUrl: (String) -> Unit,
    webViewFactory: (Context) -> T,
    onBackClicked: () -> Unit,
    onReloadClicked: () -> Unit,
    onClearCookiesClicked: () -> Unit,
    onCopyUrlClicked: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current

    // Локальный стейт для редактирования URL
    var editingUrl by remember(toolbarTitle) { mutableStateOf(toolbarTitle) }
    var isFocused by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Unspecified,
                    ),
                    title = {
                        OutlinedTextField(
                            value = editingUrl,
                            onValueChange = { editingUrl = it },
                            singleLine = true,
                            textStyle = TextStyle(fontSize = 12.sp),
                            modifier = Modifier
                                .height(48.dp)
                                .onFocusChanged { isFocused = it.isFocused },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    val url = editingUrl.trim().let {
                                        if (!it.startsWith("http")) "https://$it" else it
                                    }
                                    onNavigateToUrl(url)
                                    focusManager.clearFocus()
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = Color.Transparent,
                                focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                unfocusedContainerColor = Color.Transparent,
                            ),
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
                                        .padding(end = 4.dp)
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

                            // Вставить URL из буфера и сразу перейти
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val pastedUrl = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim() ?: return@IconButton
                                val url = if (!pastedUrl.startsWith("http")) "https://$pastedUrl" else pastedUrl
                                editingUrl = url
                                onNavigateToUrl(url)
                            }) {
                                Icon(Icons.Default.ContentPaste, "Paste URL")
                            }

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