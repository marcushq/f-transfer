package dev.fujitransfer.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.FloatExponentialDecaySpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import dev.fujitransfer.SavedPhoto
import dev.fujitransfer.SavedViewModel
import dev.fujitransfer.ShotDetails
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

private val shotTime = SimpleDateFormat("dd.MM.yyyy · HH:mm", Locale.US)
private val Ink = Color(0xFFF2EFEA)
private val InkSoft = Color(0xFF8E8A82)
private val Lime = Color(0xFFFF9D28)
private val Button = Color(0xFF141414)

/** Full screen, swipe between photos, pinch or double-tap to zoom, strip of neighbours below. */
@Composable
fun PhotoViewer(photos: List<SavedPhoto>, start: Int, vm: SavedViewModel) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (photos.size - 1).coerceAtLeast(0))) { photos.size }
    var zoomed by remember { mutableStateOf(false) }
    BackHandler { vm.close() }
    LaunchedEffect(pager.currentPage) { vm.open(pager.currentPage); zoomed = false }

    val current = photos.getOrNull(pager.currentPage) ?: return
    val details by produceState<ShotDetails?>(null, current.id) { value = vm.details(current) }
    val delete = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) vm.deleted(current)
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = vm::close) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Quay lại", tint = Ink, modifier = Modifier.size(28.dp)) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(current.name, fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${pager.currentPage + 1} / ${photos.size}", fontFamily = Mono, fontSize = 11.sp, color = InkSoft)
            }
            Box(Modifier.size(48.dp))
        }

        // One finger swipes between photos; the pager only stops listening while a photo is zoomed in.
        HorizontalPager(
            state = pager, userScrollEnabled = !zoomed, key = { photos[it].id }, beyondViewportPageCount = 1,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { page ->
            val p = photos[page]
            val full by produceState<ImageBitmap?>(null, p.id) { value = vm.full(p, 2400)?.asImageBitmap() }
            ZoomableImage(
                full ?: vm.thumbs[p.id], p.name,
                active = page == pager.currentPage,
                onZoom = { if (page == pager.currentPage) zoomed = it },
            )
        }

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Exposure and lens only: film simulation and other camera settings aren't readable from the file.
            Text(
                details?.let { d -> listOf(d.line, d.lens).filter { it.isNotBlank() }.joinToString(" · ") }.orEmpty(),
                fontFamily = Mono, fontSize = 12.sp, color = Color(0xFFD9D5CD), maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Row {
                Text(shotTime.format(Date(current.takenMs)), fontFamily = Mono, fontSize = 12.sp, color = InkSoft, modifier = Modifier.weight(1f))
                Text(
                    "%.1f MB".format(current.bytes / 1_048_576.0).replace('.', ',') + if (current.width > 0) " · ${current.width}×${current.height}" else "",
                    fontFamily = Mono, fontSize = 12.sp, color = InkSoft,
                )
            }
        }

        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 18.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ViewerAction(Icons.Outlined.Share, "Chia sẻ", Ink) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = current.mime.ifBlank { "image/*" }
                    putExtra(Intent.EXTRA_STREAM, current.uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Chia sẻ ảnh"))
            }
            ViewerAction(Icons.Outlined.OpenInNew, "Mở bằng…", Ink) {
                val view = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(current.uri, current.mime.ifBlank { "image/*" })
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(view, "Mở ảnh bằng"))
            }
            ViewerAction(Icons.Outlined.Delete, "Xoá", Color(0xFFF09595)) {
                // The system asks for confirmation itself.
                delete.launch(IntentSenderRequest.Builder(vm.deleteRequest(current)).build())
            }
        }

        Strip(photos, pager.currentPage, vm) { i -> scope.launch { pager.animateScrollToPage(i) } }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.ViewerAction(icon: ImageVector, label: String, tint: Color, onClick: () -> Unit) {
    Column(
        Modifier.weight(1f).height(60.dp).clip(RoundedCornerShape(14.dp)).background(Button).clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(label, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 11.sp, color = tint)
    }
}

/** Thumbnails of the neighbours, the current one wider and ringed, kept centred. */
@Composable
private fun Strip(photos: List<SavedPhoto>, current: Int, vm: SavedViewModel, onPick: (Int) -> Unit) {
    val state = rememberLazyListState(initialFirstVisibleItemIndex = current)
    val density = LocalDensity.current
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(current) {
        // The first time, wait until the strip knows its width, then jump; later, glide.
        val viewport = snapshotFlow { state.layoutInfo.viewportSize.width }.first { it > 0 }
        val centre = -(viewport / 2 - with(density) { 24.dp.roundToPx() })
        if (placed) state.animateScrollToItem(current, centre) else state.scrollToItem(current, centre)
        placed = true
    }
    LazyRow(
        state = state,
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
            LaunchedEffect(p.id) { vm.requestThumb(p) }
            val thumb = vm.thumbs[p.id]
            val here = i == current
            Box(
                Modifier.size(48.dp)
                    .then(if (here) Modifier.border(2.dp, Lime, RoundedCornerShape(8.dp)).padding(3.dp) else Modifier.padding(3.dp))
                    .clip(RoundedCornerShape(6.dp)).background(Color(0xFF1E1E1E))
                    .clickable(onClickLabel = "Xem ${p.name}") { onPick(i) },
            ) {
                if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
    }
}

/**
 * Where a photo sits while zoomed: [scale] around the view's centre, then moved by [offset].
 * [view] is the space it is shown in, [image] the photo's pixel size.
 */
@Stable
class ZoomState {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var view = Size.Zero
    var image = Size.Zero

    /** The photo's size at 1×, fitted inside [view]. */
    val content: Size
        get() {
            if (image.width <= 0f || image.height <= 0f) return Size.Zero
            val fit = minOf(view.width / image.width, view.height / image.height)
            return Size(image.width * fit, image.height * fit)
        }

    /** How far the photo can move before an edge comes into view. */
    fun bounds(s: Float = scale) = Offset(
        max(0f, (content.width * s - view.width) / 2),
        max(0f, (content.height * s - view.height) / 2),
    )

    fun clamp(o: Offset, s: Float = scale): Offset {
        val b = bounds(s)
        return Offset(o.x.coerceIn(-b.x, b.x), o.y.coerceIn(-b.y, b.y))
    }

    /** Pinch by [factor] keeping the point under [centroid] (view coordinates) still, then [pan]. */
    fun transform(centroid: Offset, pan: Offset, factor: Float) {
        val next = (scale * factor).coerceIn(MIN_PINCH, MAX_PINCH)
        val c = centroid - Offset(view.width / 2, view.height / 2)
        val moved = c - (c - offset) * (next / scale) + pan
        scale = next
        // Below 1× the photo just follows the fingers; it settles back on release.
        offset = if (next <= 1f) moved else clamp(moved, next)
    }

    /** The offset that puts [point] (view coordinates) of the 1× photo under the same point at [target]. */
    fun focus(point: Offset, target: Float): Offset {
        val c = point - Offset(view.width / 2, view.height / 2)
        return clamp(c - (c - offset) * (target / scale), target)
    }

    fun set(s: Float, o: Offset) { scale = s; offset = o }

    /** Glide to [s], [o] without a bounce. */
    suspend fun animateTo(s: Float, o: Offset) {
        val s0 = scale
        val o0 = offset
        animate(0f, 1f, animationSpec = spring(dampingRatio = 1f, stiffness = 380f)) { t, _ ->
            scale = lerp(s0, s, t)
            offset = lerp(o0, o, t)
        }
    }

    /** After the fingers lift: back inside the limits, or keep drifting with the swipe's speed. */
    suspend fun settle(velocity: Offset) {
        when {
            scale < 1f -> animateTo(1f, Offset.Zero)
            scale > MAX_SCALE -> animateTo(MAX_SCALE, clamp(offset * (MAX_SCALE / scale), MAX_SCALE))
            scale > 1f -> coroutineScope {
                launch {
                    animateDecay(offset.x, velocity.x, FloatExponentialDecaySpec(frictionMultiplier = 2f)) { x, _ ->
                        val b = bounds().x
                        offset = Offset(x.coerceIn(-b, b), offset.y)
                    }
                }
                launch {
                    animateDecay(offset.y, velocity.y, FloatExponentialDecaySpec(frictionMultiplier = 2f)) { y, _ ->
                        val b = bounds().y
                        offset = Offset(offset.x, y.coerceIn(-b, b))
                    }
                }
            }
        }
    }

    companion object {
        const val MAX_SCALE = 5f
        const val DOUBLE_TAP = 2.5f
        private const val MIN_PINCH = 0.7f
        private const val MAX_PINCH = 6f
    }
}

/**
 * Fits the photo. Two fingers zoom around the point between them; one finger moves a zoomed
 * photo and otherwise is left to the pager, so swiping to the next photo keeps working.
 * Double-tap zooms to 2.5× where you tapped, or back out.
 */
@Composable
private fun ZoomableImage(bitmap: ImageBitmap?, description: String, active: Boolean, onZoom: (Boolean) -> Unit) {
    val zoom = remember { ZoomState() }
    val scope = rememberCoroutineScope()
    var motion by remember { mutableStateOf<Job?>(null) }
    fun move(block: suspend () -> Unit) {
        motion?.cancel()
        motion = scope.launch { block(); onZoom(zoom.scale > 1f) }
    }
    // A photo swiped away is shown whole again when it comes back.
    LaunchedEffect(active) { if (!active) { motion?.cancel(); zoom.set(1f, Offset.Zero) } }
    if (bitmap != null) zoom.image = Size(bitmap.width.toFloat(), bitmap.height.toFloat())

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { zoom.view = Size(it.width.toFloat(), it.height.toFloat()) }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = { at ->
                    move {
                        if (zoom.scale > 1f) zoom.animateTo(1f, Offset.Zero)
                        else zoom.animateTo(ZoomState.DOUBLE_TAP, zoom.focus(at, ZoomState.DOUBLE_TAP))
                    }
                })
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    val tracker = VelocityTracker()
                    var handled = false
                    do {
                        val event = awaitPointerEvent()
                        val fingers = event.changes.count { it.pressed }
                        if (fingers >= 2 || zoom.scale > 1f) {
                            if (!handled) { motion?.cancel(); handled = true }
                            val factor = event.calculateZoom()
                            val pan = event.calculatePan()
                            if (factor != 1f || pan != Offset.Zero) {
                                zoom.transform(event.calculateCentroid(useCurrent = true), pan, factor)
                                onZoom(zoom.scale > 1f)
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                            // Only a one-finger move keeps drifting after release, not a pinch.
                            if (fingers >= 2) tracker.resetTracking()
                            else event.changes.firstOrNull { it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }
                        }
                    } while (event.changes.any { it.pressed })
                    if (handled) {
                        val v = tracker.calculateVelocity()
                        move { zoom.settle(Offset(v.x, v.y)) }
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap, description,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    scaleX = zoom.scale; scaleY = zoom.scale
                    translationX = zoom.offset.x; translationY = zoom.offset.y
                },
            )
        }
    }
}
