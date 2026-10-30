package dev.fujitransfer.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.fujitransfer.MainViewModel
import dev.fujitransfer.Phase
import dev.fujitransfer.SavedViewModel
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.IconButton
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun App(vm: MainViewModel, saved: SavedViewModel, pip: Boolean = false) {
    val context = LocalContext.current
    remember { ThemeChoice.load(context) }
    FilmTheme {
        val ui by vm.ui.collectAsStateWithLifecycle()
        if (pip) {
            PipView(ui)
            return@FilmTheme
        }
        // The progress notification needs this on Android 13+; asked once, at the first transfer.
        val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(ui.transfer != null) {
            if (ui.transfer != null && android.os.Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                val prefs = context.getSharedPreferences("look", 0)
                if (!prefs.getBoolean("askedNotify", false)) {
                    prefs.edit().putBoolean("askedNotify", true).apply()
                    notify.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        val savedUi by saved.ui.collectAsStateWithLifecycle()
        var showLog by remember { mutableStateOf(false) }
        var tab by rememberSaveable { mutableIntStateOf(0) }
        // Scroll positions live here, so opening a photo or switching tabs doesn't lose them.
        val savedGrid = rememberLazyGridState()
        val cameraGrid = rememberLazyGridState()
        val film = LocalFilm.current

        // Keep the screen (and with it Wi-Fi) awake while photos are moving.
        val view = LocalView.current
        val busy = ui.transfer != null || ui.preparing != null
        LaunchedEffect(busy) { view.keepScreenOn = busy }
        // A finished transfer adds photos to "Đã lưu".
        LaunchedEffect(ui.transfer == null) { if (ui.transfer == null) saved.refresh() }

        val viewing = savedUi.viewing
        val inViewer = tab == 1 && viewing != null && savedUi.shown.isNotEmpty()
        // Status and navigation bar icons: light on the dark look and in the (always black) viewer.
        val darkBars = film.dark || inViewer
        LaunchedEffect(darkBars) {
            val bars = if (darkBars) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
            else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
            (context as? ComponentActivity)?.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
        }
        if (inViewer && viewing != null) {
            PhotoViewer(savedUi.shown, viewing, saved)
        } else {
            Column(Modifier.fillMaxSize().paper()) {
                // The tab bar takes the navigation-bar inset; screens above it must not add it again.
                Box(Modifier.weight(1f).consumeWindowInsets(WindowInsets.navigationBars)) {
                    if (tab == 1) {
                        SavedScreen(saved, savedGrid)
                    } else {
                        val preparing = ui.preparing
                        if (ui.phase == Phase.Connected && preparing != null) {
                            PreparingScreen(ui.cameraName, preparing, onSkip = vm::skipPreparing)
                        } else if (ui.phase == Phase.Connected) {
                            GalleryScreen(ui, vm, cameraGrid, onShowLog = { showLog = true })
                        } else {
                            ConnectScreen(ui, vm, onOpenSaved = { tab = 1; saved.refresh() }, onShowLog = { showLog = true })
                        }
                    }
                }
                HorizontalDivider(color = film.raised, thickness = 1.dp)
                NavigationBar(containerColor = film.bar, tonalElevation = 0.dp) {
                    val itemColors = NavigationBarItemDefaults.colors(
                        selectedIconColor = film.accent, selectedTextColor = film.ink, indicatorColor = film.raised,
                        unselectedIconColor = film.inkSoft, unselectedTextColor = film.inkSoft,
                    )
                    NavigationBarItem(
                        selected = tab == 0, onClick = { tab = 0 },
                        icon = { Icon(Icons.Outlined.CameraAlt, null) },
                        label = { Text(if (ui.phase == Phase.Connected) ui.cameraName.ifEmpty { "Máy ảnh" } else "Máy ảnh") },
                        colors = itemColors,
                    )
                    NavigationBarItem(
                        selected = tab == 1, onClick = { tab = 1; saved.refresh() },
                        icon = { Icon(Icons.Outlined.PhotoLibrary, null) },
                        label = { Text("Đã lưu") },
                        colors = itemColors,
                    )
                }
            }
        }
        if (showLog) LogSheet(vm, onDismiss = { showLog = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogSheet(vm: MainViewModel, onDismiss: () -> Unit) {
    val lines by vm.log.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Nhật ký kết nối", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                val uri = vm.exportLog()
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(send, "Gửi nhật ký"))
            }) { Text("Chia sẻ") }
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (lines.isEmpty()) item { Text("Chưa có gì.", style = MaterialTheme.typography.bodySmall) }
            items(lines) { line ->
                Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
    }
}

/** Sun or moon: switches between the light and dark look (the menu also offers "follow the phone"). */
@Composable
fun ThemeButton() {
    val film = LocalFilm.current
    val context = LocalContext.current
    IconButton(
        onClick = { ThemeChoice.toggle(context, film.dark) },
        modifier = Modifier.size(44.dp).border(1.dp, film.hairline, CircleShape).background(film.film, CircleShape),
    ) {
        Icon(
            if (film.dark) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
            if (film.dark) "Giao diện sáng" else "Giao diện tối",
            tint = film.ink, modifier = Modifier.size(20.dp),
        )
    }
}
