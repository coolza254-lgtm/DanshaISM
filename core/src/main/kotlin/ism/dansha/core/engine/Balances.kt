package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Bill
import ism.dansha.core.FxRate
import ism.dansha.core.Transaction
import kotlinx.serialization.Serializable

/** บัญชี + ยอดที่คำนวณแล้ว (withBalances_ ของ api.js) */
data class AccountView(
    val account: Account,
    /** cash/bank: ยอดคงเหลือ */
    val balance: Double? = null,
    /** revolving_credit: ยอดใช้ไป */
    val used: Double? = null,
    /** revolving_credit: วงเงินคงเหลือ */
    val available: Double? = null,
    /** Ascend: ดอกสะสมถึงวันนี้ */
    val accruedInterest: Double? = null,
) {
    val isCredit: Boolean get() = account.type == "revolving_credit"
}

object Balances {
    /**
     * ยอด cash/bank = ตั้งต้น + เข้า − ออก
     * revolving_credit ไม่มีตัวคำนวณ: ใช้ไป = ตั้งต้น − (เข้า − ออก)
     * มีตัวคำนวณ (ascend/spaylater): ใช้ไป = outstanding จากตัวคำนวณหนี้
     */
    fun withBalances(accounts: List<Account>, txns: List<Transaction>, debt: Map<String, DebtState>): List<AccountView> {
        val flow = HashMap<String, Double>()
        txns.forEach { t ->
            val amt = t.amount.num()
            when (t.type) {
                "income" -> flow[t.account_id] = (flow[t.account_id] ?: 0.0) + amt
                "expense" -> flow[t.account_id] = (flow[t.account_id] ?: 0.0) - amt
                "transfer" -> {
                    flow[t.account_id] = (flow[t.account_id] ?: 0.0) - amt
                    flow[t.to_account_id] = (flow[t.to_account_id] ?: 0.0) + amt
                }
            }
        }
        return accounts.map { a ->
            val st = debt[a.id]
            if (st != null && (isEngineAccount(a) || isSplAccount(a))) {
                val used = st.outstanding
                return@map AccountView(a, used = used, available = round2(a.credit_limit.num() - used), accruedInterest = st.accruedInterest)
            }
            val f = flow[a.id] ?: 0.0
            val open = a.opening_balance.num()
            if (a.type == "revolving_credit") {
                val used = round2(open - f)
                AccountView(a, used = used, available = round2(a.credit_limit.num() - used))
            } else {
                AccountView(a, balance = round2(open + f))
            }
        }
    }

    /** เรทที่ใช้ = manual_rate (ถ้ากรอก) ไม่งั้น auto_rate ไม่งั้น rate_to_thb */
    fun fxRateOf(r: FxRate): Double {
        val manual = r.manual_rate.num()
        if (manual > 0) return manual
        val auto = r.auto_rate.num()
        return if (auto > 0) auto else r.rate_to_thb.num()
    }

    /** สกุลเงิน → บาทต่อ 1 หน่วย */
    fun fxMap(fx: List<FxRate>): Map<String, Double> {
        val rates = LinkedHashMap<String, Double>()
        rates["THB"] = 1.0
        fx.forEach { r ->
            val rate = fxRateOf(r)
            if (r.currency.isNotEmpty() && rate > 0) rates[r.currency] = rate
        }
        return rates
    }

    /** เงินที่มีตอนนี้ = ทุกบัญชีที่ไม่ใช่วงเงิน (เปิดใช้งาน) แปลงเป็นบาท */
    fun cashNowThb(accounts: List<AccountView>, fx: Map<String, Double>): Double =
        accounts.filter { it.account.active && it.account.type != "revolving_credit" }
            .fold(0.0) { s, a -> s + (a.balance ?: Double.NaN) * (fx[a.account.currency]?.orIfFalsy(1.0) ?: 1.0) }
}

/** สรุปแผนของรอบ: คงเหลือคาดการณ์ = เงินตอนนี้ + รายรับที่ยังไม่เข้า − รายจ่ายที่ยังไม่จ่าย */
@Serializable
data class PlanSummary(
    val cycle: String,
    val count: Int,
    val cashNow: Double,
    val incomePending: Double,
    val incomeReceived: Double,
    val aPending: Double,
    val aPaid: Double,
    val bPending: Double,
    val bPaid: Double,
    val projected: Double,
)

object Plan {
    val GROUPS = listOf("income", "A", "B")

    fun summary(cycle: String, bills: List<Bill>, cashNow: Double): PlanSummary {
        val rows = bills.filter { it.pay_cycle == cycle }
        fun sum(g: String, st: String) = round2(
            rows.filter { it.group == g && it.status == st }
                .fold(0.0) { s, b -> s + (if (st == "paid") b.actual_amount else b.est_amount).num() }
        )
        val s = PlanSummary(
            cycle = cycle, count = rows.size, cashNow = round2(cashNow),
            incomePending = sum("income", "planned"), incomeReceived = sum("income", "paid"),
            aPending = sum("A", "planned"), aPaid = sum("A", "paid"),
            bPending = sum("B", "planned"), bPaid = sum("B", "paid"),
            projected = 0.0,
        )
        return s.copy(projected = round2(s.cashNow + s.incomePending - s.aPending - s.bPending))
    }

    /** วันครบกำหนดของ due_day ภายในรอบ: due_day ≥ วันเริ่มรอบ อยู่เดือนแรก ไม่งั้นเดือนถัดไป, เกินจำนวนวันใช้วันสุดท้าย */
    fun dueDateInCycle(cycle: String, dueDay: Int?, startDay: Int): String {
        if (dueDay == null || dueDay == 0) return ""
        val y = cycle.substring(0, 4).toInt()
        val m = cycle.substring(5, 7).toInt() - 1
        val month = if (dueDay >= startDay) m else m + 1
        val first = java.time.LocalDate.of(y, 1, 1).plusMonths(month.toLong())
        val last = first.lengthOfMonth()
        return first.plusDays((minOf(dueDay, last) - 1).toLong()).toString()
    }
}
