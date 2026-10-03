package ism.dansha.core.engine

import ism.dansha.core.Transaction
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

/*
 * โครงการร่วมจ่ายของรัฐ (ไทยช่วยไทยพลัส 60/40) ผ่าน G-Wallet — port จาก copay.js
 *  - ผู้ใช้กรอก "ราคาเต็ม" → รัฐจ่าย = min(ราคา × %, เหลือวันนี้, เหลือเดือนนี้, เหลือทั้งโครงการ)
 *  - ส่วนที่เหลือคือเงินของเราเองใน G-Wallet (= amount ของรายการ)
 *  - เพดานรายวัน/รายเดือนตัดตามวันปฏิทิน ไม่สะสม
 */

@Serializable
data class CopayConfig(
    val enabled: Boolean,
    val name: String,
    val accountId: String,
    val rate: Double,
    val dailyCap: Double,
    val monthlyCap: Double,
    val totalCap: Double,
    val start: String,
    val end: String,
    val hours: String,
)

@Serializable
data class CopayUsage(
    val date: String,
    val day: Double,
    val month: Double,
    val total: Double,
    val dayLeft: Double,
    val monthLeft: Double,
    val totalLeft: Double,
) {
    /** รัฐยังช่วยได้อีกเท่าไหร่ในวันนี้ */
    val leftToday: Double get() = min(min(dayLeft, monthLeft), totalLeft)
}

@Serializable
data class CopaySplit(val gov: Double, val self: Double, val reason: String? = null)

@Serializable
data class CopayUsed(val id: String, val date: String, val gov_subsidy: Double)

@Serializable
data class CopayStatus(
    val config: CopayConfig,
    val today: String,
    val active: Boolean,
    val usage: CopayUsage,
    val used: List<CopayUsed>,
)

object Copay {
    fun config(cfg: Map<String, String>): CopayConfig {
        fun num(k: String) = jsNumber(cfg[k]).orIfFalsy(0.0)
        return CopayConfig(
            enabled = cfg["copay_enabled"] == "true",
            name = cfg["copay_name"].orEmpty().ifEmpty { "โครงการร่วมจ่าย" },
            accountId = cfg["copay_account_id"].orEmpty(),
            rate = num("copay_gov_rate") / 100,
            dailyCap = num("copay_daily_cap"),
            monthlyCap = num("copay_monthly_cap"),
            totalCap = num("copay_total_cap"),
            start = cfg["copay_start"].orEmpty(),
            end = cfg["copay_end"].orEmpty(),
            hours = cfg["copay_hours"].orEmpty(),
        )
    }

    private fun inProgram(t: Transaction, cc: CopayConfig, excludeId: String?) =
        t.gov_subsidy.num() > 0 && t.id != excludeId && t.date >= cc.start && t.date <= cc.end

    /** ยอดที่รัฐช่วยไปแล้ว ณ วันที่ date (ไม่นับรายการ excludeId — ใช้ตอนแก้ไข) */
    fun usage(txns: List<Transaction>, cc: CopayConfig, date: String, excludeId: String? = null): CopayUsage {
        val used = txns.filter { inProgram(it, cc, excludeId) }
        fun sum(rows: List<Transaction>) = round2(rows.fold(0.0) { a, t -> a + t.gov_subsidy.num() })
        val day = sum(used.filter { it.date == date })
        val month = sum(used.filter { it.date.take(7) == date.take(7) })
        val total = sum(used)
        return CopayUsage(
            date, day, month, total,
            dayLeft = round2(max(0.0, cc.dailyCap - day)),
            monthLeft = round2(max(0.0, cc.monthlyCap - month)),
            totalLeft = round2(max(0.0, cc.totalCap - total)),
        )
    }

    /** ส่วนแบ่ง: รัฐจ่าย / จ่ายเอง */
    fun split(price: Double, cc: CopayConfig, usage: CopayUsage): CopaySplit {
        val active = cc.enabled && usage.date >= cc.start && usage.date <= cc.end
        if (!active) return CopaySplit(0.0, round2(price), "นอกช่วงโครงการ")
        val gov = round2(min(min(min(price * cc.rate, usage.dayLeft), usage.monthLeft), usage.totalLeft))
        return CopaySplit(max(0.0, gov), round2(price - max(0.0, gov)))
    }

    fun status(txns: List<Transaction>, cfg: Map<String, String>, today: String): CopayStatus? {
        val cc = config(cfg)
        if (!cc.enabled) return null
        return CopayStatus(
            config = cc,
            today = today,
            active = today >= cc.start && today <= cc.end,
            usage = usage(txns, cc, today),
            used = txns.filter { inProgram(it, cc, null) }.map { CopayUsed(it.id, it.date, it.gov_subsidy.num()) },
        )
    }
}

/** Number(s) ของ JS สำหรับค่าใน config (ข้อความ): ว่าง = 0, อ่านไม่ได้ = NaN */
fun jsNumber(s: String?): Double {
    val t = s?.trim() ?: return 0.0
    if (t.isEmpty()) return 0.0
    return t.toDoubleOrNull()?.takeIf { !t.endsWith("d", true) && !t.endsWith("f", true) } ?: Double.NaN
}
