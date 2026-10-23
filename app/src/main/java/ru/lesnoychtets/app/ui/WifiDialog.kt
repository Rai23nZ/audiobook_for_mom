package ru.lesnoychtets.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Книги скачиваются только по Wi-Fi: просим подключиться и открываем настройки Wi-Fi. */
@Composable
fun WifiDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Нет Wi-Fi", fontSize = 24.sp, fontWeight = FontWeight.Bold) },
        text = { Text("Книги загружаются только через Wi-Fi. Подключите телефон к домашнему Wi-Fi и нажмите кнопку ещё раз.", fontSize = 20.sp) },
        confirmButton = {
            TextButton(onClick = {
                val panel = if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS
                try {
                    context.startActivity(Intent(panel))
                } catch (_: ActivityNotFoundException) {
                    runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
                }
                onDismiss()
            }) { Text("ПОДКЛЮЧИТЬ WI-FI", fontSize = 19.sp) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ЗАКРЫТЬ", fontSize = 19.sp) } },
    )
}
