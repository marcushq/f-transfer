package dev.fujitransfer

import androidx.test.core.app.ApplicationProvider
import dev.fujitransfer.ui.MiniMode
import dev.fujitransfer.ui.PipChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The Live Update request on the progress notification, and Auto's fallback to the window. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LiveUpdatesTest {
    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun asksForPromotionWithAChip() {
        val e = LiveUpdates.extras("3/12")
        assertTrue(e.getBoolean("android.requestPromotedOngoing"))
        assertEquals("3/12", e.getString("android.shortCriticalText"))
    }

    @Test fun autoFallsBackToTheWindowBeforeAndroid16() {
        assertFalse(LiveUpdates.available(context))
        PipChoice.set(context, MiniMode.Auto)
        assertTrue(PipChoice.usesWindow(context))
        assertEquals("Tự động (cửa sổ)", PipChoice.label(context))
        PipChoice.set(context, MiniMode.Off)
        assertFalse(PipChoice.usesWindow(context))
    }
}
