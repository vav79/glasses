package ru.vasev.glasses.ui

import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import ru.vasev.glasses.camera.PhoneCamera

/** Видоискатель камеры телефона на всю область: камера работает, только пока он на экране. */
@Composable
internal fun PhoneViewfinderFull(camera: PhoneCamera) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        camera.bind(lifecycleOwner, previewView)
        onDispose { camera.unbind() }
    }
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
}
