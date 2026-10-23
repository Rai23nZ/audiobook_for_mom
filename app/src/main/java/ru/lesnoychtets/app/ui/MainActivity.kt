package ru.lesnoychtets.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class Screen { PLAYER, LIBRARY, CATALOG, SETTINGS }

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LesnoyTheme {
                AppRoot(vm)
            }
        }
    }
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var screen by rememberSaveable { mutableStateOf(Screen.PLAYER) }
    var openAuthor by rememberSaveable { mutableStateOf<String?>(null) }

    BackHandler(enabled = screen != Screen.PLAYER) {
        when {
            screen == Screen.LIBRARY && openAuthor != null -> openAuthor = null
            screen == Screen.CATALOG || screen == Screen.SETTINGS -> screen = Screen.LIBRARY
            else -> screen = Screen.PLAYER
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
            .safeDrawingPadding(),
    ) {
        if (screen == Screen.PLAYER || screen == Screen.LIBRARY) {
            Row(
                Modifier
                    .background(Palette.Stone)
                    .padding(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val playerActive = screen == Screen.PLAYER
                BigButton(
                    text = "▶ СЛУШАТЬ",
                    onClick = { screen = Screen.PLAYER },
                    modifier = Modifier.weight(1f),
                    container = if (playerActive) Palette.Forest else Color.White,
                    content = if (playerActive) Color.White else Palette.Ink,
                    border = if (playerActive) null else Palette.Border,
                    minHeight = 72.dp,
                )
                BigButton(
                    text = "📚 ПИСАТЕЛИ",
                    onClick = { screen = Screen.LIBRARY },
                    modifier = Modifier.weight(1f),
                    container = if (!playerActive) Palette.Forest else Color.White,
                    content = if (!playerActive) Color.White else Palette.Ink,
                    border = if (!playerActive) null else Palette.Border,
                    minHeight = 72.dp,
                )
            }
        }

        when (screen) {
            Screen.PLAYER -> PlayerScreen(vm, onOpenLibrary = { screen = Screen.LIBRARY; openAuthor = null })
            Screen.LIBRARY -> LibraryScreen(
                vm = vm,
                openAuthor = openAuthor,
                onOpenAuthor = { openAuthor = it },
                onBookStarted = { screen = Screen.PLAYER },
                onOpenCatalog = { screen = Screen.CATALOG },
                onOpenSettings = { screen = Screen.SETTINGS },
            )
            Screen.CATALOG -> CatalogScreen(vm, onBack = { screen = Screen.LIBRARY })
            Screen.SETTINGS -> SettingsScreen(vm, onBack = { screen = Screen.LIBRARY })
        }
    }
}
