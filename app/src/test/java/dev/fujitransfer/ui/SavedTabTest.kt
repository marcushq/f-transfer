package dev.fujitransfer.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import dev.fujitransfer.PhotoStore
import dev.fujitransfer.SavedPhoto
import dev.fujitransfer.SavedViewModel
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.util.Calendar

/** The "Đã lưu" tab and its viewer, rendered with stand-in photos. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w392dp-h820dp-xxhdpi")
class SavedTabTest {
    @get:Rule val compose = createComposeRule()

    private fun thumb(seed: Int): ImageBitmap {
        val tones = listOf(0xFF85B7EB to 0xFF639922, 0xFFF0997B to 0xFF5F5E5A, 0xFFB5D4F4 to 0xFF888780,
            0xFFFAC775 to 0xFF993C1D, 0xFF9FE1CB to 0xFF0F6E56, 0xFFD3D1C7 to 0xFF444441)
        val (sky, ground) = tones[seed % tones.size]
        val b = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(sky.toInt())
        c.drawRect(0f, 72f, 160f, 120f, Paint().apply { color = ground.toInt() })
        c.drawCircle(112f, 36f, 12f, Paint().apply { color = 0xFFFAEEDA.toInt() })
        return b.asImageBitmap()
    }

    private fun photos(): List<SavedPhoto> {
        val out = ArrayList<SavedPhoto>()
        var id = 1L
        for ((day, count) in listOf(29 to 6, 27 to 9, 19 to 5)) {
            for (i in 0 until count) {
                val cal = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, 18, 59 - i) }
                val kind = when (i) { 2 -> "image/x-fuji-raf"; 5 -> "image/heif"; else -> "image/jpeg" }
                val ext = when (i) { 2 -> "RAF"; 5 -> "HEIF"; else -> "JPG" }
                out += SavedPhoto(id, Uri.parse("content://test/$id"), "DSCF%04d.%s".format(1100 - id, ext), kind,
                    cal.timeInMillis, 11_500_000, 6240, 4160,
                    // The 29th and 27th were both copied over today; the 19th on the day.
                    addedMs = if (day == 19) cal.timeInMillis else System.currentTimeMillis() - id * 60_000)
                id++
            }
        }
        return out
    }

    private fun vm(viewing: Int? = null) = SavedViewModel(ApplicationProvider.getApplicationContext()).apply {
        val p = photos()
        showForTest(p, p.associate { it.id to thumb(it.id.toInt()) }, viewing)
    }

    @Test fun savedTab() {
        val vm = vm()
        compose.setContent { FilmTheme { SavedScreen(vm) } }
        compose.onRoot().captureRoboImage("build/screens/saved_tab.png")
    }

    @Test @Config(qualifiers = "+night") fun savedTabDark() {
        val vm = vm()
        compose.setContent { FilmTheme { SavedScreen(vm) } }
        compose.onRoot().captureRoboImage("build/screens/saved_tab_dark.png")
    }

    @Test fun viewer() {
        val vm = vm(viewing = 3)
        compose.setContent { FilmTheme { PhotoViewer(vm.ui.value.shown, 3, vm) } }
        compose.onRoot().captureRoboImage("build/screens/saved_viewer.png")
    }

    /** One finger swiping across the photo moves to the next one (the zoom must not swallow it). */
    @Test fun swipeToNextPhoto() {
        val vm = vm(viewing = 3)
        compose.setContent { FilmTheme { PhotoViewer(vm.ui.value.shown, 3, vm) } }
        compose.onRoot().performTouchInput { swipeLeft(startX = right - 40f, endX = left + 40f, durationMillis = 250) }
        compose.waitForIdle()
        assertEquals(4, vm.ui.value.viewing)
        compose.onRoot().performTouchInput { swipeRight(startX = left + 40f, endX = right - 40f, durationMillis = 250) }
        compose.waitForIdle()
        assertEquals(3, vm.ui.value.viewing)
    }

    /** The latest import is everything copied over on the newest day, whatever day it was shot. */
    @Test fun latestImportTakesTheWholeDay() {
        val latest = latestImport(photos())
        assertEquals(15, latest.size)
        assertEquals((0 until 15).toList(), latest.map { it.index })
    }

    /** Pulling the grid down starts a refresh and shows the spinner until it is done. */
    @Test fun pullToRefresh() {
        val vm = vm()
        compose.setContent { FilmTheme { SavedScreen(vm) } }
        compose.mainClock.autoAdvance = false
        compose.onRoot().performTouchInput { swipeDown(startY = centerY - 200f, endY = bottom - 10f, durationMillis = 300) }
        compose.mainClock.advanceTimeBy(200)
        assertTrue("spinner while refreshing", vm.ui.value.refreshing)
        compose.mainClock.autoAdvance = true
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(600))
        assertFalse("spinner gone once done", vm.ui.value.refreshing)
    }

    /** Opening a photo and going back keeps the grid where it was scrolled to. */
    @Test fun backFromViewerKeepsScroll() {
        // Enough photos that the grid scrolls.
        val many = (0 until 4).flatMap { k -> photos().map { it.copy(id = it.id + k * 100L) } }
        val vm = SavedViewModel(ApplicationProvider.getApplicationContext()).apply {
            showForTest(many, many.associate { it.id to thumb(it.id.toInt()) })
        }
        lateinit var grid: androidx.compose.foundation.lazy.grid.LazyGridState
        compose.setContent {
            FilmTheme {
                grid = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
                androidx.compose.runtime.LaunchedEffect(Unit) { grid.scrollToItem(12) }
                val ui by vm.ui.collectAsState()
                val v = ui.viewing
                if (v != null) PhotoViewer(ui.shown, v, vm) else SavedScreen(vm, grid)
            }
        }
        compose.waitForIdle()
        val before = grid.firstVisibleItemIndex
        compose.runOnIdle { vm.open(10) }
        compose.waitForIdle()
        compose.runOnIdle { vm.close() }
        compose.waitForIdle()
        assertEquals(before, grid.firstVisibleItemIndex)
        assertTrue("was scrolled", before > 0)
    }

    /** Deleting a saved copy makes its camera photo count as not downloaded again. */
    @Test fun forgettingDeletedCopies() {
        val store = PhotoStore(ApplicationProvider.getApplicationContext())
        val key = store.key("DSCF0944.JPG", "20261027T101500")
        val heif = store.key("DSCF0001.HIF", "20261027T101500")
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        ctx.getSharedPreferences("downloaded", 0).edit().putBoolean(key, true).putBoolean(heif, true).commit()

        store.forget("DSCF0944_S (1).JPG")
        assertFalse("resized duplicate maps back to the camera name", store.isDownloaded(key))
        assertTrue(store.isDownloaded(heif))
        store.forget("DSCF0001.JPG") // a HEIF converted to JPEG
        assertFalse(store.isDownloaded(heif))
    }
}
