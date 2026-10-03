package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.ShopeeOrder
import ism.dansha.core.Transaction
import kotlinx.serialization.Serializable
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/*
 * SPayLater (บัญชี revolving_credit ที่ debt_engine = 'spaylater') — port จาก shopee.js
 *  - ใช้ค่างวดที่ผู้ใช้กรอกจากแอพ Shopee
 *  - รอบบิลเดือน M = ออเดอร์ 15/M – 14/M+1 → งวดที่ k ครบกำหนดวันที่ 25 ของเดือน M + k
 *  - ชำระ = โอน/รายรับเข้าบัญชี SPayLater → ตัดงวดที่ครบกำหนดก่อนสุดก่อน
 *  - ยอดใช้ไป = ค่างวดที่เหลือทั้งหมด
 */

@Serializable
data class SplInstallment(
    val order: String,
    val item: String,
    val k: Int,
    val n: Int,
    val due: String,
    val amount: Double,
    val paid: Double = 0.0,
    val left: Double = 0.0,
)

@Serializable
data class SplPerOrder(val paidCount: Int, val left: Double, val next: String?)

@Serializable
data class SplState(
    override val accountId: String,
    val engine: String = "spaylater",
    override val asOf: String,
    override val outstanding: Double,
    val credit: Double,
    override val currentBill: CurrentBill?,
    override val nextBill: DueBill?,
    override val bills: List<DueBill>,
    val installments: List<SplInstallment>,
    val perOrder: Map<String, SplPerOrder>,
) : DebtState {
    override val accruedInterest: Double? get() = null
}

object SPayLater {

    /** งวดที่ k (เริ่ม 1): วันที่ 25 ของเดือน (เดือนรอบบิล + k) — ออเดอร์ก่อนวันที่ 15 นับเป็นรอบบิลเดือนก่อน */
    fun dueDate(orderDate: String, k: Int): String {
        val d = LocalDate.parse(orderDate.substring(0, 10))
        val billMonth = d.monthValue - 1 - (if (d.dayOfMonth < 15) 1 else 0)
        return LocalDate.of(d.year, 1, 25).plusMonths((billMonth + k).toLong()).toString()
    }

    /** วันตัดรอบของบิลที่ครบกำหนด due (วันที่ 15 ของเดือนเดียวกับ due) */
    fun stmtDate(due: String): String = due.substring(0, 8) + "15"

    fun installments(orders: List<ShopeeOrder>): List<SplInstallment> {
        val list = ArrayList<SplInstallment>()
        orders.forEach { o ->
            val n = max(1, (o.tenor ?: 0).let { if (it == 0) 1 else it })
            val price = o.price.num()
            val inst = if (n == 1) price else o.installment.num().orIfFalsy(round2(price / n))
            val noInstallment = o.installment == null || o.installment.signum() == 0
            for (k in 1..n) {
                // งวดสุดท้ายปรับเศษให้รวมเท่ากับยอดผ่อนทั้งหมด (กรณีไม่ได้กรอกค่างวด)
                val amt = if (noInstallment && k == n) round2(price - inst * (n - 1)) else inst
                list += SplInstallment(o.id, o.item, k, n, dueDate(o.order_date, k), amt)
            }
        }
        return list.sortedWith(compareBy<SplInstallment> { it.due }.thenBy { it.order })
    }

    fun state(account: Account, orders: List<ShopeeOrder>, txns: List<Transaction>, today: String): SplState {
        val mine = orders.filter { it.account_id == account.id && it.pay_method == "spaylater" && it.status != "cancelled" }
        val since = account.debt_since.ifEmpty { "0000-00-00" }
        var paid = round2(
            txns.filter { t -> t.date >= since && ((t.type == "transfer" && t.to_account_id == account.id) || (t.type == "income" && t.account_id == account.id)) }
                .fold(0.0) { a, t -> a + t.amount.num() }
        )
        val inst = installments(mine).map { i ->
            val x = min(paid, i.amount)
            val out = i.copy(paid = round2(x), left = round2(i.amount - x))
            paid = round2(paid - x)
            out
        }
        val open = inst.filter { it.left > 0.004 }
        val byDue = LinkedHashMap<String, Double>()
        open.forEach { i -> byDue[i.due] = round2((byDue[i.due] ?: 0.0) + i.left) }
        val dues = byDue.keys.sorted().map { d -> d to byDue.getValue(d) }
        val overdue = dues.filter { it.first < today }
        val upcoming = dues.filter { it.first >= today }
        val perOrder = LinkedHashMap<String, SplPerOrder>()
        mine.forEach { o ->
            val rows = inst.filter { it.order == o.id }
            perOrder[o.id] = SplPerOrder(
                paidCount = rows.count { it.left <= 0.004 },
                left = round2(rows.fold(0.0) { a, i -> a + i.left }),
                next = rows.firstOrNull { it.left > 0.004 }?.due,
            )
        }
        val outstanding = round2(open.fold(0.0) { a, i -> a + i.left })
        val currentBill = when {
            overdue.isNotEmpty() -> CurrentBill(overdue[0].first, round2(overdue.fold(0.0) { a, d -> a + d.second }), overdue = true)
            upcoming.isNotEmpty() && upcoming[0].first.substring(0, 7) == today.substring(0, 7) -> CurrentBill(upcoming[0].first, upcoming[0].second)
            else -> null
        }
        return SplState(
            accountId = account.id, asOf = today, outstanding = outstanding, credit = round2(paid),
            currentBill = currentBill,
            nextBill = upcoming.firstOrNull()?.let { DueBill(stmtDate(it.first), it.first, it.second) },
            bills = upcoming.map { DueBill(stmtDate(it.first), it.first, it.second) },
            installments = inst, perOrder = perOrder,
        )
    }

    /** หาอัตราดอกเบี้ยต่อปีจากค่างวด (bisection) */
    fun impliedRate(principal: Double, installment: Double, n: Int): Double {
        var lo = 0.0
        var hi = 2.0
        repeat(60) {
            val mid = (lo + hi) / 2
            val r = mid / 12
            val pmt = if (r == 0.0) principal / n else principal * r / (1 - jsPow(1 + r, -n.toDouble()))
            if (pmt > installment) hi = mid else lo = mid
        }
        return (lo + hi) / 2
    }
}
