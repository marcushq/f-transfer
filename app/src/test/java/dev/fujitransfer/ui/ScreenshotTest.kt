package dev.fujitransfer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fujitransfer.MainViewModel
import dev.fujitransfer.Phase
import dev.fujitransfer.Photo
import dev.fujitransfer.Transfer
import dev.fujitransfer.UiState
import dev.fujitransfer.protocol.ObjectInfo
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the screens on the JVM so the film layout can be checked without a phone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h820dp-xxhdpi")
class ScreenshotTest {
    @get:Rule val compose = createComposeRule()

    /** A stand-in photo: sky over ground, in a few film-ish tones. */
    private fun fakeThumb(seed: Int, portrait: Boolean) = run {
        val tones = listOf(
            0xFF85B7EB to 0xFF639922, 0xFFF0997B to 0xFF5F5E5A, 0xFFB5D4F4 to 0xFF888780,
            0xFFFAC775 to 0xFF993C1D, 0xFF9FE1CB to 0xFF0F6E56, 0xFFD3D1C7 to 0xFF444441,
        )
        val (sky, ground) = tones[seed % tones.size]
        val w = if (portrait) 120 else 160
        val h = if (portrait) 160 else 120
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(sky.toInt())
        c.drawRect(0f, h * 0.6f, w.toFloat(), h.toFloat(), Paint().apply { color = ground.toInt() })
        c.drawCircle(w * 0.7f, h * 0.3f, h * 0.1f, Paint().apply { color = 0xFFFAEEDA.toInt() })
        b.asImageBitmap()
    }

    private fun sample(): UiState {
        val days = listOf("20261027" to 7, "20261026" to 4, "20261019" to 5)
        var h = 1823
        val photos = mutableListOf<Photo>()
        for ((day, count) in days) for (i in 0 until count) {
            val name = "DSCF%04d.%s".format(h - 700, if (i == 2) "RAF" else "JPG")
            photos += Photo(
                handle = h,
                info = ObjectInfo(h, name, 1, "${day}T1${i}0000"),
                thumb = fakeThumb(h, portrait = i % 4 == 1),
                downloaded = day == "20261019" && i < 3,
                attempted = true,
                oriented = i % 3 != 2,
            )
            h--
        }
        photos += Photo(handle = 1, attempted = false)
        return UiState(
            phase = Phase.Connected,
            cameraName = "X-S20",
            photos = photos,
            total = 1822,
            selected = setOf(1822, 1821, 1815),
        )
    }

    @Test fun galleryFooter() {
        val vm = vm()
        val ui = sample().let { it.copy(photos = it.photos.take(7), selected = emptySet()) }
        compose.setContent { FilmTheme { GalleryScreen(ui, vm) {} } }
        compose.onRoot().captureRoboImage("build/screens/gallery_footer.png")
    }

    private fun vm() = MainViewModel(ApplicationProvider.getApplicationContext())

    @Test fun gallery() {
        val vm = vm()
        compose.setContent { FilmTheme { GalleryScreen(sample(), vm) {} } }
        compose.onRoot().captureRoboImage("build/screens/gallery.png")
    }

    @Test @Config(qualifiers = "+night")
    fun galleryDark() {
        val vm = vm()
        compose.setContent { FilmTheme { GalleryScreen(sample(), vm) {} } }
        compose.onRoot().captureRoboImage("build/screens/gallery_dark.png")
    }

    @Test fun transferring() {
        val vm = vm()
        val ui = sample().copy(selected = emptySet(), transfer = Transfer(2, 12, "DSCF1121.JPG", 6_000_000, 11_800_000, 4_300_000.0, 2, 0))
        compose.setContent { FilmTheme { GalleryScreen(ui, vm) {} } }
        compose.onRoot().captureRoboImage("build/screens/transferring.png")
    }

    @Test fun redownloadDialog() {
        val vm = vm()
        compose.setContent {
            FilmTheme {
                GalleryScreen(sample(), vm) {}
                RedownloadDialog(already = 12, chosen = 34, onNewOnly = {}, onAll = {}, onDismiss = {})
            }
        }
        com.github.takahirom.roborazzi.captureScreenRoboImage("build/screens/redownload_dialog.png")
    }

    private fun preparing() = dev.fujitransfer.Preparing(
        done = 340, total = 1822, secondsLeft = 262,
        recent = (0 until 6).map { fakeThumb(it * 7, portrait = it == 2) },
    )

    @Test fun preparingScreen() {
        compose.setContent { FilmTheme { PreparingScreen("X-S20", preparing()) {} } }
        compose.onRoot().captureRoboImage("build/screens/preparing.png")
    }

    @Test @Config(qualifiers = "+night")
    fun preparingScreenDark() {
        compose.setContent { FilmTheme { PreparingScreen("X-S20", preparing()) {} } }
        compose.onRoot().captureRoboImage("build/screens/preparing_dark.png")
    }

    /** The floating window during a transfer (2.39:1, about 250 dp wide on a phone). */
    @Test @Config(qualifiers = "w251dp-h105dp-xxhdpi") fun pipWindow() {
        val t = dev.fujitransfer.Transfer(2, 12, "DSCF1121.JPG", 6_000_000, 11_500_000, 4.1 * 1_048_576, 2, 0)
        compose.setContent { FilmTheme { PipView(UiState(transfer = t)) } }
        compose.onRoot().captureRoboImage("build/screens/pip.png")
    }

    @Test @Config(qualifiers = "w251dp-h105dp-night-xxhdpi") fun pipWindowDark() {
        val t = dev.fujitransfer.Transfer(2, 12, "DSCF1121.JPG", 6_000_000, 11_500_000, 4.1 * 1_048_576, 2, 0)
        compose.setContent { FilmTheme { PipView(UiState(transfer = t)) } }
        compose.onRoot().captureRoboImage("build/screens/pip_dark.png")
    }

    @Test fun connect() {
        val vm = vm()
        compose.setContent { FilmTheme { ConnectScreen(UiState(), vm) {} } }
        compose.onRoot().captureRoboImage("build/screens/connect.png")
    }

    @Test @Config(qualifiers = "+night")
    fun connectBusyDark() {
        val vm = vm()
        val ui = UiState(phase = Phase.PreparingCard, status = "Máy ảnh đang đọc thẻ nhớ… (12 s)")
        compose.setContent { FilmTheme { ConnectScreen(ui, vm) {} } }
        compose.onRoot().captureRoboImage("build/screens/connect_busy_dark.png")
    }
}
