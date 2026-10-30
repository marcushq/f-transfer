package dev.fujitransfer.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fujitransfer.Preparing

/**
 * First visit with a card: every photo's details, orientation and thumbnail are read once and
 * kept on the phone. Shows how far along it is, and the frames as they come in.
 */
@Composable
fun PreparingScreen(cameraName: String, p: Preparing, onSkip: () -> Unit) {
    val film = LocalFilm.current
    Column(
        Modifier.fillMaxSize().paper().safeDrawingPadding().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("FUJIFILM · ${cameraName.uppercase()}", style = captionStyle, color = film.accentText)
        Text("Đang chuẩn bị\nthư viện", style = titleStyle, color = film.ink, lineHeight = 34.sp)
        Text(
            "Lần đầu với thẻ nhớ này, app đọc ngày chụp, hướng chụp và ảnh thu nhỏ của từng ảnh rồi lưu vào điện thoại. Lần sau thư viện sẽ hiện ra ngay.",
            color = film.inkSoft,
            style = MaterialTheme.typography.bodyMedium,
        )

        Spacer(Modifier.height(8.dp))
        // The latest frames, on a strip of film.
        Column(Modifier.fillMaxWidth()) {
            Sprockets(top = true)
            Row(Modifier.fillMaxWidth().background(film.film).padding(horizontal = 2.dp)) {
                repeat(6) { i ->
                    val bmp = p.recent.getOrNull(p.recent.size - 6 + i)
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).background(film.frameEmpty)) {
                        if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
            Sprockets(top = false)
        }

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${p.done}", fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 40.sp, color = film.ink)
            Text(" / ${p.total} ảnh", fontFamily = Mono, fontSize = 16.sp, color = film.inkSoft, modifier = Modifier.padding(bottom = 6.dp))
        }
        LinearProgressIndicator(
            progress = { if (p.total == 0) 0f else p.done.toFloat() / p.total },
            color = film.accent,
            trackColor = film.hairline,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            p.secondsLeft?.let { "CÒN KHOẢNG " + formatLeft(it) } ?: "ĐANG ƯỚC TÍNH THỜI GIAN…",
            style = captionStyle,
            color = film.inkSoft,
        )

        Spacer(Modifier.weight(1f))
        PillButton("Xem ảnh ngay", primary = false, onClick = onSkip)
        Text(
            "Phần còn lại sẽ được đọc tiếp những lúc app rảnh. Giữ máy ảnh bật và ở gần điện thoại.",
            style = MaterialTheme.typography.bodySmall,
            color = film.inkSoft,
        )
    }
}

private fun formatLeft(s: Int) = when {
    s < 60 -> "$s GIÂY"
    s < 3600 -> "${(s + 30) / 60} PHÚT"
    else -> "${s / 3600} GIỜ ${(s % 3600) / 60} PHÚT"
}
