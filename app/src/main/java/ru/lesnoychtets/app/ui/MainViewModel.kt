package ru.lesnoychtets.app.ui

import android.app.Application
import android.content.ComponentName
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkQuery
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
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
import ru.lesnoychtets.app.sync.DriveBookDownloadWorker
import ru.lesnoychtets.app.sync.DriveSyncWorker
import ru.lesnoychtets.app.sync.PROGRESS_DONE
import ru.lesnoychtets.app.sync.PROGRESS_TOTAL
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

sealed interface SyncState {
    data object Idle : SyncState
    data object Running : SyncState
    data class Done(val files: Int) : SyncState
    data object Failed : SyncState
}

/** Состояние скачивания книги. */
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
    val catalog = LibrivoxCatalog.books
    val catalogLoading = LibrivoxCatalog.loading

    private val player = MutableStateFlow(PlayerUi())
    val playerUi: StateFlow<PlayerUi> = player.asStateFlow()

    val sortAscending = MutableStateFlow(AppPrefs.sortAscending)

    /** Скачивания книг. Ключ: «lv:<id записи LibriVox>» или «book:<ключ книги из семейной папки>». */
    val downloads: StateFlow<Map<String, DownloadState>> =
        WorkManager.getInstance(app)
            .getWorkInfosFlow(WorkQuery.fromTags(LibrivoxDownloadWorker.TAG, DriveBookDownloadWorker.TAG))
            .map { infos ->
                infos.mapNotNull { info ->
                    val tag = info.tags.firstOrNull {
                        it.startsWith(LibrivoxDownloadWorker.ID_TAG_PREFIX) || it.startsWith(DriveBookDownloadWorker.KEY_TAG_PREFIX)
                    } ?: return@mapNotNull null
                    val state = when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadState.Waiting
                        WorkInfo.State.RUNNING -> DownloadState.Running(
                            info.progress.getInt(PROGRESS_DONE, 0),
                            info.progress.getInt(PROGRESS_TOTAL, 0),
                        )
                        WorkInfo.State.FAILED -> DownloadState.Failed
                        WorkInfo.State.SUCCEEDED -> DownloadState.Done
                        WorkInfo.State.CANCELLED -> DownloadState.None
                    }
                    tag to state
                }.toMap()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    /** Нажимали ли «Синхронизировать сейчас» — только тогда показываем итог. */
    private val manualSync = MutableStateFlow(false)

    /** Состояние синхронизации списка книг с семейной папкой. */
    val syncState: StateFlow<SyncState> =
        combine(
            WorkManager.getInstance(app).getWorkInfosForUniqueWorkFlow(DriveSyncWorker.NOW),
            manualSync,
        ) { infos, manual ->
            val info = infos.lastOrNull()
            when (info?.state) {
                WorkInfo.State.RUNNING, WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                    if (manual) SyncState.Running else SyncState.Idle
                WorkInfo.State.SUCCEEDED ->
                    if (manual) SyncState.Done(info.outputData.getInt(DriveSyncWorker.KEY_COUNT, 0)) else SyncState.Idle
                WorkInfo.State.FAILED -> if (manual) SyncState.Failed else SyncState.Idle
                else -> SyncState.Idle
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SyncState.Idle)

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

    /**
     * Книга для вкладки «Слушать»: выбранная и начатая (играет сейчас или поставлена на паузу).
     * Дослушанная до конца книга здесь не показывается.
     */
    fun currentBook(): Book? {
        val ui = player.value
        LibraryRepository.find(ui.bookKey)?.let { b ->
            if (b.downloaded && (ui.isPlaying || ProgressStore.get(b.key)?.finished != true)) return b
        }
        return LibraryRepository.find(AppPrefs.lastBookKey)
            ?.takeIf { it.downloaded && ProgressStore.get(it.key)?.started == true }
    }

    fun playBook(book: Book) {
        val c = controller ?: return
        if (!book.downloaded || book.tracks.isEmpty()) return
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

    /** Подключён ли телефон к Wi-Fi (сеть без ограничения трафика). */
    fun isOnWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** «Синхронизировать сейчас». Возвращает false, если нет Wi-Fi — тогда экран попросит подключиться. */
    fun syncNow(): Boolean {
        if (!isOnWifi()) return false
        manualSync.value = true
        DriveSyncWorker.syncNow(getApplication())
        return true
    }

    /**
     * Скачать книгу в телефон (только по кнопке). Прослушанная книга начнётся заново.
     * Возвращает false, если нет Wi-Fi.
     */
    fun downloadBook(book: Book): Boolean {
        if (!isOnWifi()) return false
        if (ProgressStore.get(book.key)?.finished == true) ProgressStore.save(book.key, Progress())
        val id = book.librivoxId
        if (id != null) {
            LibrivoxDownloadWorker.enqueue(getApplication(), CatalogBook(id, book.author, book.title, book.year))
        } else {
            DriveBookDownloadWorker.enqueue(getApplication(), book.key)
        }
        return true
    }

    fun downloadKey(book: Book): String =
        book.librivoxId?.let { LibrivoxDownloadWorker.ID_TAG_PREFIX + it } ?: (DriveBookDownloadWorker.KEY_TAG_PREFIX + book.key)

    // --- Бесплатный каталог LibriVox ---

    fun refreshCatalog() {
        viewModelScope.launch { LibrivoxCatalog.refresh() }
    }

    fun download(book: CatalogBook): Boolean {
        if (!isOnWifi()) return false
        LibrivoxDownloadWorker.enqueue(getApplication(), book)
        return true
    }

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
