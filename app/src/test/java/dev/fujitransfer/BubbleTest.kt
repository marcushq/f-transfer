package dev.fujitransfer

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The floating bubble, drawn at its real 64 dp: a short count, a long one, and done. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "xxhdpi")
class BubbleTest {
    private fun render(t: Transfer?): Bitmap {
        val v = BubbleView(ApplicationProvider.getApplicationContext())
        val px = (64 * v.resources.displayMetrics.density).toInt()
        v.update(t)
        v.measure(View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(px, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, px, px)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    @Test fun drawsTheBadges() {
        val shots = listOf(
            render(Transfer(2, 12, "DSCF1121.JPG", 6_000_000, 11_500_000, 9.4 * 1_048_576, 2, 0)),
            render(Transfer(111, 340, "DSCF1121.JPG", 1, 2, 0.0, 111, 0)),
            render(null),
        )
        // Side by side on a light ground, as it would float over another app.
        val gap = 24
        val w = shots.sumOf { it.width } + gap * (shots.size + 1)
        val out = Bitmap.createBitmap(w, shots[0].height + gap * 2, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(0xFFE9E6E0.toInt())
        var x = gap.toFloat()
        for (s in shots) { c.drawBitmap(s, x, gap.toFloat(), null); x += s.width + gap }
        File("build/screens").mkdirs()
        File("build/screens/bubble.png").outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
