package ism.dansha.core.engine

import ism.dansha.core.Bill
import ism.dansha.core.BillTemplate
import ism.dansha.core.Dates
import ism.dansha.core.ShopeeOrder
import kotlinx.serialization.Serializable
import kotlin.math.max

/* ภาพรวมรายรอบ + เช็คก่อนซื้อ + สถิติ Shopee — port จาก shopee.js */

@Serializable
data class DateRange(val start: String, val end: String)

@Serializable
data class CycleOutlook(
    val cycle: String,
    val range: DateRange,
    /** รายรับที่ยังไม่เข้า (ตามแผน/แม่แบบ) */
    val income: Double,
    /** รายรับทั้งรอบ (รวมที่เข้าแล้ว) ใช้คิดภาระหนี้ */
    val incomeFull: Double,
    /** รายจ่ายตามแผนที่ยังไม่จ่าย (ไม่รวมค่างวดหนี้ที่มีตัวคำนวณ) */
    val planned: Double,
    /** ค่างวดหนี้ที่ครบกำหนดในรอบ (จากตัวคำนวณ) */
    val debtDue: Double,
    /** รอบนี้: เงินที่มีตอนนี้ */
    val cashNow: Double?,
    val remaining: Double,
    val fromPlan: Boolean,
)

@Serializable
data class PurchaseOption(val tenor: Int, val installment: Double, val total: Double, val interest: Double, val estimated: Boolean = true)

@Serializable
data class ChosenPlan(val tenor: Int, val installment: Double, val total: Double, val interest: Double, val effectiveRate: Double)

@Serializable
data class CycleImpact(
    val cycle: String,
    val range: DateRange,
    val income: Double,
    val incomeFull: Double,
    val planned: Double,
    val debtDue: Double,
    val cashNow: Double?,
    val remaining: Double,
    val fromPlan: Boolean,
    val extra: Double,
    val after: Double,
    val burden: Double?,
    val burdenBefore: Double?,
)

/** red | yellow */
@Serializable
data class Flag(val level: String, val text: String)

@Serializable
data class PurchaseCheck(
    val price: Double,
    val method: String,
    val date: String,
    val chosen: ChosenPlan,
    val options: List<PurchaseOption>,
    val pctOfLeft: Double?,
    val firstPayPct: Double?,
    val remainingNow: Double,
    val impact: List<CycleImpact>,
    val laterCycles: List<String>,
    val worst: CycleImpact?,
    val maxBurden: Double?,
    val flags: List<Flag>,
    /** green | yellow | red */
    val verdict: String,
)

/** p = ราคา, วิธีจ่าย (spaylater | full), งวด, ค่างวดที่กรอก, วันที่ */
data class PurchaseQuery(
    val price: Double,
    val method: String,
    val tenor: Int? = null,
    val installment: Double? = null,
    val annualRate: Double? = null,
    val date: String? = null,
)

@Serializable
data class MonthStat(val month: String, var full: Double = 0.0, var spaylater: Double = 0.0, var count: Int = 0)

@Serializable
data class ShopStat(val shop: String, var total: Double = 0.0, var count: Int = 0)

@Serializable
data class ShopeeStats(
    val count: Int,
    val total: Double,
    val avg: Double,
    val installmentShare: Double,
    val interestTotal: Double,
    val byMonth: List<MonthStat>,
    val byShop: List<ShopStat>,
)

object Outlook {
    private val MONTHS_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")

    fun shiftCycle(cycle: String, delta: Int): String {
        var y = cycle.substring(0, 4).toInt()
        var m = cycle.substring(5, 7).toInt() - 1 + delta
        y += Math.floorDiv(m, 12)
        m = Math.floorMod(m, 12)
        return "%d-%02d %s-%s".format(y, m + 1, MONTHS_EN[m], MONTHS_EN[(m + 1) % 12])
    }

    /**
     * ภาพรวมรายรอบ (รอบนี้ + รอบถัดไป): รายรับที่คาด / รายจ่ายตามแผน / ค่างวดหนี้ / คงเหลือ
     * รอบนี้ใช้คงเหลือคาดการณ์จริง, รอบถัดไปใช้แผนบิลของรอบนั้นถ้ามี ไม่มีใช้แม่แบบ
     * debt ต้องคำนวณแบบมีตารางผ่อน (withSchedule)
     */
    fun cycles(
        count: Int,
        currentCycle: String,
        startDay: Int,
        today: String,
        cashNow: Double,
        bills: List<Bill>,
        templates: List<BillTemplate>,
        debt: Map<String, DebtState>,
    ): List<CycleOutlook> {
        class Item(val group: String, val amount: Double, val to: String)
        val activeTemplates = templates.filter { it.active }
        val out = ArrayList<CycleOutlook>()
        for (i in 0 until count) {
            val cycle = shiftCycle(currentCycle, i)
            val (rs, re) = Dates.payCycleRange(cycle, startDay)
            val range = DateRange(rs.toString(), re.toString())
            val rows = bills.filter { it.pay_cycle == cycle }
            val items = if (rows.isNotEmpty()) rows.map { b -> Item(b.group, if (b.status == "paid") 0.0 else b.est_amount.num(), b.to_account_id) }
            else activeTemplates.map { t -> Item(t.group, t.est_amount.num(), t.to_account_id) }
            // ค่างวดหนี้ของรอบ (จากตัวคำนวณ) — แทนยอดในแผนของบัญชีที่ผูก
            var debtDue = 0.0
            debt.values.forEach { st ->
                val cur = st.currentBill
                val list = (if (cur != null) listOf(cur.due to cur.amount) else emptyList()) + (st.bills ?: emptyList()).map { it.due to it.totalDue }
                val seen = HashSet<String>()
                list.forEach { (due, amt) ->
                    if (due !in seen && due >= range.start && due <= range.end && due >= today) {
                        seen += due
                        debtDue += amt
                    }
                }
            }
            val income = items.filter { it.group == "income" }.fold(0.0) { s, x -> s + x.amount }
            val planned = items.filter { x -> x.group != "income" && !(x.to.isNotEmpty() && debt.containsKey(x.to)) }.fold(0.0) { s, x -> s + x.amount }
            val incomeFull = if (rows.isNotEmpty()) rows.filter { it.group == "income" }
                .fold(0.0) { s, b -> s + (if (b.status == "paid") b.actual_amount else b.est_amount).num() } else income
            val base = if (i == 0) cashNow + income else income
            out += CycleOutlook(
                cycle = cycle, range = range, income = round2(income), incomeFull = round2(incomeFull), planned = round2(planned),
                debtDue = round2(debtDue), cashNow = if (i == 0) round2(cashNow) else null,
                remaining = round2(base - planned - debtDue), fromPlan = rows.isNotEmpty(),
            )
        }
        return out
    }

    /** เช็คก่อนซื้อ: ผลกระทบต่อเงินแต่ละรอบ + คำเตือน (cycles = ภาพรวม 7 รอบ) */
    fun checkPurchase(p: PurchaseQuery, today: String, startDay: Int, cfg: Map<String, String>, cycles: List<CycleOutlook>): PurchaseCheck {
        val price = p.price
        if (!(price > 0)) throw EngineException("กรุณากรอกราคา")
        val date = p.date?.ifEmpty { null } ?: today
        val spl = p.method == "spaylater"
        val tenor = if (spl) max(1, (p.tenor ?: 0).let { if (it == 0) 1 else it }) else 1
        val givenInst = p.installment?.takeIf { it != 0.0 && !it.isNaN() }
        val inst = if (spl) (if (tenor == 1) price else (givenInst ?: round2(price / tenor))) else price

        // ค่างวดใหม่ตกอยู่ในรอบไหน
        val add = LinkedHashMap<String, Double>()
        if (spl) {
            for (k in 1..tenor) {
                val c = Dates.payCycleOf(SPayLater.dueDate(date, k), startDay)
                add[c] = round2((add[c] ?: 0.0) + (if (k == tenor && givenInst == null && tenor > 1) round2(price - inst * (tenor - 1)) else inst))
            }
        } else {
            add[Dates.payCycleOf(date, startDay)] = price
        }
        val impact = cycles.map { c ->
            val extra = add[c.cycle] ?: 0.0
            val after = round2(c.remaining - extra)
            CycleImpact(
                c.cycle, c.range, c.income, c.incomeFull, c.planned, c.debtDue, c.cashNow, c.remaining, c.fromPlan,
                extra = extra, after = after,
                burden = if (c.incomeFull > 0) round2(((c.debtDue + (if (spl) extra else 0.0)) / c.incomeFull) * 100) else null,
                burdenBefore = if (c.incomeFull > 0) round2((c.debtDue / c.incomeFull) * 100) else null,
            )
        }
        val later = add.keys.filter { c -> cycles.none { it.cycle == c } }

        // เทียบจ่ายเต็ม vs ผ่อน (ประมาณด้วยดอก 25%/ปี ถ้าไม่ได้กรอกอัตรา)
        val rateGuess = (p.annualRate ?: 0.0).orIfFalsy(25.0) / 100
        fun est(n: Int): Double {
            if (n == 1) return price
            val r = rateGuess / 12
            return round2(price * r / (1 - jsPow(1 + r, -n.toDouble())))
        }
        val options = listOf(1, 3, 6, 10, 12).map { n -> PurchaseOption(n, est(n), round2(est(n) * n), round2(est(n) * n - price)) }
        val total = round2(if (spl) (if (tenor == 1) price else inst * tenor) else price)
        val interest = round2(total - price)
        val chosen = ChosenPlan(
            tenor, inst, total, interest,
            effectiveRate = if (tenor > 1 && interest > 0) round2(SPayLater.impliedRate(price, inst, tenor) * 100) else 0.0,
        )

        val now = impact[0]
        val warnPct = jsNumber(cfg["shop_warn_pct"]).orIfFalsy(30.0)
        val buffer = jsNumber(cfg["shop_buffer"]).orIfFalsy(0.0)
        val pctOfLeft = if (now.remaining > 0) round2((price / now.remaining) * 100) else null
        val firstPayPct = if (now.remaining > 0) round2(((add[now.cycle] ?: 0.0) / now.remaining) * 100) else null
        val worst = impact.fold<CycleImpact, CycleImpact?>(null) { w, c -> if (w == null || c.after < w.after) c else w }
        val withIncome = impact.filter { it.burden != null }
        val maxBurden = if (withIncome.isNotEmpty()) withIncome.fold(0.0) { m, c -> max(m, c.burden!!) } else null

        val flags = ArrayList<Flag>()
        if (pctOfLeft != null && pctOfLeft >= warnPct) {
            flags += Flag(if (pctOfLeft >= 50) "red" else "yellow", "ราคาเต็มคิดเป็น ${jsNumStr(pctOfLeft)}% ของเงินที่เหลือรอบนี้")
        }
        if (impact.none { it.incomeFull > 0 }) flags += Flag("yellow", "ยังไม่มีรายรับในแผน — ตั้งแม่แบบ \"เงินเดือน\" ในหน้าแผนบิล ผลเช็คจะแม่นขึ้น")
        if (now.remaining < 0) flags += Flag("red", "รอบนี้เงินคงเหลือคาดการณ์ติดลบอยู่แล้ว")
        impact.forEach { c ->
            if (c.extra > 0 && c.after < buffer) {
                flags += Flag("red", "รอบ ${c.cycle.substring(8)} จะเหลือ ${toFixed2(c.after)} บาท (ต่ำกว่าเงินกันไว้ ${jsNumStr(buffer)})")
            }
        }
        // null >= 40 ใน JS = false
        if (maxBurden != null && maxBurden >= 40) flags += Flag("red", "ภาระค่างวดหนี้สูงสุด ${jsNumStr(maxBurden)}% ของรายรับ (เกิน 40%)")
        else if (maxBurden != null && maxBurden >= 30) flags += Flag("yellow", "ภาระค่างวดหนี้สูงสุด ${jsNumStr(maxBurden)}% ของรายรับ")
        if (chosen.interest > 0) flags += Flag("yellow", "ผ่อนแล้วเสียดอกรวม ${toFixed2(chosen.interest)} บาท")
        val verdict = when {
            flags.any { it.level == "red" } -> "red"
            flags.any { it.level == "yellow" } -> "yellow"
            else -> "green"
        }
        return PurchaseCheck(
            price = price, method = p.method, date = date, chosen = chosen, options = options,
            pctOfLeft = pctOfLeft, firstPayPct = firstPayPct, remainingNow = now.remaining,
            impact = impact, laterCycles = later, worst = worst, maxBurden = maxBurden, flags = flags, verdict = verdict,
        )
    }

    /** สถิติพฤติกรรมช้อปปิ้ง */
    fun shopeeStats(all: List<ShopeeOrder>): ShopeeStats {
        val orders = all.filter { it.status != "cancelled" }
        val byMonth = LinkedHashMap<String, MonthStat>()
        val byShop = LinkedHashMap<String, ShopStat>()
        var installmentTotal = 0.0
        var interestTotal = 0.0
        orders.forEach { o ->
            val m = o.order_date.take(7)
            val x = byMonth.getOrPut(m) { MonthStat(m) }
            if (o.pay_method == "spaylater") x.spaylater += o.price.num() else x.full += o.price.num()
            x.count++
            val shop = o.shop.ifEmpty { "(ไม่ระบุร้าน)" }
            val s = byShop.getOrPut(shop) { ShopStat(shop) }
            s.total += o.price.num()
            s.count++
            if (o.pay_method == "spaylater" && (o.tenor ?: 0) > 1) {
                installmentTotal += o.price.num()
                interestTotal += max(0.0, o.installment.num() * (o.tenor ?: 0) - o.price.num())
            }
        }
        val total = orders.fold(0.0) { s, o -> s + o.price.num() }
        return ShopeeStats(
            count = orders.size, total = round2(total), avg = if (orders.isNotEmpty()) round2(total / orders.size) else 0.0,
            installmentShare = if (total != 0.0) round2((installmentTotal / total) * 100) else 0.0,
            interestTotal = round2(interestTotal),
            byMonth = byMonth.keys.sorted().map { byMonth.getValue(it) },
            byShop = byShop.values.sortedByDescending { it.total }.take(8),
        )
    }
}
