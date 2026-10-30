package dev.fujitransfer.ui

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import dev.fujitransfer.UiState


/**
 * What leaving the app mid-transfer leaves on screen. The progress notification asks to be a
 * Live Update (status-bar chip / island) in every mode; [Auto] adds the floating window only
 * where the phone doesn't show Live Updates.
 */
enum class MiniMode(val label: String) { Auto("Tự động"), Window("Cửa sổ"), Bubble("Bong bóng"), Off("Chỉ thông báo") }

/** The chosen [MiniMode], kept between launches. */
object PipChoice {
    private var loaded = false
    var mode by mutableStateOf(MiniMode.Auto)
        private set

    /**
     * The picture-in-picture window is used: chosen, or Auto on a phone without Live Updates,
     * or the bubble without its permission.
     */
    fun usesWindow(context: Context) = when (mode) {
        MiniMode.Window -> true
        MiniMode.Auto -> !dev.fujitransfer.LiveUpdates.shownBySystem(context)
        MiniMode.Bubble -> !dev.fujitransfer.Bubble.allowed(context)
        MiniMode.Off -> false
    }

    /** "Tự động (đảo)" or "Tự động (cửa sổ)": what Auto turns into on this phone. */
    fun label(context: Context) =
        if (mode == MiniMode.Auto) mode.label + if (dev.fujitransfer.LiveUpdates.shownBySystem(context)) " (đảo)" else " (cửa sổ)"
        else mode.label

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences("look", 0)
        mode = MiniMode.entries.firstOrNull { it.name == p.getString("mini", null) }
            ?: if (p.getBoolean("pip", true)) MiniMode.Auto else MiniMode.Off
    }

    fun set(context: Context, m: MiniMode) {
        mode = m
        context.getSharedPreferences("look", 0).edit().putString("mini", m.name).apply()
    }
}

/**
 * The floating window while photos come in, shaped like the transfer bar in the app:
 * "ĐANG TẢI 3/12", the file and speed, and a progress bar. The window is as flat as Android
 * allows (2.39:1) so it can sit along the top edge; everything scales with its height.
 */
@Composable
fun PipView(ui: UiState) {
    val film = LocalFilm.current
    val t = ui.transfer
    BoxWithConstraints(Modifier.fillMaxSize().background(film.film), contentAlignment = Alignment.CenterStart) {
        val h = maxHeight
        // Sized from the window, not the font setting, so it always fits.
        val caption = with(LocalDensity.current) { (h * 0.12f).toSp() }
        val title = with(LocalDensity.current) { (h * 0.19f).toSp() }
        Column(
            Modifier.fillMaxWidth().padding(horizontal = h * 0.17f),
            verticalArrangement = Arrangement.spacedBy(h * 0.06f),
        ) {
            if (t == null) {
                Text("XONG", fontFamily = Mono, fontSize = caption, letterSpacing = 0.6.sp, color = film.accentText, maxLines = 1)
                Text(
                    ui.notice ?: ui.error ?: "Đã dừng", fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = title,
                    color = film.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = title * 1.15f,
                )
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "ĐANG TẢI ${t.index + 1}/${t.total}", fontFamily = Mono, fontSize = caption, letterSpacing = 0.6.sp,
                    color = film.accentText, maxLines = 1, modifier = Modifier.weight(1f),
                )
                if (t.bytesPerSecond > 0) {
                    Text(
                        "%.1f MB/s".format(t.bytesPerSecond / 1_048_576).replace('.', ','),
                        fontFamily = Mono, fontSize = caption, color = film.inkSoft, maxLines = 1,
                    )
                }
            }
            Text(
                t.fileName, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = title, color = film.ink,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val part = if (t.fileTotal > 0) t.fileDone.toFloat() / t.fileTotal else 0f
            val progress by animateFloatAsState(((t.index + part) / t.total.coerceAtLeast(1)).coerceIn(0f, 1f), label = "bar")
            LinearProgressIndicator(
                progress = { progress },
                color = film.accent, trackColor = film.hairline,
                modifier = Modifier.padding(top = h * 0.03f).fillMaxWidth().height(h * 0.05f).clip(RoundedCornerShape(50)),
            )
        }
    }
}
