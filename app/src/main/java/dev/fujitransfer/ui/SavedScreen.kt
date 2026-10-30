package dev.fujitransfer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fujitransfer.SavedPhoto
import dev.fujitransfer.SavedUi
import dev.fujitransfer.SavedViewModel
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.US)
private val monthKey = SimpleDateFormat("yyyyMM", Locale.US)

/** "Tháng 10, 2026". */
private fun monthTitle(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    return "Tháng ${c.get(Calendar.MONTH) + 1}, ${c.get(Calendar.YEAR)}"
}

/** "Hôm nay", "Hôm qua", else "27 Th10". */
private fun dayName(ms: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = ms }
    val key = dayKey.format(Date(ms))
    val today = dayKey.format(Date())
    val yesterday = dayKey.format(Date(System.currentTimeMillis() - 86_400_000L))
    return when (key) {
        today -> "Hôm nay"
        yesterday -> "Hôm qua"
        else -> "${c.get(Calendar.DAY_OF_MONTH)} Th${c.get(Calendar.MONTH) + 1}"
    }
}

/** The "Đã lưu" tab: what's already on the phone, newest first. Pull down to read it again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedScreen(vm: SavedViewModel, grid: LazyGridState = rememberLazyGridState()) {
    val film = LocalFilm.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Without this, Android only lists photos saved since the app was (re)installed.
    val permission = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED)
    }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        vm.refresh()
    }
    // Back from another app (a gallery, a file manager): photos may have come or gone.
    LifecycleResumeEffect(Unit) { vm.refresh(); onPauseOrDispose { } }

    val shown = ui.shown
    Column(Modifier.fillMaxSize().paper().statusBarsPadding()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Đã lưu", style = titleStyle, color = film.ink, modifier = Modifier.weight(1f))
                ThemeButton()
            }
            Text(
                "${"%,d".format(ui.all.size).replace(',', '.')} ẢNH · " +
                    "%.1f GB".format(ui.all.sumOf { it.bytes } / 1_073_741_824.0).replace('.', ',') + " TRÊN ĐIỆN THOẠI",
                style = captionStyle,
                color = film.inkSoft,
            )
        }

        val kinds = remember(ui.all) { listOf(SavedUi.ALL) + listOf("JPEG", "HEIF", "RAF").filter { k -> ui.all.any { it.kind == k } } }
        if (kinds.size > 2) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                kinds.forEach { k -> Pill(k, ui.filter == k) { vm.setFilter(k) } }
            }
        }

        val pull = rememberPullToRefreshState()
        PullToRefreshBox(
            isRefreshing = ui.refreshing,
            onRefresh = { vm.refresh(pulled = true) },
            state = pull,
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = pull, isRefreshing = ui.refreshing, containerColor = film.film, color = film.accent,
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            },
        ) {
            when {
                ui.loading -> Unit
                shown.isEmpty() -> Column(
                    // Scrollable so the pull gesture works on an empty tab too.
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Chưa có ảnh nào", fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = film.ink)
                    Spacer(Modifier.height(6.dp))
                    Text("Ảnh tải từ máy ảnh sẽ hiện ở đây.", color = film.inkSoft)
                    if (!granted) TextButton(onClick = { ask.launch(permission) }) { Text("Xem cả ảnh lưu trước đây", color = film.accentText) }
                }
                else -> SavedGrid(shown, vm, granted, grid) { ask.launch(permission) }
            }
        }
    }
}

@Composable
private fun SavedGrid(shown: List<SavedPhoto>, vm: SavedViewModel, granted: Boolean, grid: LazyGridState, onAsk: () -> Unit) {
    val film = LocalFilm.current
    // Month sections, newest first; each photo keeps its index in [shown] for the viewer.
    val months = remember(shown) { shown.withIndex().groupBy { monthKey.format(Date(it.value.takenMs)) }.toList() }
    // The latest import: every photo that arrived on the phone on the newest day, whenever it was shot.
    val latest = remember(shown) { latestImport(shown) }
    // Back from the viewer: if it was swiped to a photo now out of sight, bring that one into view.
    LaunchedEffect(Unit) {
        val photo = vm.takeLastViewed() ?: return@LaunchedEffect
        var at = (if (latest.isNotEmpty()) 1 else 0) + (if (!granted) 1 else 0)
        var target = -1
        for ((_, photos) in months) {
            at++ // the month heading
            val pos = photos.indexOfFirst { it.index == photo }
            if (pos >= 0) { target = at + pos; break }
            at += photos.size
        }
        if (target < 0) return@LaunchedEffect
        snapshotFlow { grid.layoutInfo.totalItemsCount }.first { it > target }
        val info = grid.layoutInfo
        val seen = info.visibleItemsInfo.firstOrNull { it.index == target }
        if (seen == null || seen.offset.y < 0 || seen.offset.y + seen.size.height > info.viewportEndOffset) grid.scrollToItem(target)
    }
    LazyVerticalGrid(
        state = grid,
        columns = GridCells.Fixed(4),
        contentPadding = PaddingValues(bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (latest.isNotEmpty()) {
            item(key = "latest", span = { GridItemSpan(maxLineSpan) }) { LatestCard(latest, vm) }
        }
        if (!granted) {
            item(key = "access", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp))
                        .background(film.film).padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Đang hiện ảnh lưu từ lần cài app này.",
                        style = MaterialTheme.typography.bodySmall, color = film.inkSoft, modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onAsk) { Text("Xem cả ảnh cũ", color = film.accentText) }
                }
            }
        }
        for ((key, photos) in months) {
            item(key = "m-$key", span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 10.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    Text(monthTitle(photos.first().value.takenMs), fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = film.ink, modifier = Modifier.weight(1f))
                    Text("${photos.size} ẢNH", style = captionStyle, color = film.inkSoft)
                }
            }
            items(photos, key = { it.value.id }) { (index, p) -> SavedTile(p, vm) { vm.open(index) } }
        }
    }
}

@Composable
private fun SavedTile(p: SavedPhoto, vm: SavedViewModel, modifier: Modifier = Modifier.aspectRatio(1f), onClick: () -> Unit) {
    val film = LocalFilm.current
    val thumb = vm.thumbs[p.id]
    LaunchedEffect(p.id) { vm.requestThumb(p) }
    Box(modifier.background(film.frameEmpty).clickable(onClickLabel = "Mở ${p.name}", onClick = onClick)) {
        if (thumb != null) Image(thumb, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        if (p.kind != "JPEG") {
            Text(
                p.kind,
                style = captionStyle.copy(fontSize = 9.sp, letterSpacing = 0.3.sp),
                color = OnPhoto,
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                    .background(Color(0xB80E0E0E), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/**
 * The photos that arrived on the phone on the newest import day, each with its index in [shown].
 * Grouped by when they were saved, not shot: a card copied today with last month's photos still
 * counts as today's import.
 */
internal fun latestImport(shown: List<SavedPhoto>): List<IndexedValue<SavedPhoto>> {
    val newest = shown.maxOfOrNull { it.addedMs } ?: return emptyList()
    val key = dayKey.format(Date(newest))
    return shown.withIndex().filter { dayKey.format(Date(it.value.addedMs)) == key }
}

/**
 * "Lần nhập gần nhất": every photo of the latest import, scrolling sideways — the first one
 * large, the rest in two rows. Tapping a photo opens it.
 */
@Composable
private fun LatestCard(latest: List<IndexedValue<SavedPhoto>>, vm: SavedViewModel) {
    val film = LocalFilm.current
    val first = latest.first()
    Column(
        Modifier.padding(horizontal = 20.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(film.film)
            .border(1.dp, film.raised, RoundedCornerShape(20.dp)),
    ) {
        LazyHorizontalGrid(
            rows = GridCells.Fixed(2),
            modifier = Modifier.fillMaxWidth().height(194.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = first.value.id, span = { GridItemSpan(maxLineSpan) }) {
                SavedTile(first.value, vm, Modifier.size(194.dp)) { vm.open(first.index) }
            }
            items(latest.drop(1), key = { it.value.id }) { (i, p) ->
                SavedTile(p, vm, Modifier.size(96.dp)) { vm.open(i) }
            }
        }
        Row(
            Modifier.fillMaxWidth().clickable { vm.open(first.index) }.padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("LẦN NHẬP GẦN NHẤT", style = captionStyle, color = film.accentText)
                Text("${dayName(first.value.addedMs)} · ${latest.size} ảnh", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, color = film.ink)
            }
            if (latest.size > 3) Text("VUỐT ĐỂ XEM HẾT", style = captionStyle, color = film.inkSoft, modifier = Modifier.padding(end = 10.dp))
            Box(Modifier.size(36.dp).background(film.raised, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = film.ink, modifier = Modifier.size(20.dp))
            }
        }
    }
}
