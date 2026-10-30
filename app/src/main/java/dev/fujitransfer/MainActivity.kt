package dev.fujitransfer

import android.app.PictureInPictureParams
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import android.graphics.Color
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.lifecycleScope
import dev.fujitransfer.ui.App
import dev.fujitransfer.ui.PipChoice
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** The camera session outlives this window (see [FujiApp]). */
    private val vm: MainViewModel get() = (application as FujiApp).session
    private val saved: SavedViewModel by viewModels()

    /** Showing as the small floating window. */
    private var inPip by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        vm.uiOpened()
        PipChoice.load(this)
        addOnPictureInPictureModeChangedListener { inPip = it.isInPictureInPictureMode }
        // Android 12+: slip into the floating window by itself when the user leaves mid-transfer.
        lifecycleScope.launch {
            vm.ui.map { it.transfer != null }.distinctUntilChanged()
                .combine(snapshotFlow { PipChoice.mode }) { busy, _ -> busy }
                .collect { busy = it; updatePip() }
        }
        setContent { App(vm, saved, inPip) }
    }

    private var busy = false

    /** Auto-enter only in window mode (or bubble mode without its permission). */
    private fun updatePip() {
        if (Build.VERSION.SDK_INT >= 31) setPictureInPictureParams(pipParams(busy && PipChoice.usesWindow(this)))
    }

    override fun onStart() {
        super.onStart()
        AppVisible.visible.value = true
        // Back from the settings page: "Display over other apps" may have changed.
        updatePip()
    }

    override fun onStop() {
        AppVisible.visible.value = false
        super.onStop()
    }

    private fun pipParams(autoEnter: Boolean): PictureInPictureParams {
        // As flat as Android allows (2.39:1), so it can sit along the top edge.
        val b = PictureInPictureParams.Builder().setAspectRatio(Rational(239, 100))
            .setActions(listOf(stopAction()))
        if (Build.VERSION.SDK_INT >= 31) b.setAutoEnterEnabled(autoEnter).setSeamlessResizeEnabled(false)
        return b.build()
    }


    /** "Dừng" among the floating window's controls (shown when it is tapped). */
    private fun stopAction() = android.app.RemoteAction(
        android.graphics.drawable.Icon.createWithResource(this, android.R.drawable.ic_media_pause),
        "Dừng", "Dừng tải ảnh", TransferService.stopIntent(this),
    )

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Before Android 12 there is no auto-enter: do it on Home / Recents.
        if (Build.VERSION.SDK_INT < 31 && PipChoice.usesWindow(this) && vm.ui.value.transfer != null) {
            try {
                enterPictureInPictureMode(pipParams(false))
            } catch (_: IllegalStateException) {
            }
        }
    }

    override fun onDestroy() {
        // Swiped away, backed out of, or its floating window closed: a transfer keeps going.
        if (isFinishing) vm.uiClosed()
        super.onDestroy()
    }
}
