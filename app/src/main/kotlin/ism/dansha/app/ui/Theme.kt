package ism.dansha.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/** minimal พื้นขาว ตัวอักษรดำ — เขียว = บวก, แดง = ลบ */
object DanshaColors {
    val Ink = Color(0xFF111111)
    val Muted = Color(0xFF6B6B6B)
    val Line = Color(0xFFE6E6E6)
    val Surface = Color(0xFFF6F6F6)
    val Positive = Color(0xFF1B8A3A)
    val Negative = Color(0xFFD12E2E)
}

private val scheme = lightColorScheme(
    primary = DanshaColors.Ink,
    onPrimary = Color.White,
    primaryContainer = DanshaColors.Surface,
    onPrimaryContainer = DanshaColors.Ink,
    secondary = DanshaColors.Ink,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = DanshaColors.Ink,
    background = Color.White,
    onBackground = DanshaColors.Ink,
    surface = Color.White,
    onSurface = DanshaColors.Ink,
    surfaceVariant = DanshaColors.Surface,
    onSurfaceVariant = DanshaColors.Muted,
    surfaceContainer = Color.White,
    surfaceContainerLow = DanshaColors.Surface,
    surfaceContainerHigh = DanshaColors.Surface,
    outline = DanshaColors.Line,
    outlineVariant = DanshaColors.Line,
    error = DanshaColors.Negative,
)

@Composable
fun DanshaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}

private val money = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

/** 1234.5 → "1,234.50" */
fun formatMoney(v: BigDecimal?): String =
    if (v == null) "–" else money.format(v.setScale(2, RoundingMode.HALF_UP))

fun parseColor(hex: String, fallback: Color = DanshaColors.Surface): Color =
    try {
        if (hex.isBlank()) fallback else Color(hex.toColorInt())
    } catch (_: IllegalArgumentException) {
        fallback
    }
