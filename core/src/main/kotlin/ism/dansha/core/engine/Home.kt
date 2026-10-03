package ism.dansha.core.engine

import ism.dansha.core.Dates
import java.time.temporal.ChronoUnit

/** ตัวเลขบนหน้าแรก (หัวข้อ 5.10) */
data class HomeFigures(
    /** เงินที่มีตอนนี้ = รวมบัญชีที่ไม่ใช่วงเงิน (บาท) */
    val cashNow: Double,
    /** หนี้คงเหลือ = รวมยอดใช้ไปของบัญชีวงเงิน (บาท) */
    val debtTotal: Double,
    /** สิ้นรอบเหลือ = คงเหลือคาดการณ์ (null = ยังไม่มีแผนรอบนี้) */
    val projected: Double?,
    /** ใช้ได้วันละ */
    val perDay: Double,
    /** จำนวนวันถึงสิ้นรอบ (รวมวันนี้) */
    val daysLeft: Int,
    val nextDebtBill: NextDebtBill?,
    /** ยอดใช้ในรอบบิล 16–15 ของบัญชี Ascend (ชื่อบัญชี → ยอด) */
    val cycleSpend: List<Pair<String, CycleSpend>>,
    val copay: CopayCard?,
)

data class NextDebtBill(val accountName: String, val due: String, val amount: Double, val overdue: Boolean)

data class CopayCard(
    val name: String,
    /** เหลือกี่วัน (รวมวันนี้) */
    val daysLeft: Int,
    /** รัฐช่วยได้วันนี้อีก */
    val leftToday: Double,
    /** ควรซื้อราคาเต็มเท่าไหร่จะใช้สิทธิวันนี้ครบ */
    val fullPriceForToday: Double,
    val totalLeft: Double,
)

object Home {
    fun figures(ov: Overview): HomeFigures {
        val fx = ov.fx
        val debtTotal = round2(
            ov.accounts.filter { it.account.active && it.isCredit }
                .fold(0.0) { s, a -> s + (a.used ?: 0.0) * (fx[a.account.currency] ?: 1.0) }
        )
        val end = Dates.parse(ov.payCycleRange.end)
        val daysLeft = maxOf(1, ChronoUnit.DAYS.between(Dates.parse(ov.today), end).toInt() + 1)
        val hasPlan = ov.plan.count > 0
        val base = if (hasPlan) ov.plan.projected else ov.plan.cashNow
        val names = ov.accounts.associate { it.account.id to it.account.name }

        val next = ov.debt.values.mapNotNull { st ->
            val cur = st.currentBill
            val nb = st.nextBill
            when {
                cur != null -> NextDebtBill(names[st.accountId].orEmpty(), cur.due, cur.amount, cur.overdue == true || cur.due < ov.today)
                nb != null -> NextDebtBill(names[st.accountId].orEmpty(), nb.due, nb.totalDue, false)
                else -> null
            }
        }.minByOrNull { it.due }

        val copay = ov.copay?.takeIf { it.active }?.let { c ->
            val left = c.usage.leftToday
            CopayCard(
                name = c.config.name,
                daysLeft = ChronoUnit.DAYS.between(Dates.parse(ov.today), Dates.parse(c.config.end)).toInt() + 1,
                leftToday = left,
                fullPriceForToday = if (c.config.rate > 0) round2(left / c.config.rate) else 0.0,
                totalLeft = c.usage.totalLeft,
            )
        }

        return HomeFigures(
            cashNow = ov.plan.cashNow,
            debtTotal = debtTotal,
            projected = if (hasPlan) ov.plan.projected else null,
            perDay = round2(base / daysLeft),
            daysLeft = daysLeft,
            nextDebtBill = next,
            cycleSpend = ov.debt.values.filterIsInstance<AscendState>().map { names[it.accountId].orEmpty() to it.cycleSpend },
            copay = copay,
        )
    }
}
