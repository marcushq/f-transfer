package dev.fujitransfer

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * Holds the camera session for the life of the process rather than of a screen: closing the
 * app's window (or its picture-in-picture window) mid-transfer must not drop the connection.
 * [TransferService] keeps the process alive while photos are moving.
 */
class FujiApp : Application(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    /** The one camera session, shared by every activity and the transfer service. */
    val session: MainViewModel by lazy {
        ViewModelProvider(this, ViewModelProvider.AndroidViewModelFactory.getInstance(this))[MainViewModel::class.java]
    }
}

/** Whether one of the app's windows is on screen; the transfer bubble shows only when not. */
object AppVisible {
    val visible = kotlinx.coroutines.flow.MutableStateFlow(false)
}
