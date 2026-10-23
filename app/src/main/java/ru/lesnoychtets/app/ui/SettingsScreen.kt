package ru.lesnoychtets.app.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import ru.lesnoychtets.app.data.AppPrefs

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val books by vm.books.collectAsStateWithLifecycle()
    val scanning by vm.scanning.collectAsStateWithLifecycle()
    val syncRunning by vm.syncRunning.collectAsStateWithLifecycle()
    val syncMessage by vm.syncMessage.collectAsStateWithLifecycle()
    var treeUri by remember { mutableStateOf(AppPrefs.treeUri) }
    var link by remember { mutableStateOf(AppPrefs.driveLink) }
    var wifiOnly by remember { mutableStateOf(AppPrefs.wifiOnly) }

    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            vm.setTreeUri(uri)
            treeUri = uri.toString()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BigButton("⬅ НАЗАД", onClick = onBack, container = Palette.StoneDark)
        Text("⚙ Настройки для родственников", fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
        Text(
            "В библиотеке: ${books.size} книг" + if (scanning) " (обновляется…)" else "",
            fontSize = 17.sp,
            color = Palette.Forest,
            fontWeight = FontWeight.Bold,
        )

        Section("📁 Папка на телефоне") {
            Text(
                treeUri?.let { "Выбрана: " + Uri.decode(Uri.parse(it).lastPathSegment ?: it) } ?: "Папка не выбрана",
                fontSize = 16.sp,
                color = Palette.InkSoft,
            )
            BigButton("Выбрать папку с аудиокнигами", onClick = { pickFolder.launch(null) }, fontSize = 18.sp)
            BigButton(
                "🔄 Обновить библиотеку",
                onClick = vm::rescan,
                container = Palette.Card, content = Palette.Ink, border = Palette.Border, fontSize = 18.sp,
            )
        }

        Section("☁ Семейная папка Google Drive") {
            Text(
                "Откройте доступ к папке «Все, у кого есть ссылка» и вставьте ссылку сюда. " +
                    "Приложение само скачивает новые книги раз в несколько часов и удаляет те, что убрали из папки.",
                fontSize = 15.sp,
                color = Palette.InkSoft,
            )
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Ссылка на папку") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            BigButton(
                if (syncRunning) "⏳ Синхронизация…" else "Сохранить и скачать сейчас",
                onClick = { vm.saveDriveLink(link) },
                enabled = !syncRunning,
                fontSize = 18.sp,
            )
            if (syncMessage.isNotBlank()) Text(syncMessage, fontSize = 15.sp, color = Palette.Ink)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Скачивать только по Wi-Fi", fontSize = 17.sp, modifier = Modifier.weight(1f))
                Switch(checked = wifiOnly, onCheckedChange = { wifiOnly = it; vm.setWifiOnly(it) })
            }
        }

        Section("📝 Как называть файлы") {
            Text(
                "• Один файл: «Пушкин А.С. - 1836 - Капитанская дочка.mp3»\n" +
                    "• Книга из нескольких файлов — папка «Толстой Л.Н. - 1869 - Война и мир» с файлами 01.mp3, 02.mp3…\n" +
                    "• Можно и так: папка «Есенин С.А.», в ней «1924 - Письмо матери.mp3»\n\n" +
                    "Писатели показываются по алфавиту, книги внутри — по годам. " +
                    "Форматы: MP3, M4A, M4B, OGG, OPUS, AAC, FLAC, WAV.",
                fontSize = 15.sp,
                color = Palette.Ink,
                lineHeight = 21.sp,
            )
        }

        Section("🎧 Наушники") {
            Text(
                "Кнопка на наушниках только ставит на паузу и продолжает чтение. Случайное двойное нажатие " +
                    "не переключит главу и не начнёт книгу заново. Если приложение закрыто, одно нажатие " +
                    "кнопки продолжит последнюю книгу.",
                fontSize = 15.sp,
                color = Palette.Ink,
                lineHeight = 21.sp,
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = Palette.Card,
        border = BorderStroke(1.dp, Palette.Border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Palette.Ink)
            content()
        }
    }
}
