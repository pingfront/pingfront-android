package dev.pingfront.reports

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFront
import kotlinx.coroutines.launch

/** The report screen: what went wrong, the screenshot with freehand marks, and Send. */
public class ReportActivity : ComponentActivity() {
    internal class Pending(val screenshot: Bitmap?, val prefill: String?)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // The screenshot survives rotation in a ViewModel (review CQ-M14); the static hand-off is read once.
        val holder = androidx.lifecycle.ViewModelProvider(this)[Holder::class.java]
        val pending = holder.pending ?: (Companion.pending.also { Companion.pending = null } ?: Pending(null, null)).also { holder.pending = it }
        setContent {
            // The chat's own colors and type, never Material's baseline lavender (audit A-07).
            dev.pingfront.ui.PingFrontScreenTheme { ReportScreen(pending, onDone = ::finish) }
        }
    }

    /** Keeps the screenshot across configuration changes. */
    internal class Holder : androidx.lifecycle.ViewModel() {
        var pending: Pending? = null
    }

    internal companion object {
        @Volatile var pending: Pending? = null
    }
}

/** Marking up and PNG-encoding the screenshot run on the SDK's injected dispatcher (review AN-20). */
internal object ReportWork {
    val dispatcher: kotlinx.coroutines.CoroutineDispatcher get() = PingFront.instance.workDispatcher()
}

private const val MARK_WIDTH_DP = 4f
private const val MARK_COLOR = 0xFFFF2D78

@Composable
internal fun ReportScreen(
    pending: ReportActivity.Pending,
    send: suspend (String, ByteArray?) -> Unit = { text, png -> PingFront.reportBug(text, png) },
    onDone: () -> Unit,
) {
    var text by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(pending.prefill.orEmpty()) }
    var include by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(pending.screenshot != null) }
    var sending by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scope = rememberCoroutineScope()
    val shotForSend = pending.screenshot
    fun send() {
        sending = true
        failed = false
        val marks = strokes.toList()
        scope.launch {
            // Marking up and PNG-encoding a full-resolution screenshot runs off the main thread (review CQ-M14).
            val bytes = if (include && shotForSend != null) {
                kotlinx.coroutines.withContext(ReportWork.dispatcher) { Screenshot.png(marked(shotForSend, marks, size)) }
            } else {
                null
            }
            // A report that did not go out keeps the screen, its text and marks, and says so (review AN-7).
            if (dev.pingfront.ui.sendReport { send(text, bytes) }) onDone() else failed = true
            sending = false
        }
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).safeDrawingPadding()) {
        // A Material top app bar: close, the title, and Send (audit A-07).
        @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
        androidx.compose.material3.TopAppBar(
            title = { Text(stringResource(dev.pingfront.ui.R.string.pf_report_bug)) },
            navigationIcon = {
                androidx.compose.material3.IconButton(onClick = onDone) {
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(dev.pingfront.ui.R.drawable.pf_ic_close),
                        contentDescription = stringResource(dev.pingfront.ui.R.string.pf_cancel),
                    )
                }
            },
            actions = {
                TextButton(enabled = text.isNotBlank() && !sending, onClick = ::send) { Text(stringResource(dev.pingfront.ui.R.string.pf_send)) }
            },
            windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
            colors = androidx.compose.material3.TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        )
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val question = stringResource(dev.pingfront.ui.R.string.pf_what_went_wrong)
            // The heading is read once, as the field's label (review AN-25), not as a separate stop.
            Text(question, style = MaterialTheme.typography.titleMedium, modifier = Modifier.clearAndSetSemantics {})
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).semantics { contentDescription = question },
            )
            if (failed) dev.pingfront.ui.ReportNotDelivered()
            val shot = pending.screenshot
            if (shot != null) {
                // The whole row is the switch, so TalkBack reads its label with its state (review CQ-L17).
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = include, role = Role.Switch, onValueChange = { include = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(dev.pingfront.ui.R.string.pf_include_screenshot), modifier = Modifier.weight(1f))
                    Switch(checked = include, onCheckedChange = null)
                }
                if (include) {
                    Text(
                        stringResource(dev.pingfront.ui.R.string.pf_draw_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Box(
                            Modifier.aspectRatio(shot.width.toFloat() / shot.height).clip(RoundedCornerShape(12.dp)).onSizeChanged {
                                size = it
                            },
                        ) {
                            Image(
                                shot.asImageBitmap(),
                                contentDescription = stringResource(dev.pingfront.ui.R.string.pf_screenshot),
                                modifier = Modifier.fillMaxSize(),
                            )
                            Canvas(
                                Modifier.fillMaxSize().pointerInput(Unit) {
                                    detectDragGestures(
                                        onDragStart = { current = listOf(it) },
                                        onDrag = { change, _ -> current = current + change.position },
                                        onDragEnd = {
                                            strokes += current
                                            current = emptyList()
                                        },
                                    )
                                },
                            ) {
                                (strokes + listOf(current)).forEach { points ->
                                    if (points.size < 2) return@forEach
                                    val path = Path().apply {
                                        moveTo(points.first().x, points.first().y)
                                        points.drop(1).forEach { lineTo(it.x, it.y) }
                                    }
                                    drawPath(path, Color(MARK_COLOR), style = Stroke(width = MARK_WIDTH_DP * density, cap = StrokeCap.Round))
                                }
                            }
                        }
                    }
                    if (strokes.isNotEmpty()) {
                        TextButton(onClick = { strokes.clear() }) { Text(stringResource(dev.pingfront.ui.R.string.pf_clear_marks)) }
                    }
                }
            }
        }
    }
}

/** Burns the marks into a copy of the screenshot at its full resolution. */
private fun marked(source: Bitmap, strokes: List<List<Offset>>, shown: IntSize): Bitmap {
    if (strokes.isEmpty() || shown.width == 0) return source
    val copy = source.copy(Bitmap.Config.ARGB_8888, true)
    val scale = source.width.toFloat() / shown.width
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = MARK_COLOR.toInt()
        style = Paint.Style.STROKE
        strokeWidth = MARK_WIDTH_DP * 3 * scale
        strokeCap = Paint.Cap.ROUND
    }
    val canvas = Canvas(copy)
    strokes.forEach { points -> points.zipWithNext { a, b -> canvas.drawLine(a.x * scale, a.y * scale, b.x * scale, b.y * scale, paint) } }
    return copy
}
