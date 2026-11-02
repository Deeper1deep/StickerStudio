package com.stickerstudio.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.stickerstudio.app.R

/** Where a friend can download the app. A pack itself is sent as a .wastickers file. */
const val APP_DOWNLOAD_URL = "https://github.com/Deeper1deep/StickerStudio-Updates/releases/latest"

/**
 * QR code with the app icon in the centre. Error correction level H lets the code survive the
 * logo covering its middle (about 20% of the side).
 */
fun buildQrWithLogo(context: Context, text: String, size: Int = 768): Bitmap {
    val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.H, EncodeHintType.MARGIN to 1)
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
    val pixels = IntArray(size * size)
    for (y in 0 until size) for (x in 0 until size) pixels[y * size + x] = if (matrix[x, y]) Color.BLACK else Color.WHITE
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)   // mutable, so the logo can be drawn on it
    bmp.setPixels(pixels, 0, size, 0, 0, size, size)
    val canvas = Canvas(bmp)
    val box = size * 0.22f
    val c = size / 2f
    val pad = box * 0.12f
    val rect = RectF(c - box / 2 - pad, c - box / 2 - pad, c + box / 2 + pad, c + box / 2 + pad)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    canvas.drawRoundRect(rect, box * 0.25f, box * 0.25f, paint)
    try {
        val icon = context.packageManager.getApplicationIcon(context.packageName).toBitmap(box.toInt(), box.toInt())
        canvas.drawBitmap(icon, c - box / 2, c - box / 2, Paint(Paint.FILTER_BITMAP_FLAG))
    } catch (_: Exception) {
        // QR still works without the logo
    }
    return bmp
}

/** Dialog showing the QR; [onSendFile] is non-null when a pack can also be sent as a file. */
@Composable
fun ShareQrDialog(onDismiss: () -> Unit, onSendFile: (() -> Unit)? = null) {
    val context = LocalContext.current
    // Either the finished picture or the error text, so a failure is visible instead of an endless spinner.
    val qr by produceState<Any?>(null) {
        value = withContext(Dispatchers.Default) {
            try {
                buildQrWithLogo(context, APP_DOWNLOAD_URL).asImageBitmap()
            } catch (e: Throwable) {
                "${e.javaClass.simpleName}: ${e.message}"
            }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.share_title)) },
        text = {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                val img = qr
                when (img) {
                    is androidx.compose.ui.graphics.ImageBitmap -> Image(img, null, Modifier.size(240.dp))
                    is String -> {
                        Text(img, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        Text(APP_DOWNLOAD_URL, style = MaterialTheme.typography.bodySmall)
                    }
                    else -> Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
                Text(stringResource(R.string.share_qr_hint), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                if (onSendFile != null) {
                    Button(onClick = onSendFile, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.send_pack_file)) }
                    Text(
                        stringResource(R.string.send_pack_note), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) } },
    )
}
