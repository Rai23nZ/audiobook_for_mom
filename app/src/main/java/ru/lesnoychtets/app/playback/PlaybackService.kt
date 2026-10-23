package ru.lesnoychtets.app.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import ru.lesnoychtets.app.data.AppPrefs
import ru.lesnoychtets.app.data.LibraryRepository
import ru.lesnoychtets.app.data.Progress
import ru.lesnoychtets.app.data.ProgressStore
import ru.lesnoychtets.app.ui.MainActivity

/**
 * Плеер в отдельном сервисе: читает с погашенным экраном, показывает кнопки в шторке
 * и на экране блокировки, слушается кнопки Bluetooth-наушников.
 */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private lateinit var exo: ExoPlayer
    private val handler = Handler(Looper.getMainLooper())
    private var currentBookKey: String? = null

    private val periodicSave = object : Runnable {
        override fun run() {
            if (exo.isPlaying) saveCurrent()
            handler.postDelayed(this, 5_000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ true, // пауза на время телефонного звонка
            )
            .setHandleAudioBecomingNoisy(true) // пауза, если наушники отключились
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        exo.addListener(listener)

        val openApp = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, HeadsetSafePlayer(exo, ::saveCurrent))
            .setSessionActivity(openApp)
            .setCallback(callback)
            .build()
        handler.post(periodicSave)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Окно смахнули: если книга играет — продолжаем, иначе сервис больше не нужен.
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        saveCurrent()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun saveCurrent() {
        val item = exo.currentMediaItem ?: return
        val key = Queue.bookKeyOf(item.mediaId) ?: return
        if (exo.playbackState != Player.STATE_READY && exo.playbackState != Player.STATE_BUFFERING) return
        val duration = exo.duration.takeIf { it != C.TIME_UNSET } ?: 0L
        ProgressStore.save(key, Progress(Queue.trackOf(item.mediaId), exo.currentPosition, duration, finished = false))
    }

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (!isPlaying) saveCurrent()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val newKey = Queue.bookKeyOf(mediaItem?.mediaId)
            val previousKey = currentBookKey
            currentBookKey = newKey
            if (newKey != null) AppPrefs.lastBookKey = newKey
            if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || newKey == null || newKey == previousKey) return

            // Книга дослушана, началась следующая книга писателя.
            previousKey?.let(ProgressStore::markFinished)
            val saved = ProgressStore.get(newKey)
            if (saved != null && saved.started) {
                val target = Queue.mediaId(LibraryRepository.find(newKey) ?: return, saved.track)
                val index = (0 until exo.mediaItemCount).firstOrNull { exo.getMediaItemAt(it).mediaId == target }
                if (index != null) exo.seekTo(index, saved.positionMs)
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                Queue.bookKeyOf(exo.currentMediaItem?.mediaId)?.let(ProgressStore::markFinished)
            }
        }
    }

    private val callback = object : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            val commands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .removeAll(*HeadsetSafePlayer.BLOCKED.toIntArray())
                .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(commands)
                .build()
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            // При передаче от экрана к сервису адрес файла приходит в requestMetadata.
            val resolved = mediaItems.map { item ->
                if (item.localConfiguration != null) item
                else item.buildUpon().setUri(item.requestMetadata.mediaUri).build()
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }

        /** Нажали кнопку на наушниках, а приложение было закрыто — продолжаем последнюю книгу. */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val book = LibraryRepository.find(AppPrefs.lastBookKey)
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Нет последней книги"))
            val start = Queue.build(LibraryRepository.books.value, book)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(start.items, start.index, start.positionMs),
            )
        }
    }
}

/**
 * Защита от случайных нажатий: двойное касание наушников (шапкой, воротником, каплей)
 * не перескакивает на другую главу и не начинает книгу заново. Работает только «пуск/пауза».
 */
class HeadsetSafePlayer(
    player: Player,
    private val beforeQueueChange: () -> Unit,
) : ForwardingPlayer(player) {
    companion object {
        val BLOCKED = listOf(
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_FORWARD,
        )
    }

    override fun getAvailableCommands(): Player.Commands =
        super.getAvailableCommands().buildUpon().removeAll(*BLOCKED.toIntArray()).build()

    override fun isCommandAvailable(command: Int): Boolean = command !in BLOCKED && super.isCommandAvailable(command)

    // Перед сменой книги запоминаем, где остановилась предыдущая.
    override fun setMediaItems(mediaItems: MutableList<MediaItem>, startIndex: Int, startPositionMs: Long) {
        beforeQueueChange()
        super.setMediaItems(mediaItems, startIndex, startPositionMs)
    }

    override fun setMediaItems(mediaItems: MutableList<MediaItem>, resetPosition: Boolean) {
        beforeQueueChange()
        super.setMediaItems(mediaItems, resetPosition)
    }

    override fun seekToNext() = Unit
    override fun seekToNextMediaItem() = Unit
    override fun seekToPrevious() = Unit
    override fun seekToPreviousMediaItem() = Unit
    override fun seekForward() = Unit
}
