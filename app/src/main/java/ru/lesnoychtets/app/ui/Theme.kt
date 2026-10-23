package ru.lesnoychtets.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Палитра из макета «Лесной Чтец»: тёплый фон, лесной зелёный и янтарный. */
object Palette {
    val Background = Color(0xFFFAF8F5)
    val Forest = Color(0xFF065F46)
    val ForestDark = Color(0xFF064E3B)
    val Green = Color(0xFF047857)
    val GreenLight = Color(0xFFD1FAE5)
    val GreenPale = Color(0xFFECFDF5)
    val Amber = Color(0xFFD97706)
    val AmberDark = Color(0xFF92400E)
    val AmberLight = Color(0xFFFEF3C7)
    val AmberBorder = Color(0xFFFCD34D)
    val Stone = Color(0xFFE7E5E4)
    val StoneDark = Color(0xFF292524)
    val Ink = Color(0xFF0F172A)
    val InkSoft = Color(0xFF475569)
    val Border = Color(0xFFCBD5E1)
    val Card = Color.White
}

@Composable
fun LesnoyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Palette.Green,
            onPrimary = Color.White,
            background = Palette.Background,
            surface = Palette.Card,
            onSurface = Palette.Ink,
        ),
        content = content,
    )
}

/** Крупная кнопка: минимум 64dp высотой, как в макете, чтобы попадать пальцем в перчатке. */
@Composable
fun BigButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    container: Color = Palette.Green,
    content: Color = Color.White,
    fontSize: TextUnit = 22.sp,
    minHeight: Dp = 64.dp,
    border: Color? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = minHeight),
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        border = border?.let { BorderStroke(2.dp, it) },
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text, fontSize = fontSize, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center, lineHeight = fontSize * 1.15)
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
