package ru.lesnoychtets.app.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import ru.lesnoychtets.app.data.Book
import ru.lesnoychtets.app.data.LibraryBuilder
import ru.lesnoychtets.app.data.ProgressStore

/**
 * Очередь воспроизведения: выбранная книга и следом непрослушанные книги того же писателя по годам.
 * Так стихи идут одно за другим сами, без участия слушателя.
 */
object Queue {
    private const val SEP = '\u001F'

    data class Start(val items: List<MediaItem>, val index: Int, val positionMs: Long)

    fun mediaId(book: Book, track: Int) = "${book.key}$SEP$track"

    fun bookKeyOf(mediaId: String?): String? = mediaId?.substringBeforeLast(SEP, "")?.ifEmpty { null }

    fun trackOf(mediaId: String?): Int = mediaId?.substringAfterLast(SEP, "")?.toIntOrNull() ?: 0

    fun build(all: List<Book>, start: Book): Start {
        // Дальше идут только скачанные и ещё не прослушанные произведения этого писателя.
        val sameAuthor = all.filter { it.author == start.author }.sortedWith(LibraryBuilder.byYear)
        val following = listOf(start) + sameAuthor.dropWhile { it.key != start.key }.drop(1)
            .filter { it.downloaded && it.tracks.isNotEmpty() && ProgressStore.get(it.key)?.finished != true }
        val items = following.flatMap { book -> book.tracks.indices.map { item(book, it) } }
        val progress = ProgressStore.get(start.key)
        return if (progress == null || progress.finished) {
            Start(items, 0, 0)
        } else {
            Start(items, progress.track.coerceIn(0, start.tracks.lastIndex), progress.positionMs)
        }
    }

    private fun item(book: Book, track: Int): MediaItem {
        val uri = Uri.parse(book.tracks[track].uri)
        val title = if (book.tracks.size > 1) "${book.title} — часть ${track + 1}" else book.title
        return MediaItem.Builder()
            .setMediaId(mediaId(book, track))
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(book.author)
                    .setAlbumTitle(book.title)
                    .setReleaseYear(book.year)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }
}
