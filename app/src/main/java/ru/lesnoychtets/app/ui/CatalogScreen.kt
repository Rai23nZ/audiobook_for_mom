package ru.lesnoychtets.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.lesnoychtets.app.data.CatalogBook

/**
 * Бесплатные аудиокниги LibriVox: русская классика в общественном достоянии.
 * Книга скачивается в память телефона и потом слушается без интернета.
 */
@Composable
fun CatalogScreen(vm: MainViewModel, onBack: () -> Unit) {
    val catalog by vm.catalog.collectAsStateWithLifecycle()
    val loading by vm.catalogLoading.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val libraryKeys = books.map { it.key }.toSet()
    var toDelete by remember { mutableStateOf<CatalogBook?>(null) }

    LaunchedEffect(Unit) { vm.refreshCatalog() }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { BigButton("⬅ НАЗАД", onClick = onBack, container = Palette.StoneDark) }
        item {
            Text("🎁 Бесплатные книги", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            Text(
                "Русская классика из библиотеки LibriVox — записи добровольцев, свободные от авторских прав. " +
                    "Нажмите «Скачать»: книга загрузится по домашнему Wi-Fi и будет звучать в лесу без интернета.",
                fontSize = 17.sp,
                color = Palette.InkSoft,
                lineHeight = 22.sp,
            )
            if (loading) Text("⏳ Обновляю список…", fontSize = 16.sp, color = Palette.Forest)
        }
        items(catalog, key = { it.id }) { book ->
            val inLibrary = book.key in libraryKeys
            val state = downloads[book.id] ?: DownloadState.None
            CatalogCard(
                book = book,
                inLibrary = inLibrary,
                state = state,
                onDownload = { vm.download(book) },
                onDelete = { toDelete = book },
            )
        }
    }

    toDelete?.let { book ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Удалить книгу из телефона?", fontSize = 22.sp) },
            text = { Text("«${book.title}» можно будет скачать снова.", fontSize = 18.sp) },
            confirmButton = {
                TextButton(onClick = { vm.deleteDownloaded(book); toDelete = null }) { Text("Удалить", fontSize = 18.sp) }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Отмена", fontSize = 18.sp) } },
        )
    }
}

@Composable
private fun CatalogCard(
    book: CatalogBook,
    inLibrary: Boolean,
    state: DownloadState,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (inLibrary) Palette.GreenPale else Palette.Card,
        border = BorderStroke(2.dp, if (inLibrary) Palette.Green else Palette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                book.author + (book.year?.let { " • $it г." } ?: ""),
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Palette.AmberDark,
            )
            Text(book.title, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, lineHeight = 26.sp)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    inLibrary -> {
                        Text("✓ В библиотеке", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest, modifier = Modifier.weight(1f))
                        TextButton(onClick = onDelete) { Text("Удалить", fontSize = 16.sp, color = Palette.InkSoft) }
                    }
                    state is DownloadState.Running -> Text(
                        if (state.total > 0) "⏳ Скачивается: часть ${state.done + 1} из ${state.total}" else "⏳ Скачивается…",
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.Forest,
                    )
                    state is DownloadState.Waiting -> Text(
                        "⏳ Скачается по домашнему Wi-Fi",
                        fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Palette.AmberDark,
                    )
                    else -> BigButton(
                        text = if (state is DownloadState.Failed) "⚠ Не вышло. Скачать снова" else "📥 СКАЧАТЬ",
                        onClick = onDownload,
                        fontSize = 20.sp,
                        minHeight = 60.dp,
                    )
                }
            }
        }
    }
}
