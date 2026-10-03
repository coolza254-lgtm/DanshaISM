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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.core.graphics.toColorInt
import ism.dansha.app.R
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.util.Locale

/*
 * ธีม 断捨ISM: สมุดจดการเงิน (กระดาษ + หมึก) สว่าง/มืด — สีทั้งแอพมาจากที่นี่ที่เดียว
 * หมึกน้ำเงิน = หลัก, ดินสอเขียว = บวก, หมึกแดง = ลบ, ปากกาไฮไลต์ = เน้น
 * ตัวอักษร: Sarabun อ่านง่าย (เนื้อหา/ตัวเลข), Mali ลายมือ (หัวข้อใหญ่เท่านั้น)
 */

@Immutable
data class Palette(
    val dark: Boolean,
    /** กระดาษ (พื้นหลังหน้าจอ) */
    val bg: Color,
    /** การ์ด / แผ่นกระดาษแปะ */
    val card: Color,
    /** พื้นรอง: ช่องกรอก ชิป แถบล่าง */
    val surface: Color,
    /** หมึกดำ (ตัวหนังสือ + เส้นขอบ) */
    val ink: Color,
    val muted: Color,
    val line: Color,
    /** ปากกาน้ำเงิน: ปุ่มหลัก */
    val primary: Color,
    val onPrimary: Color,
    val primarySoft: Color,
    /** ดินสอเขียว = บวก */
    val positive: Color,
    val positiveSoft: Color,
    /** หมึกแดง = ลบ */
    val negative: Color,
    val negativeSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val heroStart: Color,
    val heroEnd: Color,
    /** ปากกาไฮไลต์ */
    val highlight: Color,
    /** กระดาษโน้ตเหลือง / ฟ้า */
    val sticky: Color,
    val stickyBlue: Color,
    /** เส้นบรรทัด / เส้นขอบหน้าแดง */
    val rule: Color,
    val margin: Color,
    /** เทปวาชิ */
    val tape: Color,
)

val LightPalette = Palette(
    dark = false,
    bg = Color(0xFFFBF6EA), card = Color(0xFFFFFDF6), surface = Color(0xFFF3ECDD),
    ink = Color(0xFF2A2A33), muted = Color(0xFF5A534B), line = Color(0xFFD9D0BF),
    primary = Color(0xFF2B4C8C), onPrimary = Color(0xFFFFFDF6), primarySoft = Color(0xFFDCEBFF),
    positive = Color(0xFF3F7D4E), positiveSoft = Color(0xFFDDEFD9),
    negative = Color(0xFFC8463C), negativeSoft = Color(0xFFF8DEDA),
    warning = Color(0xFF9A6A12), warningSoft = Color(0xFFFFF1A8),
    heroStart = Color(0xFFFFFDF6), heroEnd = Color(0xFFFFFDF6),
    highlight = Color(0xFFFFE98A), sticky = Color(0xFFFFF1A8), stickyBlue = Color(0xFFDCEBFF),
    rule = Color(0xFFE3EAF2), margin = Color(0xFFE8A0A0), tape = Color(0xBFF0AABE),
)

val DarkPalette = Palette(
    dark = true,
    bg = Color(0xFF1E1C1A), card = Color(0xFF2A2724), surface = Color(0xFF34302B),
    ink = Color(0xFFEDE6D8), muted = Color(0xFFBDB3A4), line = Color(0xFF4A443C),
    primary = Color(0xFF9DBDF2), onPrimary = Color(0xFF14213A), primarySoft = Color(0xFF26344D),
    positive = Color(0xFF8FD3A6), positiveSoft = Color(0xFF233428),
    negative = Color(0xFFF2978C), negativeSoft = Color(0xFF3D2421),
    warning = Color(0xFFE8C46A), warningSoft = Color(0xFF3E3720),
    heroStart = Color(0xFF2A2724), heroEnd = Color(0xFF2A2724),
    highlight = Color(0xFF6E5E1C), sticky = Color(0xFF433B20), stickyBlue = Color(0xFF253247),
    rule = Color(0xFF2C2926), margin = Color(0xFF5E3434), tape = Color(0x99A0566A),
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
    val Highlight: Color @Composable @ReadOnlyComposable get() = LocalPalette.current.highlight
}

/** ตัวพิมพ์อ่านง่าย: เนื้อหาและตัวเลขทั้งหมด */
val Sarabun = FontFamily(
    Font(R.font.sarabun_regular, FontWeight.Normal),
    Font(R.font.sarabun_medium, FontWeight.Medium),
    Font(R.font.sarabun_semibold, FontWeight.SemiBold),
    Font(R.font.sarabun_bold, FontWeight.Bold),
)

/** ลายมือ: เฉพาะหัวข้อใหญ่ (ไม่ใช้กับตัวเลขเงิน) */
val Hand = FontFamily(
    Font(R.font.mali_semibold, FontWeight.SemiBold),
    Font(R.font.mali_bold, FontWeight.Bold),
)

private fun typography(): Typography {
    val t = Typography()
    // ตัวเลขกว้างเท่ากัน (tnum) ให้ยอดเงินเรียงตรงหลัก
    fun TextStyle.f() = copy(fontFamily = Sarabun, fontFeatureSettings = "tnum")
    return Typography(
        displayLarge = t.displayLarge.f(), displayMedium = t.displayMedium.f(), displaySmall = t.displaySmall.f(),
        headlineLarge = t.headlineLarge.f(), headlineMedium = t.headlineMedium.f(), headlineSmall = t.headlineSmall.f(),
        titleLarge = t.titleLarge.f(), titleMedium = t.titleMedium.f(), titleSmall = t.titleSmall.f(),
        bodyLarge = t.bodyLarge.f(), bodyMedium = t.bodyMedium.f(), bodySmall = t.bodySmall.f(),
        labelLarge = t.labelLarge.f(), labelMedium = t.labelMedium.f(), labelSmall = t.labelSmall.f(),
    )
}

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(12.dp), extraLarge = RoundedCornerShape(16.dp),
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

/** กระดาษมีเส้นบรรทัดทุก 32dp + เส้นขอบหน้าแดงด้านซ้าย (วาดหลังเนื้อหา) */
fun Modifier.notebookPaper(p: Palette, ruled: Boolean = true, marginX: Dp = 26.dp): Modifier = this
    .background(p.bg)
    .drawBehind {
        if (ruled) {
            val step = 32.dp.toPx()
            var y = step
            while (y < size.height) {
                drawLine(p.rule, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                y += step
            }
        }
        val x = marginX.toPx()
        drawLine(p.margin, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
    }

/** การ์ดกระดาษ: ขอบหมึก + เงาแข็งเยื้อง (เหมือนกระดาษแปะ) */
fun Modifier.paperCard(p: Palette, color: Color = p.card, radius: Dp = 6.dp, shadow: Boolean = true): Modifier {
    val shape = RoundedCornerShape(radius)
    return this
        .then(
            if (shadow) Modifier.drawBehind {
                val dx = 3.dp.toPx(); val dy = 4.dp.toPx()
                drawRoundRect(p.ink.copy(alpha = if (p.dark) 0.35f else 0.12f), topLeft = Offset(dx, dy), size = size, cornerRadius = CornerRadius(radius.toPx()))
            } else Modifier,
        )
        .background(color, shape)
        .border(1.5.dp, p.ink.copy(alpha = if (p.dark) 0.55f else 1f), shape)
}

/** อักษรนำของหมวด (ข้ามสระหน้า เ แ โ ใ ไ) เช่น "อาหาร" → อ, "เดินทาง" → ด */
fun categoryMark(name: String): String {
    val c = name.trimStart().firstOrNull { it !in "เแโใไ" } ?: return "•"
    return c.toString()
}
