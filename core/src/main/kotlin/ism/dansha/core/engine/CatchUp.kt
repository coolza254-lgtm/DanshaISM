package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * กลับมาจดต่อ (แอพ Android): ไม่ได้จดหลายวันแล้วอยากกลับมาใช้
 *  1. จดย้อนหลังหลายรายการทีเดียว: บรรทัดวันที่ (28/10, 28 ต.ค., เมื่อวาน) ตามด้วยรายการแบบจดไว (กาแฟ 35 สด)
 *  2. บิลในแผนที่ถึงกำหนดแล้วแต่ยังไม่ได้กด
 *  3. ยอดจริงของแต่ละบัญชี → ส่วนต่างที่เหลือบันทึกเป็น "ยอดที่ไม่ได้จด" รายการเดียว (source = adjust)
 * บัญชีหนี้ที่มีตัวคำนวณ (Ascend/SPayLater) ไม่ปรับยอดที่นี่ ใช้หน้าเทียบยอดของหนี้แทน
 */
object CatchUp {
    /** source ของรายการปรับยอด */
    const val ADJUST = "adjust"

    /** ไม่ได้จดกี่วันถึงจะชวนกลับมาจดต่อ */
    const val REMIND_DAYS = 2

    /** lastEntry = วันที่จดล่าสุด (ตามเวลาที่บันทึก), days = ผ่านมากี่วัน */
    data class Gap(val lastEntry: String?, val days: Int)

    /** วันที่เริ่มใหม่ (ล้างประวัติ) นับเป็นวันที่จดล่าสุดด้วย */
    fun gap(d: DanshaData, today: LocalDate = Dates.today()): Gap {
        val last = (d.transactions.mapNotNull { t ->
            t.created_at.take(10).takeIf { isIso(it) } ?: t.date.takeIf { isIso(it) }
        } + listOfNotNull(d.config[FreshStart.KEY]?.takeIf { isIso(it) })).maxOrNull() ?: return Gap(null, 0)
        return Gap(last, ChronoUnit.DAYS.between(LocalDate.parse(last), today).toInt().coerceAtLeast(0))
    }

    private fun isIso(s: String) = Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(s)

    // ---------- 1. จดย้อนหลัง ----------

    /** บรรทัดรายการที่อ่านแล้ว: error ไม่ว่าง = บันทึกไม่ได้, duplicate = มีรายการวัน/ยอด/บัญชีเดียวกันอยู่แล้ว */
    data class Line(
        val text: String,
        val date: String,
        val type: String,
        val amount: Double?,
        val categoryId: String,
        val subcategoryId: String,
        val accountId: String,
        val note: String,
        val error: String?,
        val duplicate: Boolean = false,
    ) {
        val ok: Boolean get() = error == null && amount != null
    }

    private val MONTHS = listOf(
        listOf("มค", "มกรา", "มกราคม", "jan"), listOf("กพ", "กุมภา", "กุมภาพันธ์", "feb"), listOf("มีค", "มีนา", "มีนาคม", "mar"),
        listOf("เมย", "เมษา", "เมษายน", "apr"), listOf("พค", "พฤษภา", "พฤษภาคม", "may"), listOf("มิย", "มิถุนา", "มิถุนายน", "jun"),
        listOf("กค", "กรกฎา", "กรกฎาคม", "jul"), listOf("สค", "สิงหา", "สิงหาคม", "aug"), listOf("กย", "กันยา", "กันยายน", "sep"),
        listOf("ตค", "ตุลา", "ตุลาคม", "oct"), listOf("พย", "พฤศจิกา", "พฤศจิกายน", "nov"), listOf("ธค", "ธันวา", "ธันวาคม", "dec"),
    )

    private val ISO = Regex("""^(\d{4})-(\d{1,2})-(\d{1,2})""")
    private val SLASH = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?(?=\s|:|$)""")
    private val NAMED = Regex("""^(\d{1,2})\s*([ก-๙a-zA-Z.]+?)\.?(?:\s+(\d{4}|\d{2}))?(?=\s|:|$)""")

    /** ค.ศ. จากปีที่พิมพ์: 69 / 2569 (พ.ศ.) หรือ 26 / 2026 */
    private fun yearOf(s: String?): Int? {
        val y = s?.toIntOrNull() ?: return null
        return when {
            y >= 2400 -> y - 543
            y >= 1900 -> y
            y >= 43 -> 2500 + y - 543
            else -> 2000 + y
        }
    }

    /** ไม่ระบุปี = ปีนี้ ถ้าเลยวันนี้ไปเกินเดือน (เช่นพิมพ์ 25/12 ตอนต้นปี) = ปีที่แล้ว */
    private fun dateOf(day: Int, month: Int, year: Int?, today: LocalDate): LocalDate? = runCatching {
        if (year != null) return@runCatching LocalDate.of(year, month, day)
        val d = LocalDate.of(today.year, month, day)
        if (d.isAfter(today.plusDays(31))) d.minusYears(1) else d
    }.getOrNull()

    /**
     * วันที่ที่ขึ้นต้นบรรทัด → (วันที่, ข้อความที่เหลือ) — ไม่ใช่วันที่ = null
     * วันที่ผิด (เช่น 31/02) = ("", ข้อความที่เหลือ)
     */
    fun leadingDate(line: String, today: LocalDate): Pair<String, String>? {
        val s = line.trim()
        fun rest(n: Int) = s.substring(n).trimStart(':', ' ', '-', '–').trim()
        for ((word, back) in listOf("เมื่อวานซืน" to 2L, "เมื่อวาน" to 1L, "วันนี้" to 0L)) {
            if (s.startsWith(word)) return today.minusDays(back).toString() to rest(word.length)
        }
        ISO.find(s)?.let { m ->
            val (y, mo, d) = m.destructured
            return (dateOf(d.toInt(), mo.toInt(), y.toInt(), today)?.toString() ?: "") to rest(m.range.last + 1)
        }
        SLASH.find(s)?.let { m ->
            val (d, mo, y) = m.destructured
            // "7/11 ขนม 45" = ร้านเซเว่น ไม่ใช่วันที่ (บรรทัด "7/11" เดี่ยวๆ ยังเป็นวันที่)
            if (m.value == "7/11" && rest(m.range.last + 1).isNotEmpty()) return null
            return (dateOf(d.toInt(), mo.toInt(), yearOf(y.ifEmpty { null }), today)?.toString() ?: "") to rest(m.range.last + 1)
        }
        NAMED.find(s)?.let { m ->
            val (d, name, y) = m.destructured
            val key = name.replace(".", "").lowercase()
            val month = MONTHS.indexOfFirst { names -> key in names }
            if (month >= 0) return (dateOf(d.toInt(), month + 1, yearOf(y.ifEmpty { null }), today)?.toString() ?: "") to rest(m.range.last + 1)
        }
        return null
    }

    /**
     * อ่านข้อความหลายบรรทัด: บรรทัดวันที่เปลี่ยนวันของบรรทัดถัดๆ ไป (ก่อนมีวันที่ = defaultDate)
     * "28/10 กาแฟ 35" = วันที่ + รายการในบรรทัดเดียว · หมวด/บัญชีเดาแบบจดไว แต่ยอดต้องพิมพ์เอง
     */
    fun parse(d: DanshaData, text: String, today: LocalDate = Dates.today(), defaultDate: String = today.toString()): List<Line> {
        var date = defaultDate
        val out = ArrayList<Line>()
        text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.forEach { raw ->
            var body = raw
            var badDate = false
            leadingDate(raw, today)?.let { (dt, rest) ->
                if (dt.isEmpty()) badDate = true else date = dt
                body = rest
            }
            if (body.isEmpty() && !badDate) return@forEach
            fun error(msg: String) { out += Line(raw, date, "expense", null, "", "", "", body, msg) }
            when {
                badDate -> error("วันที่ไม่ถูกต้อง")
                date > today.toString() -> error("วันที่ยังไม่ถึง")
                else -> {
                    val p = QuickEntry.parse(d, body)
                    when {
                        p.amount == null || !(p.amount > 0) -> error("ไม่มียอดเงิน")
                        p.type == "transfer" -> error("รายการโอน ให้จดในหน้าจดรายการ")
                        else -> {
                            // ไม่ให้คะแนนช่วงเวลาของวัน (จดย้อนหลัง เวลาตอนนี้ไม่เกี่ยว)
                            val g = QuickEntry.suggest(d, body, today, limit = 1, hour = -100).firstOrNull()
                            val type = p.type ?: g?.type ?: "expense"
                            val acc = p.accountId ?: g?.accountId?.ifEmpty { null } ?: Forms.defaultAccount(d, type)
                            val dup = d.transactions.any { t ->
                                t.date == date && t.type == type && t.account_id == acc && kotlin.math.abs(t.amount.num() - p.amount) < 0.005
                            }
                            out += Line(
                                raw, date, type, p.amount,
                                g?.categoryId.orEmpty(), g?.subcategoryId.orEmpty(), acc, p.text.ifEmpty { g?.note.orEmpty() },
                                if (acc.isEmpty()) "ยังไม่มีบัญชี" else null, dup,
                            )
                        }
                    }
                }
            }
        }
        return out
    }

    // ---------- 2. บิลที่ถึงกำหนดแล้ว ----------

    /** บิลในแผน (รอบนี้ + รอบก่อน) ที่ถึงกำหนดแล้วแต่ยังไม่ได้กด — ไม่รวมค่าเดินทางที่แอพคิดเอง และหนี้ที่ไม่มีบิล */
    fun dueBills(d: DanshaData, today: String = Dates.todayStr()): List<Bill> {
        val startDay = Engine.startDay(Engine.config(d))
        val current = Dates.payCycleOf(today, startDay)
        val cycles = setOf(current, Outlook.shiftCycle(current, -1))
        return d.bills.filter { b ->
            b.pay_cycle in cycles && b.status != "paid" && b.due_date.isNotEmpty() && b.due_date <= today &&
                b.template_id != PlanBook.AUTO_TRAVEL && !(b.to_account_id.isNotEmpty() && b.est_amount.num() == 0.0)
        }.sortedWith(compareBy({ it.due_date }, { it.sort ?: Int.MAX_VALUE }))
    }

    // ---------- 3. ยอดจริงของบัญชี ----------

    /** app = ยอดในแอพ (บัญชีเงิน: คงเหลือ, วงเงินที่ไม่มีตัวคำนวณ: ยอดใช้ไป) */
    data class BalanceRow(val account: Account, val app: Double, val isCredit: Boolean)

    /** บัญชีที่เปิดใช้และปรับยอดได้ (ไม่รวมหนี้ที่มีตัวคำนวณ) เรียงตามลำดับบัญชี */
    fun balanceRows(d: DanshaData, today: String = Dates.todayStr()): List<BalanceRow> {
        val views = Engine.accounts(d, Engine.debtStates(d, today, false))
        return views.filter { v -> v.account.active && !isEngineAccount(v.account) && !isSplAccount(v.account) }
            .sortedWith(compareBy({ it.account.sort ?: Int.MAX_VALUE }, { it.account.name }))
            .map { v -> BalanceRow(v.account, (if (v.isCredit) v.used else v.balance) ?: 0.0, v.isCredit) }
    }

    /** รายการปรับยอด: type (expense | income) + ยอด — ตรงกันแล้ว = null */
    data class Adjustment(val type: String, val amount: Double)

    /**
     * บัญชีเงิน: ยอดจริงน้อยกว่า = ใช้ไปโดยไม่ได้จด (รายจ่าย), มากกว่า = รายรับที่ไม่ได้จด
     * วงเงิน: ใช้ไปจริงมากกว่า = รายจ่ายที่ไม่ได้จด, น้อยกว่า = จ่ายคืนที่ไม่ได้จด (รายรับเข้าบัญชีวงเงิน)
     */
    fun adjustment(row: BalanceRow, actual: Double): Adjustment? {
        val diff = round2(actual - row.app)
        if (kotlin.math.abs(diff) < 0.005) return null
        val spent = if (row.isCredit) diff > 0 else diff < 0
        return Adjustment(if (spent) "expense" else "income", kotlin.math.abs(diff))
    }
}
