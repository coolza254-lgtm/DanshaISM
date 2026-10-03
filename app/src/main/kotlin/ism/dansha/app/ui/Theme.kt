package ism.dansha.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import ism.dansha.app.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * ธีม 断捨ISM: พาสเทลอบอุ่น (สว่าง/มืด) — สีทั้งแอพมาจากที่นี่ที่เดียว
 * เขียว = บวก, แดงกุหลาบ = ลบ
 */

@Immutable
data class Palette(
    val dark: Boolean,
    /** พื้นหลังหน้าจอ */
    val bg: Color,
    /** การ์ด */
    val card: Color,
    /** พื้นรอง: ช่องกรอก ชิป หัวกลุ่ม */
    val surface: Color,
    val ink: Color,
    val muted: Color,
    val line: Color,
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    val positive: Color,
    val positiveSoft: Color,
    val negative: Color,
    val negativeSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    /** ไล่สีการ์ดหลักหน้าแรก */
    val heroStart: Color,
    val heroEnd: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFFF8F5), card = Color(0xFFFFFFFF), surface = Color(0xFFFBEFF1),
    ink = Color(0xFF3A3440), muted = Color(0xFF8E8494), line = Color(0xFFF0E2E7),
    primary = Color(0xFFE26D8A), onPrimary = Color.White, primarySoft = Color(0xFFFFE3EA),
    positive = Color(0xFF2E9E6B), positiveSoft = Color(0xFFDDF3E7),
    negative = Color(0xFFD64B6A), negativeSoft = Color(0xFFFDE3E8),
    warning = Color(0xFFD9891C), warningSoft = Color(0xFFFFF1D9),
    heroStart = Color(0xFFFFD6E0), heroEnd = Color(0xFFFFE9D6),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF17151A), card = Color(0xFF221F26), surface = Color(0xFF2C2830),
    ink = Color(0xFFF3EDF4), muted = Color(0xFFA79DAD), line = Color(0xFF352F3A),
    primary = Color(0xFFF28BA5), onPrimary = Color(0xFF2A1018), primarySoft = Color(0xFF3D2430),
    positive = Color(0xFF6FD3A0), positiveSoft = Color(0xFF1D3329),
    negative = Color(0xFFFF8DA5), negativeSoft = Color(0xFF3B1F27),
    warning = Color(0xFFF2B65A), warningSoft = Color(0xFF3A2F1C),
    heroStart = Color(0xFF45283A), heroEnd = Color(0xFF3E2F2A),
)

val LocalPalette = staticCompositionLocalOf { LightPalette }

/** ทางลัดเรียกสีของธีมปัจจุบัน (ชื่อเดิม เพื่อให้หน้าจอเก่าใช้ต่อได้) */
object DanshaColors {
    val Ink: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.ink
    val Muted: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.muted
    val Line: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.line
    val Surface: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.surface
    val Card: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.card
    val Bg: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.bg
    val Positive: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.positive
    val Negative: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.negative
    val Primary: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.primary
    val PrimarySoft: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.primarySoft
    val Warning: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.warning
}

val NotoSansThai = FontFamily(
    Font(R.font.notosansthai_regular, FontWeight.Normal),
    Font(R.font.notosansthai_medium, FontWeight.Medium),
    Font(R.font.notosansthai_semibold, FontWeight.SemiBold),
    Font(R.font.notosansthai_bold, FontWeight.Bold),
)

private fun typography(): Typography {
    val t = Typography()
    fun TextStyle.f() = copy(fontFamily = NotoSansThai)
    return Typography(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp),
)

/** mode: system | light | dark */
@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "light" -> false
    "dark" -> true
    else -> isSystemInDarkTheme()
}

@Composable
fun DanshaTheme(mode: String = "system", content: @Composable () -> Unit) {
    val p = if (isDark(mode)) DarkPalette else LightPalette
    val scheme = if (p.dark) darkColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primarySoft, onPrimaryContainer = p.ink,
        secondary = p.primary, onSecondary = p.onPrimary, secondaryContainer = p.primarySoft, onSecondaryContainer = p.ink,
        background = p.bg, onBackground = p.ink, surface = p.card, onSurface = p.ink, surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.bg, surfaceContainerLow = p.card, surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.surface,
        outline = p.line, outlineVariant = p.line, error = p.negative,
    ) else lightColorScheme(
        primary = p.primary, onPrimary = p.onPrimary, primaryContainer = p.primarySoft, onPrimaryContainer = p.ink,
        secondary = p.primary, onSecondary = p.onPrimary, secondaryContainer = p.primarySoft, onSecondaryContainer = p.ink,
        background = p.bg, onBackground = p.ink, surface = p.card, onSurface = p.ink, surfaceVariant = p.surface, onSurfaceVariant = p.muted,
        surfaceContainerLowest = p.card, surfaceContainerLow = p.card, surfaceContainer = p.card, surfaceContainerHigh = p.card, surfaceContainerHighest = p.surface,
        outline = p.line, outlineVariant = p.line, error = p.negative,
    )
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme, typography = typography(), shapes = shapes, content = content)
    }
}

private val money = DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US))

/** 1234.5 → "1,234.50" */
fun formatMoney(v: BigDecimal?): String =
    if (v == null) "–" else money.format(v.setScale(2, RoundingMode.HALF_UP))

fun formatMoney(v: Double?): String = formatMoney(v?.let { BigDecimal.valueOf(it) })

/** "#FFD6E0" → Color (อ่านไม่ได้ = fallback) */
fun parseColor(hex: String, fallback: Color = Color(0xFFEEEEEE)): Color =
    try {
        if (hex.isBlank()) fallback else Color(hex.toColorInt())
    } catch (_: IllegalArgumentException) {
        fallback
    }

/** สีพาสเทลของหมวด/บัญชี ปรับให้เข้ากับโหมดมืด */
@Composable
@ReadOnlyComposable
fun tint(hex: String): Color {
    val p = LocalPalette.current
    val c = parseColor(hex, p.surface)
    return if (p.dark) lerp(p.card, c, 0.32f) else c
}
