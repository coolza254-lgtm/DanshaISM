package ism.dansha.core.engine

import java.math.BigDecimal
import java.math.RoundingMode

/*
 * ตัวช่วยให้ผลคำนวณตรงกับ JavaScript ของระบบเดิมทุกหลัก
 * (ตัวเลขระหว่างทางเป็น Double เหมือน JS แล้วปัดตามลำดับเดิม)
 */

/** Number.EPSILON ของ JS */
const val JS_EPSILON = 2.220446049250313E-16

/** Math.round ของ JS: ปัดครึ่งขึ้นไปทาง +∞, NaN/∞ คืนค่าเดิม */
fun jsRound(x: Double): Double =
    if (x.isNaN() || x.isInfinite() || kotlin.math.abs(x) >= 4.503599627370496E15) x else Math.round(x).toDouble()

/** round2_ (store.js): Math.round(n × 100) / 100 */
fun round2(n: Double): Double = jsRound(n * 100) / 100

/** r2 ของ debt-engine: Math.round((n + EPSILON) × 100) / 100 */
fun r2(n: Double): Double = jsRound((n + JS_EPSILON) * 100) / 100

fun round6(n: Double): Double = jsRound(n * 1e6) / 1e6

/** Math.pow ของ JS (V8 ใช้ fdlibm เหมือน StrictMath) */
fun jsPow(a: Double, b: Double): Double = StrictMath.pow(a, b)

/** Number(x) ของคอลัมน์ตัวเลข: ว่าง = 0 */
fun BigDecimal?.num(): Double = this?.toDouble() ?: 0.0

/** Number(x) || fallback */
fun Double.orIfFalsy(fallback: Double): Double = if (this == 0.0 || isNaN()) fallback else this

/** แปลงตัวเลขเป็นข้อความแบบ JS (String(n)) สำหรับค่าที่ปัดแล้ว เช่น 50, 92.03, -1587.63 */
fun jsNumStr(d: Double): String {
    if (d.isNaN()) return "NaN"
    if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
    if (d == Math.floor(d) && kotlin.math.abs(d) < 1e15) return d.toLong().toString()
    return BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
}

/** n.toFixed(2) ของ JS (ปัดจากค่าจริงของ Double แบบครึ่งออกจากศูนย์) */
fun toFixed2(d: Double): String {
    val s = BigDecimal(d).setScale(2, RoundingMode.HALF_UP).toPlainString()
    return if (d < 0 && !s.startsWith("-")) "-$s" else s
}

/** Double → BigDecimal สำหรับเก็บลงตาราง (ค่าเดียวกับที่ JS เขียนลง JSON) */
fun Double.toDec(): BigDecimal {
    val b = BigDecimal(this.toString()).stripTrailingZeros()
    return if (b.signum() == 0) BigDecimal.ZERO else b
}
