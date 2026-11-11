package dev.pingfront.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.content.hasMediaType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.pingfront.PingFrontInstance
import dev.pingfront.RateScope
import dev.pingfront.conversation.ConversationState
import dev.pingfront.look.ResolvedLook
import dev.pingfront.model.SendPolicy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal const val TEXT_LIMIT: Int = 4000
private const val IMAGES_PER_MESSAGE = 6
private const val SECOND_MS = 1000L

/** Test tags for the composer's layout (alignment tests). */
internal object ComposerTags {
    const val ADD: String = "pf_composer_add"
    const val FIELD: String = "pf_composer_field"
    const val SEND: String = "pf_composer_send"
}

/** The 48 dp touch target of the + and send buttons, and the field's minimum height. */
internal val BUTTON_TARGET = 48.dp

/** The visible + circle (Material's filled tonal icon button). */
private val ADD_CIRCLE = 40.dp

/** One gap between the +, the field and send. */
private val COMPOSER_GAP = 6.dp

/** The field's top and bottom padding around its text. */
internal val FIELD_PADDING_V = 12.dp

private val COUNTER_TOP = 4.dp

/**
 * The field grows until it takes this share of the window, then scrolls inside: Google Messages on a Pixel 8 grows
 * to 330 dp of 915 (about 12 lines at 100 %, 6 at 200 %).
 */
internal const val COMPOSER_WINDOW_SHARE: Float = 1f / 3

/** The fallback cap when the window size is not known yet. */
internal const val COMPOSER_MAX_LINES: Int = 5

/** The height the chat leaves for the composer (the header taken off); its pill never grows past it. */
internal val LocalComposerRoom = androidx.compose.runtime.staticCompositionLocalOf { androidx.compose.ui.unit.Dp.Unspecified }

/** The composer's own padding above and below the pill. */
private val COMPOSER_PADDING_V = 6.dp + 8.dp

/** The character counter appears this close to the limit. */
private const val COUNTER_FROM = 200

/** Keeps at most [max] Unicode code points (the protocol's limit). */
internal fun limitCodePoints(text: String, max: Int = TEXT_LIMIT): String =
    if (text.codePointCount(0, text.length) <= max) text else text.substring(0, text.offsetByCodePoints(0, max))

/** Composer rules, as plain logic so they are tested without Compose. */
internal object ComposerRules {
    /** Code points, never UTF-16 units: an emoji counts once. */
    fun count(text: CharSequence): Int = Character.codePointCount(text, 0, text.length)

    /** The counter shows near the limit; null hides it. */
    fun counter(count: Int): Int? = count.takeIf { it > TEXT_LIMIT - COUNTER_FROM }

    fun overLimit(count: Int): Boolean = count > TEXT_LIMIT

    /** A topic's sentence start goes in only when the field is empty: typed text is never overwritten. */
    fun insert(current: String, starter: String): String? = starter.takeIf { current.isBlank() }

    /** A keyboard's Enter sends; Shift+Enter (and the on-screen keyboard's Enter) adds a line. */
    fun hardwareSend(isEnter: Boolean, shift: Boolean, physical: Boolean): Boolean =
        hardwareEnter(isEnter, shift, physical, composing = false) == HardwareEnter.Send

    /** What a hardware keyboard's Enter does in the composer. */
    enum class HardwareEnter { None, Send, NewLine }

    /**
     * A keyboard's Enter sends and its Shift+Enter adds a line; the on-screen keyboard's Enter, and any Enter while an
     * IME is [composing] (it commits the composition), are left to the IME and the field.
     */
    fun hardwareEnter(isEnter: Boolean, shift: Boolean, physical: Boolean, composing: Boolean): HardwareEnter = when {
        !isEnter || !physical || composing -> HardwareEnter.None
        shift -> HardwareEnter.NewLine
        else -> HardwareEnter.Send
    }
}

/** Staged photos per SDK instance: they survive rotation (bytes are too large for saved state). */
internal object StagedPhotoStore {
    private val staged = java.util.WeakHashMap<PingFrontInstance, androidx.compose.runtime.snapshots.SnapshotStateList<ByteArray>>()

    @Synchronized
    fun of(instance: PingFrontInstance): androidx.compose.runtime.snapshots.SnapshotStateList<ByteArray> = staged.getOrPut(instance) { mutableStateListOf() }
}

/**
 * An add button (photos, camera, bug report), a pill text field that grows to five lines, and a send button that
 * appears once there is something to send. Images can also be pasted; a counter appears near the length limit.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun Composer(instance: PingFrontInstance, state: ConversationState, look: ResolvedLook, disabled: Boolean) {
    val theme = LocalChatTheme.current
    val conversation = instance.conversation
    val preview = LocalChatPreview.current
    val draft by conversation.draft.collectAsState()
    val insertion by conversation.composerInsertion.collectAsState()
    // Saved across rotation; the draft itself is also stored on disk, so it survives process death. A topic
    // starter waiting when the composer first appears is its first text, so the first frame already shows it
    // (audit A-01); the effect below then finds the field filled and only consumes it.
    val field = androidx.compose.foundation.text.input.rememberTextFieldState(
        preview.draft ?: draft.ifBlank { insertion ?: draft },
    )
    val staged = if (preview.stagedPhotos.isNotEmpty()) {
        remember { mutableStateListOf<ByteArray>().apply { addAll(preview.stagedPhotos) } }
    } else {
        remember(instance) { StagedPhotoStore.of(instance) }
    }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    // Focus survives rotation, so the keyboard stays up mid-sentence as in Google Messages. The value restored from
    // saved state is read once; focus changes keep it current.
    var focused by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val refocus = remember { focused }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { if (refocus && !preview.still) runCatching { focus.requestFocus() } }

    LaunchedEffect(insertion) {
        insertion?.let { starter ->
            ComposerRules.insert(field.text.toString(), starter)?.let { field.setTextAndPlaceCursorAtEnd(it) }
            conversation.updateDraft(field.text.toString())
            conversation.consumeInsertion()
            // A topic starter is a sentence to finish: put the cursor there, nothing is sent (audit X-01).
            if (!preview.still) runCatching { focus.requestFocus() }
        }
    }
    // Typing updates the draft; a send (draft emptied) clears the field; a draft restored from disk after
    // process death fills an empty field.
    LaunchedEffect(field) {
        androidx.compose.runtime.snapshotFlow { field.text.toString() }.collect { conversation.updateDraft(it) }
    }
    LaunchedEffect(draft) {
        if (preview.draft != null) return@LaunchedEffect
        // The current draft, not the one this effect was keyed on: a starter shown in the first frame has already
        // become the draft, and must not be cleared by the empty draft of that frame.
        val current = conversation.draft.value
        if (current.isNotEmpty() && field.text.isEmpty()) field.setTextAndPlaceCursorAtEnd(current)
        if (current.isEmpty() && field.text.isNotEmpty()) field.clearText()
    }

    fun stage(uris: List<Uri>, afterRead: () -> Unit = {}) {
        scope.launch {
            val bytes = withContext(ChatImages.io) {
                uris.mapNotNull { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull() }
                    .also { afterRead() }
            }
            bytes.take(IMAGES_PER_MESSAGE - staged.size).forEach { staged += it }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(IMAGES_PER_MESSAGE)) { uris: List<Uri> -> stage(uris) }
    // A capture is staged in memory, then its file goes: the original never waits on disk (review AN-6).
    val camera = rememberCamera { uri -> stage(listOf(uri)) { CameraFiles.clearNow(context) } }

    val notice = policyNotice(state.policy)
    // The + circle (40 dp in its 48 dp target) starts where the bubbles start: the gutter.
    Column(Modifier.fillMaxWidth().padding(start = theme.gutter - 4.dp, end = theme.gutter - 4.dp, top = 6.dp, bottom = 8.dp)) {
        if (notice != null) {
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = theme.muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 12.dp),
            )
            return@Column
        }
        // One ticker drives the countdown lines and the send button, so the button returns the moment a cooldown ends (review CQ-L14).
        val (messagesLeft, uploadsLeft) = rememberCooldownsLeft(instance, state)
        CooldownLines(messagesLeft, uploadsLeft)
        if (staged.isNotEmpty()) StagedPhotos(staged) { staged.removeAt(it) }
        val count = ComposerRules.count(field.text)
        val over = ComposerRules.overLimit(count)
        val canSend = (field.text.isNotBlank() || staged.isNotEmpty()) && !over && messagesLeft == null && !disabled
        val canStage = look.photos && staged.size < IMAGES_PER_MESSAGE && uploadsLeft == null
        val send = {
            conversation.send(field.text.toString(), staged.toList())
            staged.clear()
            field.clearText()
        }
        // Buttons sit on the field's last line (Google Messages): centered on a one-line field, on the last line once it
        // grows, at every font scale. Their center rises by the field's bottom padding plus half the last line as laid
        // out (taller than the style's line height at large font scales), less half a button; a character counter
        // below the text adds its own height.
        val density = LocalDensity.current
        var measuredLine by remember { mutableStateOf<androidx.compose.ui.unit.Dp?>(null) }
        val line = measuredLine ?: with(density) { MaterialTheme.typography.bodyLarge.lineHeight.toDp() }
        val counterHeight = if (ComposerRules.counter(count) != null) {
            with(density) { MaterialTheme.typography.labelSmall.lineHeight.toDp() } + COUNTER_TOP
        } else {
            0.dp
        }
        val lift = (FIELD_PADDING_V + line / 2 + counterHeight - BUTTON_TARGET / 2).coerceAtLeast(0.dp)
        val window = with(density) { androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toDp() }
        val room = LocalComposerRoom.current
        val pillMax = when {
            window <= 0.dp -> androidx.compose.ui.unit.Dp.Unspecified
            room == androidx.compose.ui.unit.Dp.Unspecified -> window * COMPOSER_WINDOW_SHARE
            else -> minOf(window * COMPOSER_WINDOW_SHARE, room - COMPOSER_PADDING_V)
        }
        val textMax = if (pillMax == androidx.compose.ui.unit.Dp.Unspecified) pillMax else (pillMax - FIELD_PADDING_V * 2).coerceAtLeast(line)
        val maxLines = if (window > 0.dp) Int.MAX_VALUE else COMPOSER_MAX_LINES
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(COMPOSER_GAP)) {
            if (look.photos || look.reports) {
                AddButton(
                    modifier = Modifier.padding(bottom = lift).testTag(ComposerTags.ADD),
                    photos = canStage,
                    camera = canStage && camera != null,
                    reports = look.reports,
                    enabled = !disabled,
                    onPhotos = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    onCamera = { camera?.invoke() },
                    onReport = { instance.openReport(null) },
                )
            }
            val placeholder = look.composerPlaceholder.text() ?: stringResource(R.string.pf_message)
            Column(
                Modifier
                    .weight(1f)
                    .heightIn(min = BUTTON_TARGET)
                    .testTag(ComposerTags.FIELD)
                    .clip(RoundedCornerShape(BUTTON_TARGET / 2))
                    .background(theme.teamBubble)
                    .padding(horizontal = 16.dp, vertical = FIELD_PADDING_V),
                verticalArrangement = Arrangement.Center,
            ) {
                // The placeholder and the first line share one box, so the placeholder is centered like the text.
                Box(contentAlignment = Alignment.CenterStart) {
                    if (field.text.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = theme.muted, maxLines = 1)
                    androidx.compose.foundation.text.BasicTextField(
                        state = field,
                        enabled = !disabled,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = theme.text).merge(AuthoredText),
                        cursorBrush = SolidColor(theme.accent),
                        // Sentences start with a capital letter, as in Google Messages; Enter stays a new line.
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences,
                        ),
                        lineLimits = androidx.compose.foundation.text.input.TextFieldLineLimits.MultiLine(1, maxLines),
                        onTextLayout = { result ->
                            result()?.let { layout ->
                                val last = layout.lineCount - 1
                                if (last >= 0) measuredLine = (layout.getLineBottom(last) - layout.getLineTop(last)).toDp()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = textMax)
                            .focusRequester(focus)
                            .onFocusChanged {
                                // A rotation clears focus after onPause and before the state is saved: only a
                                // change while the screen is resumed (a person's tap) counts.
                                if (it.isFocused || lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) focused = it.isFocused
                            }
                            .semantics { contentDescription = placeholder }
                            // Pasted images are staged like picked ones (Google Messages); text pastes as usual.
                            .contentReceiver { content ->
                                if (!canStage || !content.hasMediaType(androidx.compose.foundation.content.MediaType.Image)) return@contentReceiver content
                                val clip = content.clipEntry.clipData
                                val images = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                                stage(images)
                                content.consume { it.uri != null }
                            }
                            // A hardware keyboard's Enter sends; Shift+Enter adds a line. Intercepted before the soft
                            // keyboard, which would otherwise turn the Enter into a newline (found on the emulator).
                            .onInterceptKeyBeforeSoftKeyboard { event ->
                                val enter = event.key == androidx.compose.ui.input.key.Key.Enter || event.key == androidx.compose.ui.input.key.Key.NumPadEnter
                                // On-screen keyboards commit text or flag their key events; anything else is a keyboard.
                                val physical = event.nativeKeyEvent.flags and android.view.KeyEvent.FLAG_SOFT_KEYBOARD == 0
                                val down = event.type == androidx.compose.ui.input.key.KeyEventType.KeyDown
                                // While an IME composes (CJK, for one), Enter commits the composition: it is the IME's.
                                val composing = field.composition != null
                                when (ComposerRules.hardwareEnter(enter, event.isShiftPressed, physical, composing)) {
                                    ComposerRules.HardwareEnter.None -> return@onInterceptKeyBeforeSoftKeyboard false
                                    ComposerRules.HardwareEnter.Send -> if (down && canSend) send()
                                    // The line break is the composer's own, so an on-screen keyboard that is up (and
                                    // sees the key first) never decides it (HardwareKeyboardTest, audit round 2).
                                    ComposerRules.HardwareEnter.NewLine -> if (down) field.edit { replace(selection.min, selection.max, "\n") }
                                }
                                true
                            },
                    )
                }
                ComposerRules.counter(count)?.let { CharacterCounter(it) }
            }
            AnimatedVisibility(visible = canSend, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
                SmallFloatingActionButton(
                    onClick = send,
                    containerColor = theme.accent,
                    contentColor = theme.onAccent,
                    shape = CircleShape,
                    elevation = androidx.compose.material3.FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                    modifier = Modifier.padding(bottom = lift).size(BUTTON_TARGET).testTag(ComposerTags.SEND),
                ) {
                    Icon(painterResource(R.drawable.pf_ic_send), contentDescription = stringResource(R.string.pf_send))
                }
            }
        }
    }
}

/** "3,912 / 4,000" near the limit; red and "over the limit" for TalkBack once it is passed (send is disabled). */
@Composable
private fun CharacterCounter(count: Int) {
    val theme = LocalChatTheme.current
    val over = ComposerRules.overLimit(count)
    val format = java.text.NumberFormat.getIntegerInstance()
    val spoken = if (over) {
        androidx.compose.ui.res.pluralStringResource(R.plurals.pf_characters_over, count - TEXT_LIMIT, count - TEXT_LIMIT)
    } else {
        androidx.compose.ui.res.pluralStringResource(R.plurals.pf_characters_left, TEXT_LIMIT - count, TEXT_LIMIT - count)
    }
    Text(
        stringResource(R.string.pf_character_count, format.format(count), format.format(TEXT_LIMIT)),
        style = MaterialTheme.typography.labelSmall,
        color = if (over) MaterialTheme.colorScheme.error else theme.muted,
        textAlign = TextAlign.End,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .semantics {
                contentDescription = spoken
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            },
    )
}

/**
 * Camera capture into the SDK's cache (shared through its FileProvider), or null when the device has no camera.
 * If the host app declares the CAMERA permission, Android requires it to be granted first, so it is asked for.
 */
@Composable
private fun rememberCamera(onPhoto: (Uri) -> Unit): (() -> Unit)? {
    val context = LocalContext.current
    if (!context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_CAMERA_ANY)) return null
    var target by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Uri?>(null) }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { saved ->
        target?.let { if (saved) onPhoto(it) }
        target = null
    }
    fun launch() {
        val uri = CameraFiles.newPhoto(context) ?: return
        target = uri
        runCatching { capture.launch(uri) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launch() }
    return {
        if (CameraFiles.needsPermission(context)) permission.launch(android.Manifest.permission.CAMERA) else launch()
    }
}

/**
 * Camera photos go to the SDK's cache, shared only with the camera app through the SDK's FileProvider. A capture is
 * deleted once it is staged, and on logout and erase.
 */
internal object CameraFiles {
    private fun dir(context: android.content.Context) = java.io.File(context.cacheDir, "pingfront-camera")

    /** Deletes every capture (blocking file work: call it off the main thread, or from the wipe). */
    fun clearNow(context: android.content.Context) {
        dir(context).listFiles()?.forEach { it.delete() }
    }

    fun newPhoto(context: android.content.Context): Uri? = runCatching {
        val dir = dir(context).apply { mkdirs() }
        // One capture at a time: older ones are removed so photos never pile up in the cache.
        dir.listFiles()?.forEach { it.delete() }
        val file = java.io.File(dir, "photo-${System.nanoTime()}.jpg")
        androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.pingfront.share", file)
    }.getOrNull()

    /** True when the host declares CAMERA and it is not granted (TakePicture then needs it). */
    fun needsPermission(context: android.content.Context): Boolean {
        val declared = runCatching {
            context.packageManager.getPackageInfo(context.packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
                .requestedPermissions.orEmpty().contains(android.Manifest.permission.CAMERA)
        }.getOrDefault(false)
        return declared && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }
}

@Composable
private fun AddButton(
    photos: Boolean,
    camera: Boolean,
    reports: Boolean,
    enabled: Boolean,
    onPhotos: () -> Unit,
    onCamera: () -> Unit,
    onReport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalChatTheme.current
    var open by remember { mutableStateOf(false) }
    val options = listOf(photos, camera, reports).count { it }
    Box(modifier) {
        val label = stringResource(
            when {
                options > 1 -> R.string.pf_add_photo_or_report
                photos || camera -> R.string.pf_add_photo
                else -> R.string.pf_report_bug
            },
        )
        FilledTonalIconButton(
            onClick = {
                when {
                    options > 1 -> open = true
                    photos -> onPhotos()
                    camera -> onCamera()
                    else -> onReport()
                }
            },
            enabled = enabled && options > 0,
            colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = theme.accentTint, contentColor = theme.onAccentTint),
            // Material's 40 dp circle inside a 48 dp touch target.
            modifier = Modifier.size(BUTTON_TARGET).padding((BUTTON_TARGET - ADD_CIRCLE) / 2),
        ) {
            Icon(painterResource(R.drawable.pf_ic_add), contentDescription = label)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (photos) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pf_photos)) },
                    leadingIcon = { Icon(painterResource(R.drawable.pf_ic_photo_library), contentDescription = null) },
                    onClick = {
                        open = false
                        onPhotos()
                    },
                )
            }
            if (camera) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pf_take_photo)) },
                    leadingIcon = { Icon(painterResource(R.drawable.pf_ic_photo_camera), contentDescription = null) },
                    onClick = {
                        open = false
                        onCamera()
                    },
                )
            }
            if (reports) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.pf_report_bug)) },
                    leadingIcon = { Icon(painterResource(R.drawable.pf_ic_bug_report), contentDescription = null) },
                    onClick = {
                        open = false
                        onReport()
                    },
                )
            }
        }
    }
}

@Composable
private fun StagedPhotos(photos: List<ByteArray>, remove: (Int) -> Unit) {
    val theme = LocalChatTheme.current
    val px = with(LocalDensity.current) { 64.dp.roundToPx() }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 56.dp, bottom = 8.dp)) {
        itemsIndexed(photos) { index, bytes ->
            // Decoded off the main thread (snapshots and previews decode inline so the image is in the frame; review CQ-M14).
            val still = LocalChatPreview.current.still
            val image by androidx.compose.runtime.produceState(if (still) ChatImages.decode(bytes, px) else null, bytes) {
                if (value == null) value = kotlinx.coroutines.withContext(ChatImages.io) { ChatImages.decode(bytes, px) }
            }
            Box(Modifier.size(72.dp)) {
                Box(Modifier.size(64.dp).align(Alignment.BottomStart).clip(RoundedCornerShape(12.dp)).background(theme.teamBubble)) {
                    image?.let {
                        Image(
                            it.asImageBitmap(),
                            contentDescription = stringResource(R.string.pf_photo),
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(64.dp),
                        )
                    }
                }
                FilledTonalIconButton(
                    onClick = { remove(index) },
                    modifier = Modifier.size(28.dp).align(Alignment.TopEnd),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = theme.text, contentColor = theme.background),
                ) {
                    Icon(
                        painterResource(R.drawable.pf_ic_close),
                        contentDescription = stringResource(R.string.pf_remove_photo, index + 1),
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

/** Exact countdowns under five minutes, a general line otherwise. Text keeps sending while uploads wait. */
@Composable
private fun rememberCooldownsLeft(instance: PingFrontInstance, state: ConversationState): Pair<kotlin.time.Duration?, kotlin.time.Duration?> {
    var tick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val still = LocalChatPreview.current.still
    val conversation = instance.conversation
    LaunchedEffect(state.coolingDown) {
        while (!still && state.coolingDown.isNotEmpty()) {
            delay(SECOND_MS)
            tick += 1
            if (conversation.cooldownRemaining(RateScope.Messages) == null && conversation.cooldownRemaining(RateScope.Uploads) == null) break
        }
    }
    return remember(tick, state.coolingDown) {
        conversation.cooldownRemaining(RateScope.Messages) to conversation.cooldownRemaining(RateScope.Uploads)
    }
}

@Composable
private fun CooldownLines(messagesLeft: kotlin.time.Duration?, uploadsLeft: kotlin.time.Duration?) {
    val theme = LocalChatTheme.current
    if (messagesLeft == null && uploadsLeft == null) return
    Column(Modifier.padding(start = 12.dp, bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        messagesLeft?.let { left ->
            CooldownLine(
                countdown(left)?.let {
                    stringResource(R.string.pf_send_again_in, it)
                } ?: stringResource(R.string.pf_sending_paused),
                theme.muted,
            )
        }
        uploadsLeft?.let { left ->
            CooldownLine(
                countdown(left)?.let {
                    stringResource(R.string.pf_photos_again_in, it)
                } ?: stringResource(R.string.pf_photos_paused),
                theme.muted,
            )
        }
    }
}

@Composable
private fun CooldownLine(text: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(R.drawable.pf_ic_hourglass_empty), contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = Modifier.padding(start = 6.dp))
    }
}

/** `m:ss` when under five minutes. */
internal fun countdown(remaining: kotlin.time.Duration): String? {
    if (remaining.inWholeSeconds >= FIVE_MINUTES_S) return null
    val seconds = ((remaining.inWholeMilliseconds + SECOND_MS - 1) / SECOND_MS).toInt().coerceAtLeast(0)
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

private const val FIVE_MINUTES_S = 300

@Composable
private fun policyNotice(policy: SendPolicy): String? = when (policy) {
    SendPolicy.Open -> null
    is SendPolicy.Closed -> stringResource(
        when (policy.reason) {
            SendPolicy.Reason.AppPaused -> R.string.pf_paused
            SendPolicy.Reason.VerificationRequired -> R.string.pf_verification_required
            else -> R.string.pf_blocked
        },
    )
}
