package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import java.time.LocalDate

/*
 * แผนบิลแบบแบ่งหมวด (แอพ Android)
 *  รายรับ (เงินเดือน / Staff Claim / อื่นๆ) · รายจ่ายประจำ · หนี้สิน (ดึงยอดอัตโนมัติ) · ครั้งเดียว · ค่าเดินทาง · เงินที่วางแผนใช้
 * group เดิม (income / A / B) ยังใช้คำนวณยอดคงเหลือเหมือนระบบเดิม: section บอกแค่ว่าแสดงอยู่หมวดไหน
 */
object PlanBook {
    const val INCOME = "income"
    const val SALARY = "salary"
    const val CLAIM = "claim"
    const val FIXED = "fixed"
    const val DEBT = "debt"
    const val ONCE = "once"
    const val TRAVEL = "travel"
    const val PLANNED = "planned"

    /** หมวดตามลำดับที่แสดง */
    val SECTIONS = listOf(INCOME, FIXED, DEBT, ONCE, TRAVEL, PLANNED)

    /** หมวดที่เลือกได้ตอนเพิ่ม/แก้แผน (ไม่รวมหมวดที่แอพจัดการเอง) */
    val EDITABLE = listOf(SALARY, INCOME, FIXED, DEBT, ONCE, TRAVEL, PLANNED)

    /** template_id ของแถวที่แอพสร้าง/อัปเดตเอง */
    const val AUTO_DEBT = "auto:debt"
    const val AUTO_TRAVEL = "auto:travel"
    const val CLAIM_PREFIX = "claim:"

    /** group (ใช้คำนวณ) ของแต่ละหมวด */
    fun groupOf(section: String): String = when (section) {
        INCOME, SALARY, CLAIM -> "income"
        TRAVEL, PLANNED -> "B"
        else -> "A"
    }

    /** หมวดที่แสดงของแถว (แถวเก่าที่ยังไม่มี section เดาจาก group/ข้อมูลอื่น) */
    fun sectionOf(b: Bill): String = displaySection(b.section, b.group, b.to_account_id, b.template_id)

    fun sectionOf(t: BillTemplate): String = displaySection(t.section, t.group, t.to_account_id, t.id)

    private fun displaySection(section: String, group: String, toAccount: String, templateId: String): String = when {
        section == SALARY || section == CLAIM -> INCOME
        section.isNotEmpty() -> section
        group == "income" -> INCOME
        toAccount.isNotEmpty() -> DEBT
        group == "B" -> PLANNED
        templateId.isNotEmpty() -> FIXED
        else -> ONCE
    }

    // ---------- วันทำงาน / ค่าเดินทาง ----------

    /** วันทำงานของรอบ: key ใน config = "work:<yyyy-MM ของรอบ>", ค่า = "2026-10-16,2026-10-17*,…" (* = กะ 14:00–23:00 กลับ Grab) */
    fun workKey(cycle: String) = "work:" + cycle.take(7)

    data class WorkDay(val date: String, val late: Boolean)

    fun workDays(config: Map<String, String>, cycle: String): List<WorkDay> =
        config[workKey(cycle)].orEmpty().split(',').map { it.trim() }.filter { it.length >= 10 }
            .map { WorkDay(it.take(10), it.endsWith("*")) }.distinctBy { it.date }.sortedBy { it.date }

    fun encodeWorkDays(days: List<WorkDay>): String =
        days.sortedBy { it.date }.joinToString(",") { it.date + if (it.late) "*" else "" }

    /**
     * ค่ารถที่เผื่อไว้ตอนวางแผน: รถเมล์เที่ยวละ 20 (กรณีแพงสุดของ 8/12/20) — จ่ายจริงน้อยกว่าค่อยจดตามจริง
     * วันกะดึก (เลิก 23:00) = ขาไปรถเมล์ + ขากลับ Grab (ไม่มีรถเมล์ขากลับ)
     */
    data class TravelFares(val bus: Double, val grab: Double, val choices: List<Double>)

    fun fares(config: Map<String, String>): TravelFares = TravelFares(
        bus = config["travel_bus"]?.toDoubleOrNull() ?: 20.0,
        grab = config["travel_grab"]?.toDoubleOrNull() ?: 150.0,
        choices = config["travel_fares"].orEmpty().split(',').mapNotNull { it.trim().toDoubleOrNull() }.ifEmpty { listOf(8.0, 12.0, 20.0) },
    )

    const val NOTE_GO = "ค่ารถไปทำงาน"
    const val NOTE_BACK = "ค่ารถกลับบ้าน"

    /** วันหนึ่งในแผน: ที่เผื่อไว้ vs ที่จดจริง */
    data class TravelDay(
        val date: String,
        val late: Boolean,
        val go: Transaction?,
        /** ขากลับ: รถเมล์ (วันปกติ) หรือ Grab (วันกะดึก) */
        val back: Transaction?,
        val planGo: Double,
        val planBack: Double,
    ) {
        val paid: Double get() = (go?.amount.num()) + (back?.amount.num())
        val done: Boolean get() = go != null && back != null
        /** ยังต้องเตรียม = ขาที่ยังไม่จด */
        val pending: Double get() = (if (go == null) planGo else 0.0) + (if (back == null) planBack else 0.0)
        /** ประหยัดได้จากขาที่จดแล้ว (เผื่อ − จ่ายจริง) */
        val saved: Double get() = (go?.let { planGo - it.amount.num() } ?: 0.0) + (back?.let { planBack - it.amount.num() } ?: 0.0)
    }

    data class TravelPlan(
        val days: Int,
        val lateDays: Int,
        /** วันที่ยังจดไม่ครบ */
        val daysLeft: Int,
        val lateLeft: Int,
        /** เผื่อไว้ทั้งรอบ */
        val total: Double,
        /** ยังต้องเตรียม (ขาที่ยังไม่จด) */
        val remaining: Double,
        /** จ่ายจริงแล้ว (ค่าเดินทางที่จดในรอบนี้) */
        val spent: Double,
        /** ประหยัดได้จากที่เผื่อ */
        val saved: Double,
        val list: List<TravelDay>,
    )

    /** ขาไป/ขากลับที่จดไว้ของวันนั้น */
    fun legs(d: DanshaData, date: String): Pair<Transaction?, Transaction?> {
        val day = d.transactions.filter { it.date == date && isTravelTxn(it) }
        val go = day.firstOrNull { it.note == NOTE_GO }
        val back = day.firstOrNull { it.note == NOTE_BACK || it.claim.isNotEmpty() }
        return go to back
    }

    fun travel(d: DanshaData, cycle: String, today: String = Dates.todayStr()): TravelPlan {
        val cfg = Engine.config(d)
        val f = fares(cfg)
        val list = workDays(cfg, cycle).map { w ->
            val (go, back) = legs(d, w.date)
            TravelDay(w.date, w.late, go, back, f.bus, if (w.late) f.grab else f.bus)
        }
        val (rs, re) = Dates.payCycleRange(cycle, Engine.startDay(cfg))
        val spent = d.transactions.filter { isTravelTxn(it) && it.date >= rs.toString() && it.date <= re.toString() }.sumOf { it.amount.num() }
        val open = list.filter { !it.done }
        return TravelPlan(
            days = list.size, lateDays = list.count { it.late }, daysLeft = open.size, lateLeft = open.count { it.late },
            total = round2(list.sumOf { it.planGo + it.planBack }), remaining = round2(list.sumOf { it.pending }),
            spent = round2(spent), saved = round2(list.sumOf { it.saved }), list = list,
        )
    }

    /** รายการค่าเดินทางไปทำงาน (จดจากปุ่มค่ารถ หรือ Grab ที่เบิกได้) */
    fun isTravelTxn(t: Transaction): Boolean =
        t.type == "expense" && (t.source == "travel" || t.claim.isNotEmpty())

    // ---------- Staff Claim ----------

    /**
     * วันได้เงินเบิกคืน: ส่งใบเบิกภายในวันที่ 15 → ได้วันที่ 30 เดือนนั้น, หลังวันที่ 15 → วันที่ 30 เดือนถัดไป
     * เดือนที่ไม่มีวันที่ 30 (ก.พ.) ใช้วันสุดท้ายของเดือน
     */
    fun claimPayDate(submitted: String): String {
        val s = LocalDate.parse(submitted.take(10))
        val month = if (s.dayOfMonth <= 15) s.withDayOfMonth(1) else s.withDayOfMonth(1).plusMonths(1)
        return month.withDayOfMonth(minOf(30, month.lengthOfMonth())).toString()
    }

    fun openClaims(d: DanshaData): List<Transaction> = d.transactions.filter { it.claim == "open" }.sortedBy { it.date }

    /** บัญชีที่จ่าย Grab (TrueMoney) / รับเงินเบิก (Bangkok Bank): ตั้งเองได้ ไม่ตั้ง = เดาจากชื่อ */
    fun claimPayAccount(d: DanshaData): String = pickAccount(d, Engine.config(d)["claim_pay_account_id"], listOf("true"))

    fun claimReceiveAccount(d: DanshaData): String = pickAccount(d, Engine.config(d)["claim_account_id"], listOf("bangkok", "bbl", "กรุงเทพ"))

    fun cashAccount(d: DanshaData): String = pickAccount(d, Engine.config(d)["travel_account_id"], emptyList(), cash = true)

    private fun pickAccount(d: DanshaData, configured: String?, names: List<String>, cash: Boolean = false): String {
        val active = d.accounts.filter { it.active }
        configured?.takeIf { id -> active.any { it.id == id } }?.let { return it }
        val hit: Account? = if (cash) active.firstOrNull { it.type == "cash" }
        else active.firstOrNull { a -> names.any { a.name.lowercase().contains(it) || a.icon.lowercase().contains(it) } }
        return hit?.id ?: Forms.defaultAccount(d, "expense")
    }

    // ---------- เพิ่มหลายรายการจากข้อความ ----------

    /** 1 บรรทัด = หมวด | ชื่อ | ยอด | วันที่ (ไม่บังคับ) | โน้ต (ไม่บังคับ) */
    data class BulkItem(val section: String, val name: String, val amount: Double, val day: Int?, val note: String) {
        /** ครั้งเดียว / วางแผนใช้ = ใส่ในรอบที่เลือก, ที่เหลือ = แม่แบบทุกเดือน */
        val oneOff: Boolean get() = section == ONCE || section == PLANNED
    }

    data class BulkLine(val line: String, val item: BulkItem?, val error: String?)

    private val SECTION_WORDS = listOf(
        listOf("เงินเดือน", "salary") to SALARY,
        listOf("รายรับ", "income") to INCOME,
        listOf("ประจำ", "รายจ่ายประจำ", "fixed", "บิล") to FIXED,
        listOf("หนี้", "หนี้สิน", "debt") to DEBT,
        listOf("ครั้งเดียว", "once") to ONCE,
        listOf("เดินทาง", "ค่าเดินทาง", "travel") to TRAVEL,
        listOf("วางแผน", "วางแผนใช้", "planned") to PLANNED,
    )

    fun parseBulk(text: String): List<BulkLine> = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { line ->
        val f = line.split('|').map { it.trim() }
        val sec = SECTION_WORDS.firstOrNull { (w, _) -> f[0].lowercase() in w }?.second
        val amount = f.getOrNull(2)?.let { Forms.parseAmount(it) }
        val day = f.getOrNull(3)?.filter(Char::isDigit)?.toIntOrNull()
        when {
            f.size < 3 -> BulkLine(line, null, "ต้องมีอย่างน้อย หมวด | ชื่อ | ยอด")
            sec == null -> BulkLine(line, null, "ไม่รู้จักหมวด \"${f[0]}\"")
            f[1].isEmpty() -> BulkLine(line, null, "ไม่มีชื่อรายการ")
            amount == null || amount < 0 -> BulkLine(line, null, "ยอดไม่ถูกต้อง")
            day != null && day !in 1..31 -> BulkLine(line, null, "วันที่ต้องอยู่ระหว่าง 1–31")
            else -> BulkLine(line, BulkItem(sec, f[1], amount, day, f.drop(4).joinToString(" | ")), null)
        }
    }

    // ---------- หนี้ในแผน ----------

    /** ยอดบิลหนี้ที่ครบกำหนดในรอบ (ตามตัวคำนวณ) ของแต่ละบัญชีวงเงิน: accountId → (วันครบ, ยอด) */
    fun debtDues(d: DanshaData, cycle: String, states: Map<String, DebtState>): Map<String, Pair<String, Double>> {
        val (rs, re) = Dates.payCycleRange(cycle, Engine.startDay(Engine.config(d)))
        val out = LinkedHashMap<String, Pair<String, Double>>()
        d.accounts.filter { it.active && (isEngineAccount(it) || isSplAccount(it)) }.forEach { a ->
            val st = states[a.id] ?: return@forEach
            val cur = st.currentBill
            val list = (if (cur != null) listOf(cur.due to cur.amount) else emptyList()) + (st.bills ?: emptyList()).map { it.due to it.totalDue }
            list.firstOrNull { it.first >= rs.toString() && it.first <= re.toString() }?.let { out[a.id] = it }
        }
        return out
    }
}
