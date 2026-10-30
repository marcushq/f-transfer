package dev.fujitransfer.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pinch-zoom maths behind the photo viewer. */
class ZoomStateTest {
    // A 3:2 landscape photo in a 1000×1500 view: fitted to 1000×667, centred.
    private fun zoom() = ZoomState().apply { view = Size(1000f, 1500f); image = Size(6240f, 4160f) }

    @Test fun fitsInsideTheView() {
        val z = zoom()
        assertEquals(1000f, z.content.width, 0.5f)
        assertEquals(666.7f, z.content.height, 0.5f)
        assertEquals(0f, z.bounds().x, 0.01f)
        assertEquals(0f, z.bounds().y, 0.01f)
    }

    @Test fun pinchKeepsThePointBetweenTheFingersStill() {
        val z = zoom()
        val fingers = Offset(800f, 750f) // 300 px right of the centre
        z.transform(fingers, Offset.Zero, 2f)
        assertEquals(2f, z.scale, 0f)
        // The photo point that was under the fingers is still there: 300 * 2 + offset = 300.
        assertEquals(-300f, z.offset.x, 0.01f)
        assertEquals(0f, z.offset.y, 0.01f)
    }

    @Test fun panStopsAtTheEdges() {
        val z = zoom()
        z.transform(Offset(500f, 750f), Offset.Zero, 2f) // 2000×1333: 500 px spare each side, none vertically
        z.transform(Offset(500f, 750f), Offset(-5000f, 400f), 1f)
        assertEquals(-500f, z.offset.x, 0.01f)
        assertEquals(0f, z.offset.y, 0.01f)
    }

    @Test fun doubleTapZoomsWhereTapped() {
        val z = zoom()
        val target = z.focus(Offset(900f, 750f), ZoomState.DOUBLE_TAP)
        // 400 px right of centre → 400 * (1 - 2.5) = -600, inside the 750 px allowed at 2.5×.
        assertEquals(-600f, target.x, 0.01f)
    }
}
