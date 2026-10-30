package dev.fujitransfer

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SavedUi(
    val loading: Boolean = true,
    /** A pull-to-refresh is running. */
    val refreshing: Boolean = false,
    val all: List<SavedPhoto> = emptyList(),
    /** "Tất cả", "JPEG", "HEIF", "RAF". */
    val filter: String = ALL,
    /** Index into [shown] of the photo open full screen, or null. */
    val viewing: Int? = null,
) {
    val shown: List<SavedPhoto> get() = if (filter == ALL) all else all.filter { it.kind == filter }

    companion object { const val ALL = "Tất cả" }
}

/** The "Đã lưu" tab: photos already on the phone, browsable without a camera. */
class SavedViewModel(app: Application) : AndroidViewModel(app) {
    private val saved = SavedPhotos(app)
    private val store = PhotoStore(app)

    private val _ui = MutableStateFlow(SavedUi())
    val ui: StateFlow<SavedUi> = _ui

    /** Grid thumbnails by photo id; filled as cells come on screen. */
    val thumbs = mutableStateMapOf<Long, ImageBitmap>()
    private val loading = HashSet<Long>()
    private val decoder = Dispatchers.IO.limitedParallelism(4)

    /** Set by [showForTest]: keep the given photos instead of reading the phone's. */
    private var frozen = false
    private var query: Job? = null

    /**
     * Photos added, changed or deleted anywhere on the phone (our own transfers, another app,
     * a file manager): read the list again once things settle.
     */
    private val changes = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            pending?.cancel()
            pending = viewModelScope.launch { delay(600); refresh() }
        }
    }
    private var pending: Job? = null

    init {
        app.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, changes)
    }

    override fun onCleared() {
        getApplication<Application>().contentResolver.unregisterContentObserver(changes)
    }

    /** Reads the list again. [pulled]: the user pulled down, so show the spinner for it. */
    fun refresh(pulled: Boolean = false) {
        if (frozen) {
            if (pulled) viewModelScope.launch { _ui.update { it.copy(refreshing = true) }; delay(400); _ui.update { it.copy(refreshing = false) } }
            return
        }
        if (pulled) _ui.update { it.copy(refreshing = true) }
        query?.cancel()
        query = viewModelScope.launch {
            val started = System.currentTimeMillis()
            val list = withContext(Dispatchers.IO) { saved.query() }
            // A spinner that vanishes at once looks like nothing happened.
            if (pulled) delay((400 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            val gone = _ui.value.all.map { it.id }.toSet() - list.map { it.id }.toSet()
            gone.forEach { thumbs.remove(it) }
            _ui.update { s -> s.copy(loading = false, refreshing = false, all = list, viewing = s.viewing?.takeIf { it < s.copy(all = list).shown.size }) }
        }
    }

    fun setFilter(f: String) = _ui.update { it.copy(filter = f, viewing = null) }

    /** Screenshot tests: show these photos and thumbnails instead of the phone's. */
    @androidx.annotation.VisibleForTesting
    fun showForTest(photos: List<SavedPhoto>, thumbnails: Map<Long, ImageBitmap>, viewing: Int? = null) {
        frozen = true
        thumbs.putAll(thumbnails)
        _ui.value = SavedUi(loading = false, all = photos, viewing = viewing)
    }

    fun requestThumb(p: SavedPhoto) {
        if (thumbs.containsKey(p.id) || !synchronized(loading) { loading.add(p.id) }) return
        viewModelScope.launch(decoder) {
            try {
                saved.thumbnail(p)?.let { thumbs[p.id] = it.asImageBitmap() }
            } finally {
                synchronized(loading) { loading.remove(p.id) }
            }
        }
    }

    fun open(index: Int) = _ui.update { it.copy(viewing = index) }
    /** The photo the viewer was last on, for the grid to bring into view; read once. */
    private var lastViewed: Int? = null

    fun close() = _ui.update { lastViewed = it.viewing; it.copy(viewing = null) }

    fun takeLastViewed(): Int? = lastViewed.also { lastViewed = null }

    /** Full-size image for the viewer, about two screens wide. */
    suspend fun full(p: SavedPhoto, maxSide: Int): Bitmap? = withContext(Dispatchers.IO) { saved.full(p, maxSide) }

    suspend fun details(p: SavedPhoto): ShotDetails? = withContext(Dispatchers.IO) { saved.details(p) }

    fun deleteRequest(p: SavedPhoto) = saved.deleteRequest(listOf(p))

    /** The system deleted [p]: drop it here, and let "Chọn ảnh mới" offer it again. */
    fun deleted(p: SavedPhoto) {
        store.forget(p.name)
        thumbs.remove(p.id)
        _ui.update { s ->
            val all = s.all.filter { it.id != p.id }
            val shownSize = if (s.filter == SavedUi.ALL) all.size else all.count { it.kind == s.filter }
            s.copy(all = all, viewing = s.viewing?.let { v -> if (shownSize == 0) null else v.coerceAtMost(shownSize - 1) })
        }
    }
}
