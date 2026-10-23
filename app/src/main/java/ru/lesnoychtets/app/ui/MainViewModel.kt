package ru.lesnoychtets.app.ui

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import ru.lesnoychtets.app.data.AppPrefs
import ru.lesnoychtets.app.data.Book
import ru.lesnoychtets.app.data.CatalogBook
import ru.lesnoychtets.app.data.LibraryRepository
import ru.lesnoychtets.app.data.LibrivoxCatalog
import ru.lesnoychtets.app.data.LibrivoxDownloader
import ru.lesnoychtets.app.data.Progress
import ru.lesnoychtets.app.data.ProgressStore
import ru.lesnoychtets.app.playback.PlaybackService
import ru.lesnoychtets.app.playback.Queue
import ru.lesnoychtets.app.sync.DriveSyncWorker
import ru.lesnoychtets.app.sync.LibrivoxDownloadWorker

/** Что сейчас происходит в плеере (опрашивается дважды в секунду). */
data class PlayerUi(
    val connected: Boolean = false,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val bookKey: String? = null,
    val track: Int = 0,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Состояние скачивания книги из каталога LibriVox. */
sealed interface DownloadState {
    data object None : DownloadState
    data object Waiting : DownloadState
    data class Running(val done: Int, val total: Int) : DownloadState
    data object Failed : DownloadState
    data object Done : DownloadState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val books: StateFlow<List<Book>> = LibraryRepository.books
    val progress: StateFlow<Map<String, Progress>> = ProgressStore.all
    val scanning = LibraryRepository.scanning
    val catalog = LibrivoxCatalog.books
    val catalogLoading = LibrivoxCatalog.loading
    val syncRunning = AppPrefs.syncRunning
    val syncMessage = AppPrefs.syncMessage

    private val player = MutableStateFlow(PlayerUi())
    val playerUi: StateFlow<PlayerUi> = player.asStateFlow()

    val sortAscending = MutableStateFlow(AppPrefs.sortAscending)

    val downloads: StateFlow<Map<String, DownloadState>> =
        WorkManager.getInstance(app).getWorkInfosByTagFlow(LibrivoxDownloadWorker.TAG)
            .map { infos ->
                infos.mapNotNull { info ->
                    val id = info.tags.firstOrNull { it.startsWith(LibrivoxDownloadWorker.ID_TAG_PREFIX) }
                        ?.removePrefix(LibrivoxDownloadWorker.ID_TAG_PREFIX) ?: return@mapNotNull null
                    val state = when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Waiting
                        WorkInfo.State.RUNNING -> DownloadState.Running(
                            info.progress.getInt(LibrivoxDownloadWorker.PROGRESS_DONE, 0),
                            info.progress.getInt(LibrivoxDownloadWorker.PROGRESS_TOTAL, 0),
                        )
                        WorkInfo.State.FAILED -> DownloadState.Failed
                        WorkInfo.State.SUCCEEDED -> DownloadState.Done
                        WorkInfo.State.CANCELLED -> DownloadState.None
                    }
                    id to state
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private var controller: MediaController? = null

    init {
        viewModelScope.launch {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            val c = runCatching { MediaController.Builder(app, token).buildAsync().await() }.getOrNull()
                ?: return@launch
            controller = c
            while (true) {
                refreshPlayer(c)
                delay(500)
            }
        }
        rescan()
    }

    private fun refreshPlayer(c: MediaController) {
        val item = c.currentMediaItem
        player.value = PlayerUi(
            connected = true,
            isPlaying = c.isPlaying,
            isBuffering = c.playbackState == Player.STATE_BUFFERING && c.playWhenReady,
            bookKey = Queue.bookKeyOf(item?.mediaId),
            track = Queue.trackOf(item?.mediaId),
            positionMs = c.currentPosition,
            durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0,
        )
    }

    /** Книга, показываемая на вкладке «Слушать»: играющая сейчас или последняя. */
    fun currentBook(): Book? =
        LibraryRepository.find(player.value.bookKey) ?: LibraryRepository.find(AppPrefs.lastBookKey)

    fun playBook(book: Book) {
        val c = controller ?: return
        AppPrefs.lastBookKey = book.key
        val start = Queue.build(books.value, book)
        c.setMediaItems(start.items, start.index, start.positionMs)
        c.prepare()
        c.play()
        refreshPlayer(c)
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            val book = currentBook() ?: return
            val loaded = Queue.bookKeyOf(c.currentMediaItem?.mediaId) == book.key
            if (loaded && c.playbackState != Player.STATE_ENDED) {
                if (c.playbackState == Player.STATE_IDLE) c.prepare()
                c.play()
            } else {
                playBook(book)
            }
        }
        refreshPlayer(c)
    }

    fun rewind30() {
        val c = controller ?: return
        val book = currentBook() ?: return
        if (Queue.bookKeyOf(c.currentMediaItem?.mediaId) == book.key) {
            c.seekTo((c.currentPosition - 30_000).coerceAtLeast(0))
            refreshPlayer(c)
        } else {
            ProgressStore.get(book.key)?.let {
                ProgressStore.save(book.key, it.copy(positionMs = (it.positionMs - 30_000).coerceAtLeast(0)))
            }
        }
    }

    fun toggleSort() {
        val v = !sortAscending.value
        sortAscending.value = v
        AppPrefs.sortAscending = v
    }

    fun rescan() {
        viewModelScope.launch { LibraryRepository.rescan(getApplication()) }
    }

    // --- Настройки для родственников ---

    fun setTreeUri(uri: Uri) {
        val app = getApplication<Application>()
        runCatching {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        AppPrefs.treeUri = uri.toString()
        rescan()
    }

    fun saveDriveLink(link: String) {
        AppPrefs.driveLink = link.trim()
        DriveSyncWorker.schedule(getApplication())
        if (link.isNotBlank()) DriveSyncWorker.syncNow(getApplication())
    }

    fun syncNow() = DriveSyncWorker.syncNow(getApplication())

    fun setWifiOnly(value: Boolean) {
        AppPrefs.wifiOnly = value
        DriveSyncWorker.schedule(getApplication())
    }

    // --- Бесплатный каталог LibriVox ---

    fun refreshCatalog() {
        viewModelScope.launch { LibrivoxCatalog.refresh() }
    }

    fun isDownloaded(book: CatalogBook) = LibrivoxDownloader.isDownloaded(getApplication(), book.id)

    fun download(book: CatalogBook) = LibrivoxDownloadWorker.enqueue(getApplication(), book)

    fun deleteDownloaded(book: CatalogBook) {
        LibrivoxDownloadWorker.cancel(getApplication(), book.id)
        LibrivoxDownloader.delete(getApplication(), book.id)
        rescan()
    }

    override fun onCleared() {
        controller?.release()
        super.onCleared()
    }
}
