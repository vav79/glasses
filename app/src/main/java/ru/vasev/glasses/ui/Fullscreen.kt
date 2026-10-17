package ru.vasev.glasses.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ru.vasev.glasses.R

/** Снимок на весь экран; нажатие в любом месте или «Закрыть» — назад. */
@Composable
fun FullscreenPhoto(bitmap: Bitmap, label: String?, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClickLabel = stringResource(R.string.close_photo), onClick = onClose),
        ) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = label?.let { stringResource(R.string.photo_full_screen_with_source, it) } ?: stringResource(R.string.photo_full_screen),
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
            CloseButton(onClose, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp))
        }
    }
}

/**
 * Перевод крупным текстом на весь экран — повернуть телефон к собеседнику.
 * Размер шрифта подбирается по длине фразы, чтобы короткие реплики читались издалека.
 */
@Composable
fun FullscreenText(text: String, caption: String, onClose: () -> Unit) {
    val size = when {
        text.length <= 30 -> 56.sp
        text.length <= 80 -> 44.sp
        text.length <= 160 -> 34.sp
        else -> 26.sp
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .clickable(onClickLabel = stringResource(R.string.close), onClick = onClose),
        ) {
            Column(
                Modifier.align(Alignment.Center).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(caption, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    text,
                    fontSize = size,
                    lineHeight = size * 1.15f,
                    fontWeight = FontWeight.Medium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
            CloseButton(onClose, Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(8.dp))
        }
    }
}

@Composable
private fun CloseButton(onClose: () -> Unit, modifier: Modifier) {
    // IconButton — зона нажатия 48 dp.
    IconButton(onClick = onClose, modifier = modifier) {
        Icon(painterResource(R.drawable.ic_sym_close), contentDescription = stringResource(R.string.close), tint = Color.Gray, modifier = Modifier.size(28.dp))
    }
}
