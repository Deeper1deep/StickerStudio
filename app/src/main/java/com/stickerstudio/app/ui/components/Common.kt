package com.stickerstudio.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.stickerstudio.app.R
import java.io.File
import kotlin.math.min

/** Grey/white checkerboard that shows which parts of a sticker are transparent. */
fun Modifier.checkerboard(
    cell: Dp = 12.dp,
    light: Color = Color(0xFFF1F1F1),
    dark: Color = Color(0xFFD6D6D6),
): Modifier = this.drawBehind {
    val c = cell.toPx()
    var y = 0f
    var row = 0
    while (y < size.height) {
        var x = 0f
        var col = 0
        while (x < size.width) {
            drawRect(
                color = if ((row + col) % 2 == 0) light else dark,
                topLeft = Offset(x, y),
                size = Size(min(c, size.width - x), min(c, size.height - y)),
            )
            x += c
            col++
        }
        y += c
        row++
    }
}

/**
 * A sticker file shown on a checkerboard. Grid and list thumbnails are small and static (the first
 * frame of an animated sticker): playing dozens of animations while scrolling made it stutter.
 * Pass [animate] = true where a single sticker is shown big (preview).
 */
@Composable
fun StickerThumb(file: File?, modifier: Modifier = Modifier, showChecker: Boolean = true, animate: Boolean = false) {
    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .then(if (showChecker) Modifier.checkerboard(8.dp) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        if (file != null && file.isFile) {
            val context = androidx.compose.ui.platform.LocalContext.current
            if (animate) {
                AsyncImage(
                    model = file,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                AsyncImage(
                    model = coil.request.ImageRequest.Builder(context).data(file).size(256).build(),
                    imageLoader = StaticImages.loader(context),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** An image loader without the animated-image decoders: it shows the first frame only. */
private object StaticImages {
    @Volatile private var instance: coil.ImageLoader? = null

    fun loader(context: android.content.Context): coil.ImageLoader =
        instance ?: synchronized(this) {
            instance ?: coil.ImageLoader.Builder(context.applicationContext).crossfade(false).build().also { instance = it }
        }
}

val PALETTE: List<Int> = listOf(
    0xFFFFFFFF, 0xFF000000, 0xFFF44336, 0xFFFF9800, 0xFFFFEB3B, 0xFF4CAF50,
    0xFF00BCD4, 0xFF2196F3, 0xFF3F51B5, 0xFF9C27B0, 0xFFE91E63, 0xFF795548,
    0xFF9E9E9E, 0xFFFFC1E3, 0xFFB9F6CA, 0xFF80D8FF,
).map { it.toInt() }

@Composable
fun ColorPalette(selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 2.dp, vertical = 4.dp),
    ) {
        items(PALETTE) { color ->
            val isSelected = color == selected
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(Color(color))
                    .border(
                        BorderStroke(if (isSelected) 3.dp else 1.dp, if (isSelected) MaterialTheme.colorScheme.primary else Color(0x55888888)),
                        CircleShape,
                    )
                    .clickable { onSelect(color) },
            )
        }
    }
}

@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(
        modifier = modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        IconBadge(Icons.Filled.AutoAwesome, size = 72.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** Round violet-tinted badge with an icon: the app's standard "icon chip". */
@Composable
fun IconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = MaterialTheme.colorScheme.primary,
    container: Color = MaterialTheme.colorScheme.primaryContainer,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(size * 0.34f)).background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** White card with a hairline violet-grey border (no heavy shadows). */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    val border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    if (onClick == null) {
        Card(modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    } else {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, border = border, content = content)
    }
}

/** Big screen title with an optional subtitle and a trailing action. */
@Composable
fun ScreenHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(start = if (onBack == null) 20.dp else 8.dp, end = 12.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            androidx.compose.material3.IconButton(onClick = onBack) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack,
                    androidx.compose.ui.res.stringResource(com.stickerstudio.app.R.string.back),
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (trailing != null) trailing()
    }
}

/** Small rounded status label. */
@Composable
fun StatusPill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(container).padding(horizontal = 10.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelMedium,
        color = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmText) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun MessageDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) } },
    )
}

@Composable
fun TextInputDialog(
    title: String,
    label: String,
    initial: String = "",
    singleLine: Boolean = true,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(label) },
                singleLine = singleLine,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }, enabled = value.isNotBlank()) { Text(stringResource(R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

/** Pack name + publisher dialog (used to create and to rename a pack). */
@Composable
fun PackInfoDialog(
    title: String,
    initialName: String,
    initialPublisher: String,
    onConfirm: (name: String, publisher: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var publisher by remember { mutableStateOf(initialPublisher) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(60) },
                    label = { Text(stringResource(R.string.pack_name)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = publisher, onValueChange = { publisher = it.take(60) },
                    label = { Text(stringResource(R.string.pack_publisher)) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name, publisher) }, enabled = name.isNotBlank() && publisher.isNotBlank()) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
fun LabeledRow(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        content()
    }
}

/** A modal spinner; with [progress] (finished, total) it also says how far a batch has come. */
@Composable
fun BusyDialog(progress: Pair<Int, Int>? = null) {
    androidx.compose.ui.window.Dialog(onDismissRequest = {}) {
        androidx.compose.material3.Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                androidx.compose.material3.CircularProgressIndicator()
                if (progress != null) {
                    Text(
                        androidx.compose.ui.res.stringResource(com.stickerstudio.app.R.string.import_progress, minOf(progress.first + 1, progress.second), progress.second),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
