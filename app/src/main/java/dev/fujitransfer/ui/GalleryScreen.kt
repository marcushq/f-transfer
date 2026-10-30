package dev.fujitransfer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ScreenRotation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fujitransfer.Filters
import dev.fujitransfer.MainViewModel
import dev.fujitransfer.Photo
import dev.fujitransfer.Transfer
import dev.fujitransfer.UiState
import dev.fujitransfer.protocol.TransferSize
import dev.fujitransfer.visiblePhotos
import java.util.Calendar

/** "20261027" → "27.10". */
private fun shortDate(day: String) = "${day.substring(6, 8)}.${day.substring(4, 6)}"

/** "Hôm nay", "Hôm qua", else the weekday ("Thứ Hai" … "Chủ nhật"). */
private fun dayTitle(day: String): String {
    val cal = Calendar.getInstance().apply {
        clear(); set(day.substring(0, 4).toInt(), day.substring(4, 6).toInt() - 1, day.substring(6, 8).toInt())
    }
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val days = ((today.timeInMillis - cal.timeInMillis) / 86_400_000L).toInt()
    return when (days) {
        0 -> "Hôm nay"
        1 -> "Hôm qua"
        else -> when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "Thứ Hai"; Calendar.TUESDAY -> "Thứ Ba"; Calendar.WEDNESDAY -> "Thứ Tư"
            Calendar.THURSDAY -> "Thứ Năm"; Calendar.FRIDAY -> "Thứ Sáu"; Calendar.SATURDAY -> "Thứ Bảy"
            else -> "Chủ nhật"
        }
    }
}

/** One day's photos; [day] is null for photos whose date isn't read yet. */
private class DayGroup(val day: String?, val photos: List<Photo>)

private fun groupsOf(photos: List<Photo>): List<DayGroup> {
    val byDay = LinkedHashMap<String?, MutableList<Photo>>()
    for (p in photos) byDay.getOrPut(p.day) { mutableListOf() } += p
    val known = byDay.filterKeys { it != null }.map { DayGroup(it.key, it.value) }
    val unknown = byDay[null]?.let { listOf(DayGroup(null, it)) } ?: emptyList()
    return known + unknown
}

@Composable
fun GalleryScreen(ui: UiState, vm: MainViewModel, grid: LazyGridState = rememberLazyGridState(), onShowLog: () -> Unit) {
    val film = LocalFilm.current
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(ui.notice) {
        ui.notice?.let {
            snackbar.showSnackbar(it)
            vm.dismissNotice()
        }
    }
    val filters by vm.filters.collectAsStateWithLifecycle()
    val visible = remember(ui.photos, filters) { visiblePhotos(ui.photos, filters) }
    val sizeChoice by vm.transferSize.collectAsStateWithLifecycle()
    /** (already on the phone, selected) while the "download again?" question is up. */
    var askRedownload by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    askRedownload?.let { (already, chosen) ->
        RedownloadDialog(
            already = already,
            chosen = chosen,
            onNewOnly = { askRedownload = null; vm.downloadSelected(includeDownloaded = false) },
            onAll = { askRedownload = null; vm.downloadSelected(includeDownloaded = true) },
            onDismiss = { askRedownload = null },
        )
    }
    val pillShown = ui.transfer != null || ui.selected.isNotEmpty()

    Box(Modifier.fillMaxSize().paper()) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Header(ui, vm, onShowLog)
            if (ui.photos.isNotEmpty()) Chips(ui, filters, vm)
            Box(Modifier.weight(1f)) {
                when {
                    ui.photos.isEmpty() -> EmptyNote(
                        "Thẻ nhớ trống",
                        "Máy ảnh không trả về ảnh nào. Nếu thẻ nhớ có ảnh, hãy gửi nhật ký kết nối.",
                        action = "Xem nhật ký", onAction = onShowLog,
                    )
                    visible.isEmpty() -> EmptyNote(
                        if (filters.hideDownloaded && filters.day == null) "Đã tải hết" else "Không có ảnh nào",
                        if (filters.hideDownloaded && filters.day == null) "Mọi ảnh trên thẻ đã ở trong điện thoại."
                        else "Không có ảnh nào khớp bộ lọc.",
                    )
                    else -> PhotoGrid(ui, visible, vm, grid, bottomSpace = if (pillShown) 96 else 16)
                }
            }
        }

        // Floating action: download the selection, or the progress while it runs.
        Box(Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 16.dp)) {
            val t = ui.transfer
            if (t != null) {
                TransferPill(t, vm::cancelTransfer)
            } else if (ui.selected.isNotEmpty()) {
                DownloadPill(ui.selected.size, sizeChoice) {
                    val chosen = ui.photos.filter { it.handle in ui.selected }
                    val already = chosen.count { it.downloaded }
                    if (already > 0) askRedownload = already to chosen.size else vm.downloadSelected()
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = if (pillShown) 84.dp else 8.dp))
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val film = LocalFilm.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).border(1.dp, film.hairline, CircleShape).background(film.film, CircleShape),
    ) { Icon(icon, label, tint = if (enabled) film.ink else film.inkSoft, modifier = Modifier.size(20.dp)) }
}

@Composable
private fun Header(ui: UiState, vm: MainViewModel, onShowLog: () -> Unit) {
    val film = LocalFilm.current
    var menu by remember { mutableStateOf(false) }
    val convertHeif by vm.convertHeif.collectAsStateWithLifecycle()
    val rotateThumbs by vm.rotateThumbs.collectAsStateWithLifecycle()
    val transferSize by vm.transferSize.collectAsStateWithLifecycle()
    val unknown = ui.photos.count { it.info == null }

    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ui.cameraName.ifEmpty { "Máy ảnh" }, style = titleStyle, color = film.ink, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.size(7.dp).background(film.accent, CircleShape))
                Text(
                    when {
                        ui.selected.isNotEmpty() -> "ĐÃ CHỌN ${ui.selected.size} ẢNH"
                        unknown > 0 -> "ĐANG ĐỌC NGÀY CHỤP ${ui.photos.size - unknown}/${ui.photos.size}"
                        else -> "ĐÃ KẾT NỐI · ${"%,d".format(ui.total).replace(',', '.')} ẢNH"
                    },
                    style = captionStyle,
                    color = film.inkSoft,
                    maxLines = 1,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (ui.selected.isNotEmpty()) {
                TextButton(onClick = vm::clearSelection) { Text("Bỏ chọn", color = film.accentText) }
            } else {
                RoundIcon(Icons.Outlined.DoneAll, "Chọn ảnh mới", enabled = ui.transfer == null, onClick = vm::selectNotDownloaded)
            }
            Box {
                RoundIcon(Icons.Filled.MoreHoriz, "Thêm") { menu = true }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Chọn tất cả") }, onClick = { menu = false; vm.selectAll() })
                    ToggleItem("Chuyển HEIF sang JPEG", "Để mọi điện thoại mở được ảnh .HIF", convertHeif, enabled = ui.transfer == null) {
                        vm.setConvertHeif(!convertHeif)
                    }
                    ToggleItem("Xoay ảnh thu nhỏ theo hướng chụp", "Áp dụng cho ảnh tải thu nhỏ sau khi bật", rotateThumbs) {
                        vm.setRotateThumbs(!rotateThumbs)
                    }
                    HorizontalDivider(color = film.hairline, thickness = 0.5.dp)
                    Text(
                        "KÍCH THƯỚC KHI TẢI ẢNH JPEG",
                        style = captionStyle.copy(fontSize = 10.sp),
                        color = film.inkSoft,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    listOf(
                        Triple(TransferSize.ORIGINAL, "Ảnh gốc", "Đầy đủ chất lượng"),
                        Triple(TransferSize.SMALL, "Nhỏ (S)", "Máy ảnh thu nhỏ trước khi gửi · thử nghiệm"),
                        Triple(TransferSize.EXTRA_SMALL, "Rất nhỏ (XS)", "Nhẹ nhất · thử nghiệm"),
                    ).forEach { (option, title, hint) ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(title)
                                    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            },
                            trailingIcon = if (option == transferSize) ({ Icon(Icons.Filled.Check, null) }) else null,
                            onClick = { vm.setTransferSize(option) },
                            enabled = ui.transfer == null,
                        )
                    }
                    HorizontalDivider(color = film.hairline, thickness = 0.5.dp)
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text("Xoá bộ nhớ đệm ảnh")
                                Text(
                                    "%.1f MB · tên, ngày và ảnh thu nhỏ đã lưu".format(vm.cacheBytes() / 1_048_576.0),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = { menu = false; vm.clearCache() },
                        enabled = ui.transfer == null,
                    )
                    val context = LocalContext.current
                    DropdownMenuItem(
                        text = { Text("Khi tải ngầm: " + PipChoice.label(context)) },
                        onClick = {
                            val next = MiniMode.entries[(PipChoice.mode.ordinal + 1) % MiniMode.entries.size]
                            PipChoice.set(context, next)
                            // The bubble needs "Display over other apps": open that switch once.
                            if (next == MiniMode.Bubble && !dev.fujitransfer.Bubble.allowed(context)) {
                                context.startActivity(dev.fujitransfer.Bubble.permissionIntent(context))
                            }
                            // Android 16 with Live Updates switched off for the app: offer the switch.
                            if (next == MiniMode.Auto && dev.fujitransfer.LiveUpdates.canEnable(context)) {
                                try {
                                    context.startActivity(dev.fujitransfer.LiveUpdates.settingsIntent(context))
                                } catch (_: Exception) {
                                }
                            }
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Giao diện: ${ThemeChoice.mode.label}") },
                        onClick = { ThemeChoice.set(context, ThemeMode.entries[(ThemeChoice.mode.ordinal + 1) % ThemeMode.entries.size]) },
                    )
                    DropdownMenuItem(text = { Text("Nhật ký kết nối") }, onClick = { menu = false; onShowLog() })
                    DropdownMenuItem(text = { Text("Ngắt kết nối") }, onClick = { menu = false; vm.disconnect() })
                }
            }
        }
    }
}

@Composable
private fun ToggleItem(title: String, hint: String, checked: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column {
                Text(title)
                Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        trailingIcon = { Checkbox(checked = checked, onCheckedChange = null) },
        onClick = onClick,
        enabled = enabled,
    )
}

/** A pill chip in the design's style: light when on, dark outlined when off. */
@Composable
fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    val film = LocalFilm.current
    Box(
        Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) film.ink else film.film)
            .border(1.dp, if (selected) film.ink else film.hairline, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (selected) film.paper else film.chipText)
    }
}

@Composable
private fun Chips(ui: UiState, filters: Filters, vm: MainViewModel) {
    // Newest days first; each chip shows just that day.
    val days = remember(ui.photos) { ui.photos.mapNotNull { it.day }.distinct().sortedDescending().take(10) }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Pill("Tất cả", filters.day == null && !filters.hideDownloaded) {
            vm.setDayFilter(null)
            if (filters.hideDownloaded) vm.setHideDownloaded(false)
        }
        Pill("Chưa tải", filters.hideDownloaded) { vm.setHideDownloaded(!filters.hideDownloaded) }
        days.forEach { d ->
            Pill("${d.substring(6, 8)} Th${d.substring(4, 6).toInt()}", filters.day == d) {
                vm.setDayFilter(if (filters.day == d) null else d)
            }
        }
    }
}

@Composable
private fun PhotoGrid(ui: UiState, visible: List<Photo>, vm: MainViewModel, grid: LazyGridState, bottomSpace: Int) {
    val film = LocalFilm.current
    val groups = remember(visible) { groupsOf(visible) }
    val more = ui.photos.size < ui.total
    // Near the end of what's loaded: add the next pack. Re-checked whenever the list grows, so a
    // screen that still isn't full (e.g. many photos hidden by a filter) keeps filling up.
    LaunchedEffect(grid, ui.photos.size, ui.total) {
        snapshotFlow {
            val info = grid.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 4
        }.collect { nearEnd -> if (nearEnd && more) vm.loadMore() }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cols = (maxWidth.value / 120f).toInt().coerceAtLeast(3)
        LazyVerticalGrid(
            columns = GridCells.Fixed(cols),
            state = grid,
            contentPadding = PaddingValues(bottom = bottomSpace.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            for (g in groups) {
                val handles = g.photos.map { it.handle }
                val allSelected = handles.isNotEmpty() && handles.all { it in ui.selected }
                item(key = "head-${g.day}", span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 10.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(g.day?.let(::dayTitle) ?: "Chưa rõ ngày", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, color = film.ink)
                            Text(
                                (g.day?.let { shortDate(it) + " · " } ?: "") + "${g.photos.size} ẢNH",
                                style = captionStyle, color = film.inkSoft, modifier = Modifier.padding(bottom = 2.dp),
                            )
                        }
                        if (ui.transfer == null) {
                            val day = g.day
                            TextButton(
                                onClick = { if (day != null) vm.selectWholeDay(day) else vm.toggleRoll(handles) },
                                enabled = ui.selectingDay == null,
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            ) {
                                Text(
                                    when {
                                        day != null && ui.selectingDay == day -> "Đang tìm…"
                                        allSelected -> "Bỏ chọn"
                                        day == null -> "Chọn tất cả"
                                        else -> "Chọn cả ngày"
                                    },
                                    fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = film.accentText,
                                )
                            }
                        }
                    }
                }
                items(g.photos, key = { it.handle }) { photo ->
                    Tile(photo, selected = photo.handle in ui.selected) {
                        if (ui.transfer == null) vm.toggle(photo.handle)
                    }
                    LaunchedEffect(photo.handle) { if (photo.thumb == null) vm.requestThumb(photo.handle) }
                }
            }
            item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (more) "ĐÃ HIỆN ${ui.photos.size}/${ui.total} ẢNH" else "HẾT THẺ NHỚ · ${ui.total} ẢNH",
                        style = captionStyle,
                        color = film.inkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    if (more) TextButton(onClick = { vm.loadMore(100) }) { Text("Tải thêm 100", color = film.accentText) }
                }
            }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color, modifier: Modifier) {
    Text(
        text,
        style = captionStyle.copy(fontSize = 10.sp, letterSpacing = 0.3.sp),
        color = color,
        modifier = modifier
            .padding(6.dp)
            .background(Color(0xB80E0E0E), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun Tile(photo: Photo, selected: Boolean, onClick: () -> Unit) {
    val film = LocalFilm.current
    Box(Modifier.aspectRatio(1f).background(film.frameEmpty).clickable(onClick = onClick)) {
        val thumb = photo.thumb
        if (thumb != null) {
            Image(thumb, photo.info?.fileName, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            Text(
                photo.info?.fileName?.substringBeforeLast('.') ?: if (photo.attempted) "LỖI" else "···",
                style = captionStyle.copy(fontSize = 10.sp),
                color = film.inkSoft,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        val ext = photo.info?.fileName?.substringAfterLast('.', "")?.uppercase()
        if (ext != null && ext.isNotEmpty() && ext != "JPG") Badge(ext, OnPhoto, Modifier.align(Alignment.BottomStart))
        if (photo.downloaded) Badge("ĐÃ LƯU", OnPhotoSoft, Modifier.align(Alignment.BottomEnd))
        if (selected) {
            Box(Modifier.fillMaxSize().border(3.dp, film.accent))
            Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).background(film.accent, CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Check, "Đã chọn", tint = film.onAccent, modifier = Modifier.size(14.dp)) }
        } else if (photo.oriented && photo.thumb != null) {
            // Details read and turned as shot: nothing left to fetch for this frame.
            Icon(
                Icons.Outlined.ScreenRotation, "Đã đọc đủ thông tin và xoay đúng hướng",
                tint = OnPhoto,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color(0x990E0E0E), CircleShape)
                    .padding(3.dp)
                    .size(11.dp),
            )
        }
    }
}

@Composable
private fun DownloadPill(count: Int, size: TransferSize, onClick: () -> Unit) {
    val film = LocalFilm.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(56.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(film.accent)
            .clickable(onClickLabel = "Tải ảnh đã chọn", onClick = onClick)
            .padding(start = 22.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Tải $count ảnh về máy", fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = film.onAccent)
            Text(
                when (size) {
                    TransferSize.ORIGINAL -> "ẢNH GỐC"
                    TransferSize.SMALL -> "CỠ S · MÁY ẢNH THU NHỎ"
                    TransferSize.EXTRA_SMALL -> "CỠ XS · MÁY ẢNH THU NHỎ"
                },
                style = captionStyle.copy(fontSize = 10.sp),
                color = film.onAccent.copy(alpha = 0.72f),
            )
        }
        Box(Modifier.size(40.dp).background(film.onAccent, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Download, null, tint = film.accent, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TransferPill(t: Transfer, onCancel: () -> Unit) {
    val film = LocalFilm.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(film.film)
            .border(1.dp, film.hairline, RoundedCornerShape(24.dp))
            .padding(start = 20.dp, end = 10.dp, top = 12.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("ĐANG TẢI ${t.index + 1}/${t.total}", style = captionStyle, color = film.accentText)
                Text(
                    t.fileName + if (t.bytesPerSecond > 0) "  ·  %.1f MB/s".format(t.bytesPerSecond / 1_048_576) else "",
                    fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = film.ink,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(onClick = onCancel, shape = RoundedCornerShape(20.dp), border = BorderStroke(1.dp, film.hairline)) {
                Text("Dừng", color = film.ink)
            }
        }
        Spacer(Modifier.height(10.dp))
        val fileFrac = if (t.fileTotal > 0) t.fileDone.toFloat() / t.fileTotal else 0f
        LinearProgressIndicator(
            progress = { (t.index + fileFrac) / t.total },
            color = film.accent,
            trackColor = film.hairline,
            modifier = Modifier.fillMaxWidth().padding(end = 10.dp),
        )
    }
}

/** Asked when the selection includes photos this phone already has. */
@Composable
fun RedownloadDialog(already: Int, chosen: Int, onNewOnly: () -> Unit, onAll: () -> Unit, onDismiss: () -> Unit) {
    val film = LocalFilm.current
    val fresh = chosen - already
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = film.film,
        title = { Text(if (fresh > 0) "Có ảnh đã tải rồi" else "Đã tải hết ảnh này", fontFamily = Sans, fontWeight = FontWeight.Bold, color = film.ink) },
        text = {
            Text(
                if (fresh > 0) "$already trong $chosen ảnh đã chọn đã có trong điện thoại. Bạn muốn tải lại những ảnh đó không?"
                else "Cả $chosen ảnh đã chọn đều đã có trong điện thoại. Tải lại sẽ tạo thêm một bản nữa trong thư viện ảnh.",
                color = film.inkSoft,
            )
        },
        confirmButton = {
            if (fresh > 0) {
                Button(
                    onClick = onNewOnly,
                    shape = RoundedCornerShape(20.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = film.accent, contentColor = film.onAccent),
                ) { Text("Chỉ tải $fresh ảnh mới") }
            } else {
                TextButton(onClick = onAll) { Text("Tải lại $chosen ảnh", color = film.accentText) }
            }
        },
        dismissButton = {
            Row {
                if (fresh > 0) TextButton(onClick = onAll) { Text("Tải lại cả $chosen", color = film.ink) }
                TextButton(onClick = onDismiss) { Text("Huỷ", color = film.inkSoft) }
            }
        },
    )
}

@Composable
private fun EmptyNote(title: String, body: String, action: String? = null, onAction: () -> Unit = {}) {
    val film = LocalFilm.current
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 22.sp, color = film.ink)
        Spacer(Modifier.height(6.dp))
        Text(body, color = film.inkSoft, style = MaterialTheme.typography.bodyMedium)
        if (action != null) TextButton(onClick = onAction) { Text(action, color = film.accentText) }
    }
}

/** The perforated edge of a film strip (preparing screen). */
@Composable
fun Sprockets(top: Boolean) {
    val film = LocalFilm.current
    val shape = if (top) RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp) else RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)
    Canvas(Modifier.fillMaxWidth().height(14.dp).clip(shape).background(film.film)) {
        val hole = Size(7.dp.toPx(), 5.dp.toPx())
        val pitch = 14.dp.toPx()
        var x = pitch / 2 - hole.width / 2
        while (x + hole.width < size.width) {
            drawRoundRect(
                color = film.sprocket,
                topLeft = Offset(x, (size.height - hole.height) / 2),
                size = hole,
                cornerRadius = CornerRadius(1.5.dp.toPx()),
            )
            x += pitch
        }
    }
}
