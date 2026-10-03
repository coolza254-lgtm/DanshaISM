package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Debt
import ism.dansha.core.ShopeeOrder
import ism.dansha.core.Transaction
import kotlinx.serialization.Serializable
import java.time.LocalDate

/*
 * เชื่อมข้อมูลกับ DebtEngine (port จาก debt.js)
 * บัญชีที่ debt_engine = 'ascend':
 *   - รายจ่ายจากบัญชีนี้            = ยอดเต็มจำนวน (ร้านค้า)
 *   - โอน/รายรับเข้าบัญชีนี้         = ชำระหนี้
 *   - หนี้ย่อย (ตาราง debts)         = ก้อนผ่อน / snapshot / ยอดเต็มจำนวนยกมา
 * รายการก่อน debt_since ไม่นำมาคิด (ใช้ snapshot ของหนี้ย่อยแทน)
 */

val DEBT_KINDS = listOf("cash", "installment", "convert", "fullpay_snapshot")

fun isEngineAccount(a: Account?): Boolean = a != null && a.type == "revolving_credit" && a.debt_engine == "ascend"
fun isSplAccount(a: Account?): Boolean = a != null && a.type == "revolving_credit" && a.debt_engine == "spaylater"

/** บิลที่ต้องจ่าย (บิลปัจจุบัน) */
@Serializable
data class CurrentBill(val due: String, val amount: Double, val overdue: Boolean? = null)

/** บิลที่ออก/จะออก: date = วันออกบิล, interest = ดอกในบิล (เฉพาะ Ascend) */
@Serializable
data class DueBill(val date: String, val due: String, val totalDue: Double, val interest: Double? = null)

/** สถานะหนี้ของบัญชีวงเงินที่มีตัวคำนวณ (Ascend / SPayLater) */
sealed interface DebtState {
    val accountId: String
    val asOf: String
    val outstanding: Double
    val currentBill: CurrentBill?
    val nextBill: DueBill?
    /** บิลล่วงหน้า (Ascend: เฉพาะเมื่อคำนวณตารางผ่อน) */
    val bills: List<DueBill>?
    val accruedInterest: Double?
}

@Serializable
data class CycleSpend(
    val start: String,
    val end: String,
    val count: Int,
    val fullpay: Double,
    val installment: Double,
    val total: Double,
)

@Serializable
data class LastStatement(val date: String, val due: String, val totalDue: Double)

@Serializable
data class AscendState(
    override val accountId: String,
    override val asOf: String,
    override val outstanding: Double,
    override val accruedInterest: Double,
    val fees: Double,
    val payoffToday: Double,
    override val currentBill: CurrentBill?,
    override val nextBill: DueBill?,
    val lastStatement: LastStatement?,
    val loans: List<LoanOut>,
    val pendingFull: List<PurchaseOut>,
    val payments: List<DebtLog>,
    val events: List<DebtLog>,
    val dailyInterest: Double,
    val cycleSpend: CycleSpend,
    /** ตารางผ่อนรายก้อน (เฉพาะเมื่อขอ) */
    val schedule: Map<String, List<StatementRow>>? = null,
    override val bills: List<DueBill>? = null,
) : DebtState

object DebtTracker {

    fun debtInput(account: Account, debts: List<Debt>, txns: List<Transaction>, until: String): SimInput {
        val since = account.debt_since.ifEmpty { "0000-00-00" }
        val rate = account.annual_rate.num().orIfFalsy(25.0) / 100
        val mine = debts.filter { it.account_id == account.id && it.status != "deleted" }
        val loans = mine.filter { it.kind != "fullpay_snapshot" }.map { d ->
            LoanIn(
                id = d.id, date = d.txn_date, principal = d.principal.num(), tenor = d.tenor ?: 0, desc = d.description, kind = d.kind,
                rate = if (d.annual_rate.num() != 0.0) d.annual_rate.num() / 100 else rate,
                installment = d.installment.num().takeIf { it != 0.0 },
                fromPurchases = d.kind == "convert" && d.snap_date.isEmpty(),
                snapshot = if (d.snap_date.isNotEmpty()) LoanSnapshot(
                    date = d.snap_date, remaining = d.snap_remaining.num(), paidPeriods = d.snap_paid_periods ?: 0,
                    lastInterestDate = d.snap_last_interest_date.ifEmpty { d.snap_date },
                ) else null,
            )
        }
        val purchases = mine.filter { it.kind == "fullpay_snapshot" }
            .map { d -> PurchaseIn(d.id, d.txn_date, d.principal.num(), d.description) } +
            txns.filter { it.type == "expense" && it.account_id == account.id && it.date >= since }
                .map { t -> PurchaseIn(t.id, t.date, t.amount.num(), t.note) }
        val payments = txns
            .filter { t -> t.date >= since && ((t.type == "transfer" && t.to_account_id == account.id) || (t.type == "income" && t.account_id == account.id)) }
            .map { t -> PaymentIn(t.id, t.date, t.amount.num()) }
        return SimInput(rate = rate, loans = loans, purchases = purchases, payments = payments, until = until)
    }

    /** สรุปหนี้ของบัญชี ณ วันนี้ + บิลปัจจุบัน/ถัดไป (+ ตารางผ่อน) */
    fun accountState(account: Account, debts: List<Debt>, txns: List<Transaction>, today: String, withSchedule: Boolean): AscendState {
        val input = debtInput(account, debts, txns, today)
        val sim = DebtEngine.simulate(input)
        val nd = DebtEngine.nextDates(today)
        val openLoans = sim.loans.filter { it.openBill != null && it.openBill.O > 0.004 }
        val openFull = sim.purchases.filter { it.billedDue != null && it.billedDue >= today && it.remaining > 0.004 }
        val currentBill = if (openLoans.isNotEmpty() || openFull.isNotEmpty()) CurrentBill(
            due = if (openLoans.isNotEmpty()) openLoans[0].openBill!!.due else openFull[0].billedDue!!,
            amount = r2(openLoans.fold(0.0) { a, l -> a + l.openBill!!.O } + openFull.fold(0.0) { a, u -> a + u.remaining } + sim.fees),
        ) else null
        // บิลถัดไป: จำลองแค่ถึงวันออกบิลถัดไป — ตารางเต็ม 2 ปีคำนวณเฉพาะเมื่อขอ
        val sched = if (withSchedule) DebtEngine.schedule(input, today) else null
        val upto = DebtEngine.simulate(input.copy(autopayFrom = today, until = nd.statement))
        val ns = upto.statements.firstOrNull { it.date == nd.statement && it.totalDue > 0 }
        val nextBill = ns?.let { DueBill(it.date, it.due, it.totalDue, r2(it.rows.fold(0.0) { a, b -> a + b.interestDue })) }
        val lastStmt = sim.statements.lastOrNull()
        return AscendState(
            accountId = account.id, asOf = today, outstanding = sim.outstanding, accruedInterest = sim.accruedInterest, fees = sim.fees,
            payoffToday = DebtEngine.payoffQuote(sim),
            currentBill = currentBill, nextBill = nextBill,
            lastStatement = lastStmt?.let { LastStatement(it.date, it.due, it.totalDue) },
            loans = sim.loans, pendingFull = sim.purchases.filter { it.remaining > 0.004 },
            payments = sim.log.filter { it.type == "payment" }.takeLast(20).reversed(),
            events = sim.log.filter { it.type != "payment" },
            dailyInterest = r2(sim.loans.fold(0.0) { a, l -> a + l.remaining * l.rate / 365 }),
            cycleSpend = cycleSpend(input, today),
            schedule = sched?.byLoan,
            bills = sched?.bills?.take(26)?.map { DueBill(it.date, it.due, it.totalDue, it.interest) },
        )
    }

    /** ยอดใช้สะสมในรอบบิลปัจจุบัน (16 – 15): ซื้อเต็มจำนวน + กดเงินสด + ผ่อนตอนซื้อ (ไม่นับการแปลงยอด/snapshot) */
    fun cycleSpend(input: SimInput, today: String): CycleSpend {
        val t = LocalDate.parse(today.substring(0, 10))
        val base = t.withDayOfMonth(1)
        val start = (if (t.dayOfMonth >= 16) base else base.minusMonths(1)).withDayOfMonth(16).toString()
        val end = (if (t.dayOfMonth >= 16) base.plusMonths(1) else base).withDayOfMonth(15).toString()
        fun inWin(d: String) = d >= start && d <= end
        val buys = input.purchases.filter { inWin(it.date) }
        val loans = input.loans.filter { (it.kind == "cash" || it.kind == "installment") && it.snapshot == null && inWin(it.date) }
        val buySum = buys.fold(0.0) { a, u -> a + u.amount }
        val loanSum = loans.fold(0.0) { a, l -> a + l.principal }
        return CycleSpend(start, end, buys.size + loans.size, r2(buySum), r2(loanSum), r2(buySum + loanSum))
    }

    /** สถานะหนี้ทุกบัญชีวงเงินที่มีตัวคำนวณ (Ascend ก่อน แล้ว SPayLater ตามลำดับบัญชี) */
    fun states(
        accounts: List<Account>,
        txns: List<Transaction>,
        debts: List<Debt>,
        orders: List<ShopeeOrder>,
        today: String,
        withSchedule: Boolean,
    ): Map<String, DebtState> {
        val res = LinkedHashMap<String, DebtState>()
        accounts.filter(::isEngineAccount).forEach { a -> res[a.id] = accountState(a, debts, txns, today, withSchedule) }
        accounts.filter(::isSplAccount).forEach { a -> res[a.id] = SPayLater.state(a, orders, txns, today) }
        return res
    }

    @Serializable
    data class QuoteAfterLoan(val id: String, val remaining: Double)

    @Serializable
    data class QuoteAfter(val outstanding: Double, val loans: List<QuoteAfterLoan>)

    @Serializable
    data class Quote(
        val date: String,
        val principal: Double,
        val interest: Double,
        val fees: Double,
        val payoff: Double,
        val allocation: DebtLog? = null,
        val after: QuoteAfter? = null,
    )

    /** ถ้าจ่ายวันที่ date: ยอดปิดหนี้ทั้งหมด + (ถ้าระบุ amount) จะตัดชำระอะไรบ้าง */
    fun quote(account: Account?, debts: List<Debt>, txns: List<Transaction>, date: String, amount: Double?): Quote {
        if (!isEngineAccount(account)) throw EngineException("บัญชีนี้ไม่ได้เปิดใช้ Debt Tracker")
        val input = debtInput(account!!, debts, txns, date)
        val sim = DebtEngine.simulate(input)
        var q = Quote(date, sim.outstanding, sim.accruedInterest, sim.fees, DebtEngine.payoffQuote(sim))
        if (amount != null && amount != 0.0 && !amount.isNaN()) {
            val after = DebtEngine.simulate(input.copy(payments = input.payments + PaymentIn("quote", date, amount)))
            q = q.copy(
                allocation = after.log.firstOrNull { it.type == "payment" && it.id == "quote" },
                after = QuoteAfter(after.outstanding, after.loans.map { QuoteAfterLoan(it.id, it.remaining) }),
            )
        }
        return q
    }
}

/** ข้อผิดพลาดที่แสดงให้ผู้ใช้เห็นได้ (ข้อความภาษาไทย เหมือนระบบเดิม) */
class EngineException(message: String) : Exception(message)
