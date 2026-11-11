package dev.pingfront.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import kotlin.math.abs
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MAX_ZOOM = 5f
private const val DOUBLE_TAP_ZOOM = 3f
private const val DISMISS_DRAG_PX = 240f
private const val JPEG_QUALITY = 90

/** Full screen: pinch to 5x, double-tap to 3x, drag down to close, and Share. */
@Composable
internal fun PhotoViewer(image: ImageBitmap, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var drag by remember { mutableFloatStateOf(0f) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var shared by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) { PhotoShare.clear(context) }
    // When the share sheet returns, the shared copy is deleted (review CQ-M13).
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME && shared) {
                shared = false
                scope.launch { PhotoShare.clear(context) }
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - abs(drag) / (DISMISS_DRAG_PX * 2)).coerceIn(0.3f, 1f)))) {
            Image(
                image,
                contentDescription = stringResource(R.string.pf_photo),
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = {
                            scale = if (scale > 1f) 1f else DOUBLE_TAP_ZOOM
                            offset = Offset.Zero
                        })
                    }
                    .pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, MAX_ZOOM)
                            if (scale > 1f) {
                                offset += pan
                            } else {
                                drag += pan.y
                                if (drag > DISMISS_DRAG_PX) onDismiss()
                            }
                        }
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y + if (scale == 1f) drag else 0f
                    },
            )
            // The photo fills the window; the controls keep clear of the status bar and, in landscape, the cutout.
            Row(
                Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        androidx.compose.foundation.layout.WindowInsets.safeDrawing.only(
                            androidx.compose.foundation.layout.WindowInsetsSides.Top + androidx.compose.foundation.layout.WindowInsetsSides.Horizontal,
                        ),
                    )
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        painterResource(R.drawable.pf_ic_close),
                        contentDescription = stringResource(R.string.pf_close),
                        tint = Color.White,
                    )
                }
                Box(Modifier.weight(1f))
                IconButton(onClick = {
                    scope.launch { shared = PhotoShare.share(context, image) }
                }) {
                    Icon(
                        painterResource(R.drawable.pf_ic_open_in_new),
                        contentDescription = stringResource(R.string.pf_share),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}

/**
 * The one chat photo that ever touches the disk: the copy handed to the share sheet. It is deleted when the
 * share sheet returns, when the viewer opens again, and on logout and erase (review CQ-M13).
 */
internal object PhotoShare {
    private fun dir(context: Context) = File(context.cacheDir, "pingfront-share")

    /** Deletes every shared copy. Blocking file work runs off the main thread. */
    suspend fun clear(context: Context) = withContext(ChatImages.io) { dir(context).listFiles()?.forEach { it.delete() } }

    /** Deletes every shared copy now (the wipe listener, which is not suspending). */
    fun clearNow(context: Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    /** Writes the photo the person chose to share (JPEG encode off the main thread), then opens the share sheet. */
    suspend fun share(context: Context, image: ImageBitmap): Boolean {
        val uri = withContext(ChatImages.io) {
            try {
                val file = File(dir(context).apply { mkdirs() }, "photo.jpg")
                file.outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
                FileProvider.getUriForFile(context, "${context.packageName}.pingfront.share", file)
            } catch (_: IOException) {
                null
            } catch (_: IllegalArgumentException) {
                // The provider is not in the merged manifest.
                null
            }
        } ?: return false
        val send = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}

/**
 * The SDK's own share provider. A subclass, so it never collides in the manifest merger with the host
 * app's `androidx.core.content.FileProvider` (review CQ-H6). Authority: `<applicationId>.pingfront.share`.
 */
internal class PingFrontShareProvider : FileProvider()
