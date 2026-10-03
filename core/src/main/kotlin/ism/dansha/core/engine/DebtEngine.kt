package ism.dansha.core.engine

import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/*
 * ตัวจำลองสินเชื่อ Ascend PayNext / PayNext Extra — port จาก debt-engine.js แบบบรรทัดต่อบรรทัด
 * (ตรวจกับ statement จริง 23 ฉบับในระบบเดิม) ห้ามเปลี่ยนลำดับการคำนวณหรือการปัดเศษ
 *
 *  - รอบบิล 16–15, ออกบิลวันที่ 15, ครบกำหนดวันที่ 1 ของเดือนถัดไป
 *  - ค่างวด = P·r / (1 − (1+r)^−n), r = อัตราต่อปี / 12, ปัด 2 ตำแหน่ง (งวดสุดท้าย = เงินต้นที่เหลือ)
 *  - ดอกเบี้ยนับวันจริง: เงินต้นคงเหลือ × อัตรา × วัน / 365 นับจากวันคิดดอกล่าสุด
 *  - ลำดับในวันเดียวกัน: กู้/ซื้อ → ชำระ → ออกบิล → วันครบกำหนด
 *  - ตัดชำระ: ค่าธรรมเนียม → รายการที่ออกบิลแล้ว (เก่าก่อน; ดอก → ต้น) → ยอดเต็มจำนวนที่ยังไม่ออกบิล → ส่วนเกิน (ก้อนเก่าก่อน)
 *  - ยอดเต็มจำนวนค้าง ≥ 300 ณ สิ้นวันครบกำหนด → แปลงเป็นผ่อน 5 งวดอัตโนมัติ
 */

// ---------- ข้อมูลเข้า ----------

data class LoanSnapshot(
    val date: String,
    val remaining: Double,
    val paidPeriods: Int,
    val lastInterestDate: String,
)

data class LoanIn(
    val id: String,
    val date: String,
    val principal: Double,
    val tenor: Int,
    val desc: String = "",
    val kind: String = "cash",
    /** null = ใช้อัตราของบัญชี */
    val rate: Double? = null,
    /** null = คำนวณจากสูตร */
    val installment: Double? = null,
    /** convert ที่ไม่มี snapshot = แปลงยอดเต็มจำนวนในบิลปัจจุบัน */
    val fromPurchases: Boolean = false,
    val snapshot: LoanSnapshot? = null,
)

data class PurchaseIn(val id: String, val date: String, val amount: Double, val desc: String = "")

data class PaymentIn(val id: String, val date: String, val amount: Double)

data class SimInput(
    val rate: Double = 0.25,
    val loans: List<LoanIn> = emptyList(),
    val purchases: List<PurchaseIn> = emptyList(),
    val payments: List<PaymentIn> = emptyList(),
    val until: String,
    val autoConvertTenor: Int = 5,
    val autoConvertMin: Double = 300.0,
    /** โหมดประมาณการ: สมมติว่าจ่ายเต็มบิลตรงวันครบกำหนดตั้งแต่วันนี้ */
    val autopayFrom: String? = null,
)

// ---------- ผลลัพธ์ (ชื่อฟิลด์ตรงกับระบบเดิม) ----------

@Suppress("PropertyName")
@Serializable
data class LoanBill(
    val k: Int,
    val due: String,
    val R: Double,
    val principalDue: Double,
    val interestDue: Double,
    val O: Double,
)

@Suppress("PropertyName")
@Serializable
data class StatementRow(
    val loan: String,
    val k: Int,
    val due: String,
    val R: Double,
    val principalDue: Double,
    val interestDue: Double,
    val O: Double,
    val installment: Double,
    val tenor: Int,
)

@Serializable
data class Statement(
    val date: String,
    val due: String,
    val rows: List<StatementRow>,
    val fullDue: Double,
    val fees: Double,
    val totalDue: Double,
    val outstanding: Double,
)

@Serializable
data class LoanOut(
    val id: String,
    val desc: String,
    val kind: String,
    val start: String,
    val principal: Double,
    val tenor: Int,
    val rate: Double,
    val installment: Double,
    val remaining: Double,
    val paidPeriods: Int,
    val periodsLeft: Int,
    val accruedInterest: Double,
    val lastInterestDate: String,
    val openBill: LoanBill?,
    /** active | closed */
    val status: String,
)

@Serializable
data class PurchaseOut(
    val id: String,
    val date: String,
    val desc: String,
    val amount: Double,
    val remaining: Double,
    val billedDue: String?,
)

/** ส่วนหนึ่งของการตัดชำระ: ก้อนผ่อน (loan) หรือยอดเต็มจำนวน (purchase) */
@Serializable
data class AllocPart(
    val loan: String? = null,
    val purchase: String? = null,
    val interest: Double? = null,
    val principal: Double? = null,
    val extra: Boolean? = null,
)

/** บันทึกเหตุการณ์: payment | autoConvert | overdue */
@Serializable
data class DebtLog(
    val type: String,
    val id: String? = null,
    val date: String,
    val amount: Double,
    val fee: Double? = null,
    val interest: Double? = null,
    val principal: Double? = null,
    val parts: List<AllocPart>? = null,
    val unapplied: Double? = null,
)

@Serializable
data class SimResult(
    val asOf: String,
    val loans: List<LoanOut>,
    val purchases: List<PurchaseOut>,
    val statements: List<Statement>,
    val log: List<DebtLog>,
    val fees: Double,
    val outstanding: Double,
    val accruedInterest: Double,
)

@Serializable
data class BillSummary(val date: String, val due: String, val totalDue: Double, val interest: Double)

data class Schedule(val byLoan: Map<String, List<StatementRow>>, val bills: List<BillSummary>)

data class NextDates(val statement: String, val due: String)

object DebtEngine {
    private const val EPS = 0.004

    fun toDay(s: String): Int = LocalDate.parse(s.substring(0, 10)).toEpochDay().toInt()
    fun toStr(d: Int): String = LocalDate.ofEpochDay(d.toLong()).toString()

    fun installmentOf(principal: Double, tenor: Int, rate: Double): Double {
        val r = rate / 12
        return r2(principal * r / (1 - jsPow(1 + r, -tenor.toDouble())))
    }

    /** วันครบกำหนดของบิลที่ออกในเดือนของ stmtDay = วันที่ 1 ของเดือนถัดไป */
    private fun dueAfterStatement(stmtDay: Int): Int {
        val dt = LocalDate.ofEpochDay(stmtDay.toLong())
        return dt.withDayOfMonth(1).plusMonths(1).toEpochDay().toInt()
    }

    /** วันออกบิลถัดไปที่ >= d */
    private fun nextStatement(d: Int): Int {
        val dt = LocalDate.ofEpochDay(d.toLong())
        var s = dt.withDayOfMonth(15).toEpochDay().toInt()
        if (s < d) s = dt.withDayOfMonth(1).plusMonths(1).withDayOfMonth(15).toEpochDay().toInt()
        return s
    }

    /** วันครบกำหนดถัดไป (วันที่ 1 = วันครบกำหนดของบิลปัจจุบัน ยังเลือกผ่อนได้) */
    private fun nextDue(d: Int): Int {
        val dt = LocalDate.ofEpochDay(d.toLong())
        if (dt.dayOfMonth == 1) return d
        return dt.withDayOfMonth(1).plusMonths(1).toEpochDay().toInt()
    }

    private class Bill(val k: Int, val due: Int, val R: Double, val principalDue: Double, val interestDue: Double, var O: Double)

    private class Loan(
        val id: String,
        val desc: String,
        val kind: String,
        val start: Int,
        val principal: Double,
        val tenor: Int,
        val rate: Double,
        var R: Double,
        var L: Int,
        var A: Double = 0.0,
        var Ip: Double = 0.0,
        var k: Int,
        var bill: Bill? = null,
        var installment: Double = 0.0,
    )

    private class Full(val id: String, val day: Int, val date: String, val desc: String, val amount: Double, var remaining: Double, var billedDue: Int? = null)

    private enum class EventType { LOAN, PURCHASE, PAYMENT, STATEMENT, DUE }

    private class Event(val day: Int, val order: Int, val type: EventType, val loan: LoanIn? = null, val purchase: PurchaseIn? = null, val payment: PaymentIn? = null)

    private class Alloc(val id: String, val date: String, val amount: Double) {
        var fee = 0.0
        var interest = 0.0
        var principal = 0.0
        val parts = ArrayList<AllocPart>()
    }

    fun simulate(input: SimInput): SimResult {
        val rate = input.rate
        val until = toDay(input.until)
        val autoTenor = input.autoConvertTenor
        val autoMin = input.autoConvertMin
        val autopayFrom = input.autopayFrom?.let { toDay(it) }

        val loans = ArrayList<Loan>()
        val statements = ArrayList<Statement>()
        val log = ArrayList<DebtLog>()
        val fulls = ArrayList<Full>()
        var fees = 0.0
        var overduePeriods = 0

        val events = ArrayList<Event>()
        input.loans.forEach { l ->
            val start = if (l.snapshot != null) toDay(l.snapshot.date) else toDay(l.date)
            events += Event(start, 1, EventType.LOAN, loan = l)
        }
        input.purchases.forEach { p -> events += Event(toDay(p.date), 1, EventType.PURCHASE, purchase = p) }
        input.payments.forEach { p -> events += Event(toDay(p.date), 2, EventType.PAYMENT, payment = p) }
        val first = if (events.isNotEmpty()) events.minOf { it.day } else until
        var s = nextStatement(first)
        while (s <= until) {
            events += Event(s, 3, EventType.STATEMENT)
            val d = dueAfterStatement(s)
            if (d <= until) events += Event(d, 4, EventType.DUE)
            s = nextStatement(s + 1)
        }
        events.sortWith(compareBy<Event> { it.day }.thenBy { it.order }) // stable เหมือน Array.sort ของ V8

        fun accrue(l: Loan, day: Int) {
            if (day > l.L) {
                l.A += l.R * l.rate * (day - l.L) / 365
                l.L = day
            }
        }

        fun billLoan(l: Loan, due: Int): Bill? {
            if (l.R <= EPS || l.k >= l.tenor) return null
            val projected = l.A + l.R * l.rate * (due - l.L) / 365
            val interestDue = r2(projected)
            l.k += 1
            var principalDue = r2(l.installment - interestDue - l.Ip)
            if (l.k >= l.tenor || principalDue >= l.R - EPS) principalDue = r2(l.R)
            if (principalDue < 0) principalDue = 0.0
            val b = Bill(l.k, due, r2(l.R), principalDue, interestDue, r2(principalDue + interestDue))
            l.bill = b
            l.Ip = 0.0
            return b
        }

        fun addLoan(l: LoanIn, day: Int): Loan {
            val snap = l.snapshot
            val loan = Loan(
                id = l.id, desc = l.desc, kind = l.kind, start = toDay(l.date), principal = l.principal, tenor = l.tenor,
                rate = l.rate ?: rate,
                R = snap?.remaining ?: l.principal,
                L = if (snap != null) toDay(snap.lastInterestDate.ifEmpty { snap.date }) else toDay(l.date),
                k = snap?.paidPeriods ?: 0,
            )
            loan.installment = l.installment ?: installmentOf(loan.principal, loan.tenor, loan.rate)
            var billNow = false
            if (l.fromPurchases && snap == null) {
                // แปลงยอดเต็มจำนวนในบิลปัจจุบันเป็นผ่อน: ตัดยอดเต็มจำนวน (เก่าก่อน) และคิดดอกตั้งแต่วันถัดจากวันออกบิล
                val due = nextDue(day)
                var left = loan.principal
                fulls.forEach { u ->
                    if (left > EPS && u.billedDue == due && u.remaining > EPS) {
                        val x = min(left, u.remaining)
                        u.remaining = r2(u.remaining - x)
                        left = r2(left - x)
                    }
                }
                val stmtDay = LocalDate.ofEpochDay(due.toLong()).minusMonths(1).withDayOfMonth(16).toEpochDay().toInt()
                loan.L = min(stmtDay, day)
                billNow = true
            }
            loans += loan
            if (billNow) billLoan(loan, nextDue(day))
            return loan
        }

        fun pay(p: PaymentIn, day: Int) {
            var x0 = p.amount
            val alloc = Alloc(p.id, toStr(day), x0)
            loans.forEach { accrue(it, day) }
            // 1. ค่าธรรมเนียม
            val f = min(x0, fees)
            fees = r2(fees - f)
            x0 = r2(x0 - f)
            alloc.fee = f

            fun payLoan(l: Loan, cap: Double, extra: Boolean) {
                // ดอกเบี้ยของก้อนนี้ก่อน แล้วค่อยเงินต้นของก้อนนี้
                var limit = cap
                val i = min(min(x0, r2(l.A)), limit)
                if (i > 0) {
                    l.A = r2(r2(l.A) - i)
                    x0 = r2(x0 - i)
                    limit = r2(limit - i)
                    alloc.interest = r2(alloc.interest + i)
                    val b = l.bill
                    if (b != null && b.O > 0) b.O = r2(max(0.0, b.O - i))
                    else if (b == null) l.Ip = r2(l.Ip + i) // ดอกที่จ่ายหลังวันครบกำหนด → ไปลดบิลถัดไป
                    alloc.parts += AllocPart(loan = l.id, interest = i)
                }
                val x = min(min(x0, l.R), limit)
                if (x > 0) {
                    l.R = r2(l.R - x)
                    x0 = r2(x0 - x)
                    alloc.principal = r2(alloc.principal + x)
                    val b = l.bill
                    if (b != null && b.O > 0) b.O = r2(max(0.0, b.O - x))
                    alloc.parts += AllocPart(loan = l.id, principal = x, extra = extra)
                }
            }

            fun payFull(u: Full) {
                val x = min(x0, u.remaining)
                if (x <= 0) return
                u.remaining = r2(u.remaining - x)
                x0 = r2(x0 - x)
                alloc.principal = r2(alloc.principal + x)
                alloc.parts += AllocPart(purchase = u.id, principal = x)
            }

            // 2. รายการที่ออกบิลแล้ว (ก้อนผ่อน + ยอดเต็มจำนวน) เรียงตามวันที่ — แต่ละก้อน: ดอก → ต้น ไม่เกินยอดบิลของก้อน
            class Billed(val full: Full?, val loan: Loan?, val day: Int)
            val billed = ArrayList<Billed>()
            fulls.forEach { u -> if (u.billedDue != null && u.remaining > EPS) billed += Billed(u, null, u.day) }
            loans.forEach { l -> val b = l.bill; if (b != null && b.O > EPS) billed += Billed(null, l, l.start) }
            billed.sortBy { it.day }
            billed.forEach { b ->
                if (x0 > 0) {
                    if (b.full != null) payFull(b.full) else payLoan(b.loan!!, b.loan.bill!!.O, false)
                }
            }
            // 3. ยอดเต็มจำนวนที่ยังไม่ออกบิล
            fulls.forEach { u -> if (x0 > 0 && u.remaining > EPS) payFull(u) }
            // 4. ส่วนเกิน → ก้อนเก่าสุดก่อน (ดอกของก้อน → ต้นของก้อน)
            loans.forEach { l -> if (x0 > 0 && (l.R > EPS || l.A > EPS)) payLoan(l, Double.POSITIVE_INFINITY, true) }
            log += DebtLog(
                type = "payment", id = alloc.id, date = alloc.date, amount = alloc.amount, fee = alloc.fee,
                interest = alloc.interest, principal = alloc.principal, parts = alloc.parts.toList(), unapplied = x0,
            )
        }

        events.forEach { e ->
            if (e.day > until) return@forEach
            when (e.type) {
                EventType.LOAN -> addLoan(e.loan!!, e.day)
                EventType.PURCHASE -> {
                    val p = e.purchase!!
                    fulls += Full(p.id, e.day, toStr(e.day), p.desc, p.amount, p.amount)
                }
                EventType.PAYMENT -> pay(e.payment!!, e.day)
                EventType.STATEMENT -> {
                    val due = dueAfterStatement(e.day)
                    val rows = ArrayList<StatementRow>()
                    fun row(l: Loan, b: Bill) = StatementRow(l.id, b.k, toStr(b.due), b.R, b.principalDue, b.interestDue, b.O, l.installment, l.tenor)
                    loans.forEach { l ->
                        val cur = l.bill
                        if (l.start > e.day || (cur != null && cur.due == due)) {
                            if (cur != null && cur.due == due) rows += row(l, cur)
                            return@forEach
                        }
                        val b = billLoan(l, due)
                        if (b != null) rows += row(l, b)
                    }
                    var fullDue = 0.0
                    fulls.forEach { u ->
                        if (u.day <= e.day && u.remaining > EPS && u.billedDue == null) u.billedDue = due
                        if (u.billedDue == due) fullDue = r2(fullDue + u.remaining)
                    }
                    val outstanding = r2(loans.fold(0.0) { a, l -> a + l.R } + fulls.fold(0.0) { a, u -> a + u.remaining })
                    val totalDue = r2(rows.fold(0.0) { a, b -> a + b.O } + fullDue + fees)
                    statements += Statement(toStr(e.day), toStr(due), rows, fullDue, fees, totalDue, outstanding)
                }
                EventType.DUE -> {
                    val day = e.day
                    // โหมดประมาณการ: สมมติว่าจ่ายเต็มบิลตรงวันครบกำหนดทุกงวด
                    if (autopayFrom != null && day >= autopayFrom) {
                        val amt = r2(
                            loans.fold(0.0) { a, l -> a + (l.bill?.takeIf { it.due == day }?.O ?: 0.0) } +
                                fulls.fold(0.0) { a, u -> a + (if (u.billedDue == day) u.remaining else 0.0) } + fees
                        )
                        if (amt > EPS) pay(PaymentIn("auto_" + toStr(day), toStr(day), amt), day)
                    }
                    // สิ้นวันครบกำหนด: ยอดเต็มจำนวนค้าง ≥ 300 → ผ่อน 5 งวด; บิลผ่อนค้าง = ผิดนัด
                    val unpaidFull = r2(fulls.filter { it.billedDue == day }.fold(0.0) { a, u -> a + u.remaining })
                    val unpaidLoans = loans.filter { l -> l.bill?.let { it.due == day && it.O > EPS } == true }
                    if (unpaidFull >= autoMin) {
                        fulls.forEach { u -> if (u.billedDue == day) u.remaining = 0.0 }
                        addLoan(
                            LoanIn(
                                id = "auto_" + toStr(day), kind = "auto", desc = "แปลงยอดเต็มจำนวนเป็นผ่อนอัตโนมัติ",
                                date = toStr(day), principal = unpaidFull, tenor = autoTenor,
                            ),
                            day,
                        )
                        log += DebtLog(type = "autoConvert", date = toStr(day), amount = unpaidFull)
                    }
                    val unpaidTotal = r2(unpaidLoans.fold(0.0) { a, l -> a + l.bill!!.O } + (if (unpaidFull < autoMin) unpaidFull else 0.0))
                    if (unpaidTotal > EPS) {
                        overduePeriods += 1
                        val debt = loans.fold(0.0) { a, l -> a + l.R }
                        if (debt >= 1000) fees = r2(fees + (if (overduePeriods > 1) 100 else 50))
                        log += DebtLog(type = "overdue", date = toStr(day), amount = unpaidTotal)
                    } else {
                        overduePeriods = 0
                    }
                    loans.forEach { l -> val b = l.bill; if (b != null && b.due == day && b.O <= EPS) l.bill = null }
                }
            }
        }

        loans.forEach { accrue(it, until) }
        return SimResult(
            asOf = toStr(until),
            loans = loans.map { l ->
                LoanOut(
                    id = l.id, desc = l.desc, kind = l.kind, start = toStr(l.start), principal = l.principal, tenor = l.tenor,
                    rate = l.rate, installment = l.installment, remaining = r2(l.R), paidPeriods = l.k,
                    periodsLeft = max(0, l.tenor - l.k), accruedInterest = r2(l.A), lastInterestDate = toStr(l.L),
                    openBill = l.bill?.let { LoanBill(it.k, toStr(it.due), it.R, it.principalDue, it.interestDue, it.O) },
                    status = if (l.R <= EPS) "closed" else "active",
                )
            },
            purchases = fulls.map { u -> PurchaseOut(u.id, u.date, u.desc, u.amount, u.remaining, u.billedDue?.let(::toStr)) },
            statements = statements,
            log = log,
            fees = fees,
            outstanding = r2(loans.fold(0.0) { a, l -> a + l.R } + fulls.fold(0.0) { a, u -> a + u.remaining }),
            accruedInterest = r2(loans.fold(0.0) { a, l -> a + l.A }),
        )
    }

    /** ยอดปิดหนี้: เงินต้น + ดอกถึงวันนี้ + ค่าธรรมเนียม */
    fun payoffQuote(sim: SimResult): Double = r2(sim.outstanding + sim.accruedInterest + sim.fees)

    /** ตารางผ่อนล่วงหน้า ~2 ปี (สมมติจ่ายตรงบิลทุกงวด) */
    fun schedule(input: SimInput, fromDate: String): Schedule {
        val u = LocalDate.parse(fromDate.substring(0, 10))
        // Date.UTC(y + 2, m + 2, 2)
        val until = LocalDate.of(u.year + 2, 1, 1).plusMonths((u.monthValue - 1 + 2).toLong()).withDayOfMonth(2).toString()
        val sim = simulate(input.copy(autopayFrom = fromDate, until = until))
        val out = LinkedHashMap<String, MutableList<StatementRow>>()
        sim.statements.filter { it.date >= fromDate }.forEach { st -> st.rows.forEach { b -> out.getOrPut(b.loan) { ArrayList() } += b } }
        val bills = sim.statements.filter { it.date >= fromDate && it.totalDue > 0 }
            .map { st -> BillSummary(st.date, st.due, st.totalDue, r2(st.rows.fold(0.0) { a, b -> a + b.interestDue })) }
        return Schedule(out, bills)
    }

    /** วันออกบิล / ครบกำหนดถัดไป */
    fun nextDates(date: String): NextDates {
        val s = nextStatement(toDay(date))
        return NextDates(toStr(s), toStr(dueAfterStatement(s)))
    }
}
