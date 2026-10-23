package ru.lesnoychtets.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun PlayerScreen(vm: MainViewModel, onOpenLibrary: () -> Unit) {
    val ui by vm.playerUi.collectAsStateWithLifecycle()
    val books by vm.books.collectAsStateWithLifecycle()
    val progressMap by vm.progress.collectAsStateWithLifecycle()

    val book = vm.currentBook()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (book == null) {
            EmptyHint(
                if (books.isEmpty()) {
                    "Книг пока нет.\n\nОни появятся сами, когда телефон будет подключён к домашнему Wi-Fi."
                } else {
                    "Выберите книгу во вкладке «Писатели»."
                },
            )
            if (books.isNotEmpty()) BigButton("📚 ВЫБРАТЬ КНИГУ", onClick = onOpenLibrary, minHeight = 96.dp, fontSize = 26.sp)
            return@Column
        }

        val live = ui.bookKey == book.key
        val saved = progressMap[book.key]
        val track = if (live) ui.track else saved?.takeIf { !it.finished }?.track ?: 0
        val position = if (live) ui.positionMs else saved?.takeIf { !it.finished }?.positionMs ?: 0
        val duration = if (live) ui.durationMs else saved?.durationMs ?: 0

        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Palette.Card,
            border = BorderStroke(2.dp, Palette.Stone),
        ) {
            Column(Modifier.padding(16.dp)) {
                if (book.year != null) {
                    Text(
                        "📅 ${book.year} г.",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Palette.AmberDark,
                        modifier = Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(Palette.AmberLight)
                            .border(1.dp, Palette.AmberBorder, RoundedCornerShape(12.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                }
                Text(book.author, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.InkSoft)
                Text(book.title, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink, lineHeight = 32.sp)
                if (book.tracks.size > 1) {
                    Text(
                        "Часть ${track + 1} из ${book.tracks.size}",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Palette.Forest,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("📍 ${formatTime(position)}", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Forest)
                    if (duration > 0) {
                        Text("из ${formatTime(duration)}", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Palette.Ink)
                    }
                }
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(16.dp)
                        .clip(RoundedCornerShape(8.dp)),
                    color = Palette.Green,
                    trackColor = Palette.Stone,
                )
            }
        }

        val playLabel = when {
            ui.isBuffering -> "⏳ ЗАГРУЗКА…"
            ui.isPlaying -> "⏸  ПАУЗА"
            position > 1000 || track > 0 -> "▶  ПРОДОЛЖИТЬ\nс ${formatTime(position)}"
            else -> "▶  СЛУШАТЬ"
        }
        BigButton(
            text = playLabel,
            onClick = vm::togglePlay,
            container = if (ui.isPlaying) Palette.Amber else Palette.Green,
            minHeight = 130.dp,
            fontSize = 32.sp,
            enabled = ui.connected,
        )

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton(
                text = "⏪ Назад\n30 сек",
                onClick = vm::rewind30,
                modifier = Modifier.weight(1f),
                container = Palette.Card,
                content = Palette.Ink,
                border = Palette.InkSoft,
                minHeight = 88.dp,
            )
            BigButton(
                text = "📚 Другая\nкнига",
                onClick = onOpenLibrary,
                modifier = Modifier.weight(1f),
                container = Palette.Stone,
                content = Palette.Ink,
                border = Palette.InkSoft,
                minHeight = 88.dp,
            )
        }
    }
}

@Composable
fun EmptyHint(text: String) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Palette.AmberLight,
        border = BorderStroke(2.dp, Palette.AmberBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Ink, modifier = Modifier.padding(18.dp), lineHeight = 28.sp)
    }
}
