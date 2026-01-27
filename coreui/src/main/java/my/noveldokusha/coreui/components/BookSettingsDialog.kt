package my.noveldokusha.coreui.components

import android.os.Parcelable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.parcelize.Parcelize
import my.noveldokusha.coreui.R
import my.noveldokusha.coreui.theme.ImageBorderShape
import my.noveldokusha.core.rememberResolvedBookImagePath
import my.noveldokusha.feature.local_database.tables.Book


sealed interface BookSettingsDialogState : Parcelable {
    @Parcelize
    data object Hide : BookSettingsDialogState

    @Parcelize
    data class Show(val book: Book) :
        BookSettingsDialogState
}

@Composable
fun BookSettingsDialog(
    book: Book,
    categories: List<String>,
    onDismiss: () -> Unit,
    onCategorySelected: (String) -> Unit,
    onDeleteNovel: () -> Unit = {},
    onMarkAllChaptersRead: () -> Unit = {},
    onMarkAllChaptersUnread: () -> Unit = {},
) {
    val buttonTextSize = 12.sp
    val buttonShape = RoundedCornerShape(8.dp)

    var dropdownExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            ImageView(
                imageModel = rememberResolvedBookImagePath(
                    bookUrl = book.url,
                    imagePath = book.coverImageUrl
                ),
                error = R.drawable.default_book_cover,
                modifier = Modifier
                    .width(96.dp)
                    .aspectRatio(1 / 1.45f)
                    .clip(ImageBorderShape)
            )
        },
        title = {
            Text(
                text = book.title,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {},
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // Category dropdown section
                Text(
                    text = stringResource(R.string.move_to),
                    fontSize = buttonTextSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                // Centered dropdown button
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    FilledTonalButton(
                        onClick = { dropdownExpanded = true },
                        shape = buttonShape,
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                        ),
                        modifier = Modifier.fillMaxWidth(0.8f) // 80% width for better proportions
                    ) {
                        val currentCategoryDisplay = when (book.category) {
                            "" -> stringResource(R.string.reading)
                            "Completed" -> stringResource(R.string.completed)
                            else -> book.category
                        }
                        Text(
                            text = currentCategoryDisplay,
                            fontSize = buttonTextSize,
                            textAlign = TextAlign.Center
                        )
                    }

                    DropdownMenu(
                        expanded = dropdownExpanded,
                        onDismissRequest = { dropdownExpanded = false }
                    ) {
                        categories.forEach { category ->
                            val displayName = when (category) {
                                "" -> stringResource(R.string.reading)
                                "Completed" -> stringResource(R.string.completed)
                                else -> category
                            }
                            val isSelected = book.category == category

                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = displayName,
                                            fontSize = buttonTextSize,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    }
                                },
                                onClick = {
                                    onCategorySelected(category)
                                    dropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Mark all chapters as read
                FilledTonalButton(
                    onClick = {
                        onMarkAllChaptersRead()
                        onDismiss()
                    },
                    shape = buttonShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.mark_all_chapters_read),
                        fontSize = buttonTextSize,
                        textAlign = TextAlign.Center
                    )
                }

                // Mark all chapters as unread
                FilledTonalButton(
                    onClick = {
                        onMarkAllChaptersUnread()
                        onDismiss()
                    },
                    shape = buttonShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.mark_all_chapters_unread),
                        fontSize = buttonTextSize,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Delete novel
                FilledTonalButton(
                    onClick = {
                        onDeleteNovel()
                        onDismiss()
                    },
                    shape = buttonShape,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = stringResource(R.string.delete_novel),
                        fontSize = buttonTextSize,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    )

}
