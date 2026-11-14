package clipfactory.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import clipfactory.app.R

object C {
    val bg = Color(0xFF0B0C10)
    val surface = Color(0xFF15171D)
    val surface2 = Color(0xFF1E2129)
    val line = Color(0xFF2A2E38)
    val text = Color(0xFFF4F5F7)
    val muted = Color(0xFF9198A5)
    val accent = Color(0xFFFFD400)
    val onAccent = Color(0xFF111111)
    val good = Color(0xFF4ADE80)
    val bad = Color(0xFFFF6B6B)
    val purple = Color(0xFF7C4DFF)
}

val Display = FontFamily(Font(R.font.montserrat_black, FontWeight.Black))

private val type = Typography(
    headlineLarge = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 34.sp),
    headlineSmall = TextStyle(fontFamily = Display, fontWeight = FontWeight.Black, fontSize = 22.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 15.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
)

@Composable
fun ClipTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.accent, onPrimary = C.onAccent, background = C.bg, onBackground = C.text,
            surface = C.surface, onSurface = C.text, surfaceVariant = C.surface2, onSurfaceVariant = C.muted,
            secondaryContainer = C.surface2, onSecondaryContainer = C.text, outline = C.line, error = C.bad,
            surfaceContainer = C.surface, surfaceContainerHigh = C.surface2, surfaceContainerHighest = C.surface2,
        ),
        typography = type,
        content = content,
    )
}
