package ru.lesnoychtets.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.lesnoychtets.app.data.Book
import ru.lesnoychtets.app.data.LibraryBuilder
import ru.lesnoychtets.app.data.Progress
import java.text.Collator
import java.util.Locale

@Composable
fun LibraryScreen(
    vm: MainViewModel,
    openAuthor: String?,
    onOpenAuthor: (String?) -> Unit,
    onBookStarted: () -> Unit,
    onOpenCatalog: () -> Unit,
) {
    val books by vm.books.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val ui by vm.playerUi.collectAsStateWithLifecycle()
    val ascending by vm.sortAscending.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val sync by vm.syncState.collectAsStateWithLifecycle()
    var needWifi by remember { mutableStateOf(false) }
    val download = { book: Book -> if (!vm.downloadBook(book)) needWifi = true }

    val authorBooks = openAuthor?.let { a -> books.filter { it.author == a } }.orEmpty()
    if (openAuthor != null && authorBooks.isNotEmpty()) {
        val sorted = authorBooks.sortedWith(LibraryBuilder.byYear).let { if (ascending) it else it.reversed() }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                BigButton("⬅ НАЗАД К ПИСАТЕЛЯМ", onClick = { onOpenAuthor(null) }, container = Palette.StoneDark)
            }
            item {
                Text(openAuthor, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, modifier = Modifier.padding(top = 4.dp))
            }
            if (authorBooks.count { it.year != null } > 1) {
                item {
                    BigButton(
                        text = if (ascending) "📅 Сначала ранние" else "📅 Сначала поздние",
                        onClick = vm::toggleSort,
                        container = Palette.Card,
                        content = Palette.Ink,
                        border = Palette.Border,
                        fontSize = 18.sp,
                        minHeight = 56.dp,
                    )
                }
            }
            items(sorted, key = { it.key }) { book ->
                val state = downloads[vm.downloadKey(book)] ?: DownloadState.None
                BookCard(
                    book = book,
                    progress = progress[book.key],
                    download = state,
                    isCurrent = ui.bookKey == book.key,
                    isPlaying = ui.bookKey == book.key && ui.isPlaying,
                    onClick = {
                        when {
                            book.downloaded -> {
                                vm.playBook(book)
                                onBookStarted()
                            }
                            state !is DownloadState.Waiting && state !is DownloadState.Running -> download(book)
                        }
                    },
                )
            }
        }
    } else {
        val collator = Collator.getInstance(Locale("ru"))
        val authors = books.groupBy { it.author }.toSortedMap(collator)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { SyncButton(sync) { if (!vm.syncNow()) needWifi = true } }
            if (authors.isEmpty()) {
                item { EmptyHint("Книг пока нет. Подключитесь к домашнему Wi-Fi и нажмите «Синхронизировать сейчас».") }
            } else {
                item { Text("Выберите писателя:", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink) }
            }
            items(authors.entries.toList(), key = { it.key }) { (author, list) ->
                AuthorCard(author, list, progress) { onOpenAuthor(author) }
            }
            item {
                BigButton(
                    text = "🎁 БЕСПЛАТНЫЕ КНИГИ\nиз интернета",
                    onClick = onOpenCatalog,
                    container = Palette.AmberLight,
                    content = Palette.AmberDark,
                    border = Palette.AmberBorder,
                    minHeight = 88.dp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (needWifi) WifiDialog(onDismiss = { needWifi = false })
}

/** «Синхронизировать сейчас»: обновляет список книг семейной папки (сами книги не скачиваются). */
@Composable
private fun SyncButton(state: SyncState, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BigButton(
            text = if (state is SyncState.Running) "⏳ СИНХРОНИЗАЦИЯ…" else "🔄 СИНХРОНИЗИРОВАТЬ СЕЙЧАС",
            onClick = onClick,
            enabled = state !is SyncState.Running,
            container = Palette.Card,
            content = Palette.Forest,
            border = Palette.Green,
            fontSize = 20.sp,
        )
        when (state) {
            is SyncState.Done -> Text(
                if (state.files > 0) "✓ Список книг обновлён" else "✓ Готово. В семейной папке пока нет аудиофайлов",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.Forest,
            )
            SyncState.Failed -> Text(
                "⚠ Не удалось открыть семейную папку. Попробуйте позже.",
                fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.AmberDark,
            )
            else -> Unit
        }
    }
}

@Composable
private fun AuthorCard(author: String, books: List<Book>, progress: Map<String, Progress>, onClick: () -> Unit) {
    val years = books.mapNotNull { it.year }
    val listened = books.count { progress[it.key]?.finished == true }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Palette.Card,
        border = BorderStroke(2.dp, Palette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (years.isNotEmpty()) {
                    val range = if (years.min() == years.max()) "${years.min()} г." else "${years.min()}–${years.max()} гг."
                    Text(range, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Palette.AmberDark)
                }
                Text(author, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, lineHeight = 30.sp)
                Text(
                    "Книг: ${books.size}" + if (listened > 0) " • прослушано: $listened" else "",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Forest,
                )
            }
            Text("➜", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun BookCard(
    book: Book,
    progress: Progress?,
    download: DownloadState,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
) {
    val finished = progress?.finished == true
    val downloadStatus = when (download) {
        DownloadState.Waiting -> "⏳ Ждёт Wi-Fi для скачивания"
        is DownloadState.Running ->
            if (download.total > 1) "⏳ Скачивается: часть ${download.done + 1} из ${download.total}" else "⏳ Скачивается…"
        DownloadState.Failed -> "⚠ Не удалось скачать. Нажмите, чтобы повторить"
        else -> if (finished) "📥 Нажмите, чтобы скачать снова" else "📥 Нажмите, чтобы скачать"
    }
    val status = when {
        !book.downloaded -> downloadStatus
        finished -> null
        progress?.started == true -> buildString {
            append("📍 Продолжить")
            if (book.tracks.size > 1) append(": часть ${progress.track + 1} из ${book.tracks.size},")
            append(" с ${formatTime(progress.positionMs)}")
            if (book.tracks.size == 1 && progress.durationMs > 0) append(" (из ${formatTime(progress.durationMs)})")
        }
        book.tracks.size > 1 -> "🆕 Начать с начала • частей: ${book.tracks.size}"
        else -> "🆕 Начать с начала"
    }
    val dimmed = !book.downloaded
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = when {
            isCurrent -> Palette.GreenPale
            finished || dimmed -> Palette.Stone
            else -> Palette.Card
        },
        border = BorderStroke(2.dp, if (isCurrent) Palette.Green else Palette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (isCurrent) Palette.Forest else Palette.AmberLight),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ГОД", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isCurrent) Color.White else Palette.AmberDark)
                    Text(
                        book.year?.toString() ?: "—",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (isCurrent) Color.White else Palette.AmberDark,
                    )
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    book.title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (dimmed) Palette.InkSoft else Palette.Ink,
                    lineHeight = 26.sp,
                )
                if (finished) {
                    Text(
                        "✓ ПРОСЛУШАНО",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Palette.Forest)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (status != null) {
                    Text(status, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = if (dimmed) Palette.InkSoft else Palette.Forest, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (book.downloaded) {
                Box(
                    Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isPlaying) Palette.Amber else Palette.Green),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (isPlaying) "⏸" else "▶", fontSize = 26.sp, color = Color.White, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
