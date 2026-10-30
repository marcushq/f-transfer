package dev.fujitransfer.ui

import dev.fujitransfer.R
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.Image
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.fujitransfer.ConnectMode
import dev.fujitransfer.MainViewModel
import dev.fujitransfer.Phase
import dev.fujitransfer.UiState

private fun permissionsFor(mode: ConnectMode): Array<String> = buildList {
    if (mode == ConnectMode.Bluetooth) {
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }
    if (mode != ConnectMode.WifiCurrent) {
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
        else add(Manifest.permission.ACCESS_FINE_LOCATION)
    }
}.toTypedArray()

private class Step(val title: String, val hint: String)

/** What happens, in order, for each way of connecting; the hints double as instructions. */
private fun stepsFor(mode: ConnectMode) = when (mode) {
    ConnectMode.Bluetooth -> listOf(
        Step("Tìm máy ảnh qua Bluetooth", "Lần đầu: MENU › CÀI ĐẶT MẠNG/USB › CÀI ĐẶT Bluetooth/ĐIỆN THOẠI › ĐĂNG KÝ GHÉP ĐÔI"),
        Step("Bật Wi-Fi của máy ảnh", "App tự nhờ máy ảnh bật Wi-Fi"),
        Step("Mở thẻ nhớ", "Ảnh đã đọc trước đây hiện ra ngay"),
    )
    ConnectMode.WifiAuto -> listOf(
        Step("Mở kết nối không dây trên máy", "Bấm ▶ › MENU › KẾT NỐI KHÔNG DÂY"),
        Step("Vào Wi-Fi FUJIFILM-…", "Android sẽ hỏi chọn mạng của máy ảnh"),
        Step("Mở thẻ nhớ", "Ảnh đã đọc trước đây hiện ra ngay"),
    )
    ConnectMode.WifiCurrent -> listOf(
        Step("Vào Wi-Fi máy ảnh trong Cài đặt", "Khi Android báo \"không có Internet\", chọn giữ kết nối"),
        Step("Mở thẻ nhớ", "Ảnh đã đọc trước đây hiện ra ngay"),
    )
}

/** Which step a connection in [phase] is on. */
private fun activeStep(mode: ConnectMode, phase: Phase): Int = when (phase) {
    Phase.Idle -> -1
    Phase.Bluetooth, Phase.Pairing -> 0
    Phase.Joining -> if (mode == ConnectMode.Bluetooth) 1 else 0
    else -> stepsFor(mode).size - 1
}

@Composable
fun ConnectScreen(ui: UiState, vm: MainViewModel, onOpenSaved: () -> Unit = {}, onShowLog: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(ConnectMode.Bluetooth) }
    var ssid by rememberSaveable { mutableStateOf("FUJIFILM") }
    var password by rememberSaveable { mutableStateOf("") }
    var denied by rememberSaveable { mutableStateOf(false) }
    val busy = ui.phase != Phase.Idle

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val bleDenied = mode == ConnectMode.Bluetooth && Build.VERSION.SDK_INT >= 31 &&
            (result[Manifest.permission.BLUETOOTH_SCAN] == false || result[Manifest.permission.BLUETOOTH_CONNECT] == false)
        denied = bleDenied
        // Wi-Fi permissions are best effort: Android may still find the network without them.
        if (!bleDenied) vm.connect(mode, ssid.trim(), password)
    }

    val film = LocalFilm.current
    Column(
        Modifier
            .fillMaxSize()
            .paper()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("FUJI TRANSFER", style = captionStyle.copy(letterSpacing = 1.3.sp), color = film.inkSoft, modifier = Modifier.weight(1f))
            ThemeButton()
        }
        Text(
            "Kết nối máy ảnh",
            fontFamily = Sans, fontWeight = FontWeight.Bold, fontSize = 36.sp, lineHeight = 40.sp, letterSpacing = (-1).sp,
            maxLines = 1, softWrap = false,
            color = film.ink, modifier = Modifier.padding(top = 12.dp),
        )

        // The camera card.
        Box(
            Modifier
                .padding(top = 24.dp)
                .fillMaxWidth()
                .height(200.dp)
                .clip(RoundedCornerShape(28.dp))
                .background(film.film)
                .border(1.dp, film.raised, RoundedCornerShape(28.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // A black body vanishes on the dark card: light it from behind.
            if (film.dark) Box(
                Modifier.padding(bottom = 26.dp).size(260.dp, 170.dp)
                    .background(Brush.radialGradient(listOf(Color(0xFF3A3A3A), Color.Transparent))),
            )
            // Product photo: fujifilm-x.com (X-S20 page).
            Image(
                painterResource(R.drawable.camera_xs20), "Fujifilm X-S20",
                contentScale = ContentScale.Fit,
                modifier = Modifier.padding(bottom = 26.dp).height(124.dp),
            )
            if (busy) LinearProgressIndicator(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(2.dp),
                color = film.accent, trackColor = Color.Transparent,
            )
            Row(
                Modifier.align(Alignment.BottomStart).padding(14.dp).background(film.paper, RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(Modifier.size(7.dp).background(if (busy) film.accent else film.inkSoft, CircleShape))
                Text(
                    when (mode) {
                        ConnectMode.Bluetooth -> "BLUETOOTH · X-S20, X-T5, X100VI…"
                        ConnectMode.WifiAuto -> "WI-FI · X-S10, X-T4, X100V…"
                        ConnectMode.WifiCurrent -> "WI-FI ĐÃ KẾT NỐI"
                    },
                    style = captionStyle.copy(fontSize = 10.sp),
                    color = film.chipText,
                )
            }
        }

        // How to connect.
        Row(
            Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(
                ConnectMode.Bluetooth to "Máy đời mới",
                ConnectMode.WifiAuto to "Máy đời cũ",
                ConnectMode.WifiCurrent to "Đã vào Wi-Fi",
            ).forEach { (m, label) -> Pill(label, mode == m) { if (!busy) mode = m } }
        }

        // The steps, which show progress once connecting.
        val steps = stepsFor(mode)
        val active = activeStep(mode, ui.phase)
        Column(Modifier.padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            steps.forEachIndexed { i, s ->
                val state = when {
                    active < 0 -> 0
                    i < active -> 2
                    i == active -> 1
                    else -> 0
                }
                val hint = if (state == 1) {
                    ui.status + when (ui.phase) {
                        Phase.WaitingForOk -> "\nMàn hình máy ảnh đang hỏi có cho phép điện thoại này không."
                        Phase.Pairing -> "\nMã trên điện thoại và máy ảnh phải giống nhau."
                        Phase.PreparingCard -> "\nThẻ nhớ mới hoặc nhiều ảnh có thể mất 1–2 phút."
                        else -> ""
                    }
                } else s.hint
                StepRow(i + 1, s.title, hint, state)
            }
        }

        if (mode == ConnectMode.WifiAuto) {
            val fieldColors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = film.accent, unfocusedBorderColor = film.hairline,
                focusedLabelColor = film.accentText, unfocusedLabelColor = film.inkSoft,
                focusedTextColor = film.ink, unfocusedTextColor = film.ink, cursorColor = film.accent,
            )
            OutlinedTextField(
                value = ssid, onValueChange = { ssid = it },
                label = { Text("Tên Wi-Fi bắt đầu bằng") },
                singleLine = true, enabled = !busy, colors = fieldColors, shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it },
                label = { Text("Mật khẩu Wi-Fi (nếu máy ảnh có)") },
                singleLine = true, enabled = !busy, colors = fieldColors, shape = RoundedCornerShape(14.dp),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        }

        if (denied) {
            Text(
                "Cần quyền \"Thiết bị ở gần\" để tìm máy ảnh qua Bluetooth. Hãy cho phép trong Cài đặt › Ứng dụng › Fuji Transfer › Quyền.",
                color = film.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp),
            )
        }
        ui.error?.let { err ->
            Row(
                Modifier.padding(top = 18.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(film.errorBg).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Outlined.ErrorOutline, null, tint = film.error)
                Text(err, color = film.errorText, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(28.dp))
        if (busy) {
            PillButton("Huỷ", primary = false, onClick = vm::disconnect)
        } else {
            PillButton("Kết nối", primary = true) {
                val perms = permissionsFor(mode)
                if (perms.isEmpty()) vm.connect(mode, ssid.trim(), password) else launcher.launch(perms)
            }
            Spacer(Modifier.height(10.dp))
            PillButton("Xem ảnh đã lưu", primary = false, onClick = onOpenSaved)
        }
        TextButton(onClick = onShowLog, modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp)) {
            Text("Xem nhật ký kết nối", color = film.inkSoft)
        }
    }
}

/** state: 0 = waiting, 1 = in progress, 2 = done. */
@Composable
private fun StepRow(number: Int, title: String, hint: String, state: Int) {
    val film = LocalFilm.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        val badge = Modifier.size(32.dp).clip(CircleShape)
        Box(
            when (state) {
                2 -> badge.background(film.accent)
                1 -> badge.border(2.dp, film.accent, CircleShape)
                else -> badge.border(1.dp, film.hairline, CircleShape)
            },
            contentAlignment = Alignment.Center,
        ) {
            if (state == 2) Icon(Icons.Filled.Check, null, tint = film.onAccent, modifier = Modifier.size(16.dp))
            else Text("$number", fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 13.sp, color = if (state == 1) film.accentText else film.inkSoft)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, color = if (state == 0 && number > 1) film.inkSoft else film.ink)
            Text(hint, fontSize = 12.sp, color = film.inkSoft, lineHeight = 16.sp)
        }
    }
}

/** The design's 56 dp pill: lime when [primary], outlined otherwise. */
@Composable
fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val film = LocalFilm.current
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (primary) 56.dp else 52.dp)
            .clip(RoundedCornerShape(28.dp))
            .then(if (primary) Modifier.background(film.accent) else Modifier.border(1.dp, film.hairline, RoundedCornerShape(28.dp)))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            fontFamily = Sans,
            fontWeight = if (primary) FontWeight.Bold else FontWeight.Medium,
            fontSize = if (primary) 16.sp else 15.sp,
            color = if (primary) film.onAccent else film.ink,
        )
    }
}
