package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Schema
import ism.dansha.core.Transaction
import java.time.LocalDateTime

/** ตัวเลขทั้งหมดของหน้าแรก ณ เวลาหนึ่ง (bootstrap_ ของระบบเดิม) */
data class Overview(
    val now: LocalDateTime,
    val today: String,
    val config: Map<String, String>,
    val startDay: Int,
    val accounts: List<AccountView>,
    /** สถานะหนี้ (ไม่มีตารางผ่อน) */
    val debt: Map<String, DebtState>,
    val plan: PlanSummary,
    val copay: CopayStatus?,
    val port: PortSummary?,
    val fx: Map<String, Double>,
    val payCycle: String,
    val payCycleRange: DateRange,
    /** 30 รายการล่าสุดตามลำดับที่บันทึก (ใหม่ก่อน) */
    val recentTransactions: List<Transaction>,
) {
    /** เงินที่มีตอนนี้ (บาท) */
    val cashNow: Double get() = plan.cashNow
}

/** จุดเรียกตัวคำนวณทั้งหมดจากแอพ: รับข้อมูลทั้งก้อน คืนผลลัพธ์ ไม่แก้ข้อมูล */
object Engine {
    fun startDay(config: Map<String, String>): Int = jsNumber(config["pay_cycle_start_day"]).orIfFalsy(15.0).toInt()

    fun config(d: DanshaData): Map<String, String> = Schema.effectiveConfig(d.config)

    /** สถานะหนี้ทุกบัญชีวงเงินที่มีตัวคำนวณ */
    fun debtStates(d: DanshaData, today: String = Dates.todayStr(), withSchedule: Boolean = false): Map<String, DebtState> =
        DebtTracker.states(d.accounts, d.transactions, d.debts, d.shopee, today, withSchedule)

    fun accounts(d: DanshaData, debt: Map<String, DebtState>): List<AccountView> =
        Balances.withBalances(d.accounts, d.transactions, debt)

    fun overview(d: DanshaData, now: LocalDateTime = Dates.now()): Overview {
        val cfg = config(d)
        val startDay = startDay(cfg)
        val today = now.toLocalDate().toString()
        val cycle = Dates.payCycleOf(now.toLocalDate(), startDay)
        val debt = debtStates(d, today, false)
        val accounts = accounts(d, debt)
        val fx = Balances.fxMap(d.fx)
        val (rs, re) = Dates.payCycleRange(cycle, startDay)
        val port = if (d.port.isNotEmpty()) {
            try {
                PortfolioCalc.compute(d.port, d.prices, fx).summary
            } catch (e: Exception) {
                null
            }
        } else null
        return Overview(
            now = now, today = today, config = cfg, startDay = startDay, accounts = accounts, debt = debt,
            plan = Plan.summary(cycle, d.bills, Balances.cashNowThb(accounts, fx)),
            copay = Copay.status(d.transactions, cfg, today),
            port = port, fx = fx, payCycle = cycle, payCycleRange = DateRange(rs.toString(), re.toString()),
            recentTransactions = d.transactions.takeLast(30).reversed(),
        )
    }

    /** ภาพรวมรายรอบ: รอบนี้ + (count − 1) รอบถัดไป */
    fun outlook(d: DanshaData, count: Int = 6, now: LocalDateTime = Dates.now()): List<CycleOutlook> {
        val cfg = config(d)
        val startDay = startDay(cfg)
        val today = now.toLocalDate().toString()
        val debt = debtStates(d, today, true)
        val accounts = accounts(d, debtStates(d, today, false))
        val cash = Balances.cashNowThb(accounts, Balances.fxMap(d.fx))
        return Outlook.cycles(count, Dates.payCycleOf(now.toLocalDate(), startDay), startDay, today, cash, d.bills, d.billTemplates, debt)
    }

    fun checkPurchase(d: DanshaData, q: PurchaseQuery, now: LocalDateTime = Dates.now()): PurchaseCheck {
        if (!(q.price > 0)) throw EngineException("กรุณากรอกราคา")
        val cfg = config(d)
        return Outlook.checkPurchase(q, now.toLocalDate().toString(), startDay(cfg), cfg, outlook(d, 7, now))
    }

    fun debtQuote(d: DanshaData, accountId: String, date: String? = null, amount: Double? = null): DebtTracker.Quote =
        DebtTracker.quote(d.accounts.firstOrNull { it.id == accountId }, d.debts, d.transactions, date?.ifEmpty { null } ?: Dates.todayStr(), amount)

    fun portfolio(d: DanshaData): Portfolio = PortfolioCalc.compute(d.port, d.prices, Balances.fxMap(d.fx))

    fun shopeeStats(d: DanshaData): ShopeeStats = Outlook.shopeeStats(d.shopee)
}
