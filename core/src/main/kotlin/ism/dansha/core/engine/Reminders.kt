package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/*
 * รายการใกล้ครบกำหนด + เลือกว่าต้องแจ้งเตือนอะไร (port ตรรกะจาก mail.js — ใช้กับแจ้งเตือนในเครื่องแทนอีเมล)
 */

/** kind = debt (หนี้ PayNext/SPayLater) | bill (บิลในแผนที่ยังไม่จ่าย) */
@Serializable
data class DueItem(val kind: String, val name: String, val due: String, val amount: Double, val days: Int, val overdue: Boolean)

object Reminders {
    fun daysUntil(from: String, to: String): Int = ChronoUnit.DAYS.between(LocalDate.parse(from), LocalDate.parse(to)).toInt()

    /** รายการที่ครบกำหนดภายใน within วัน (รวมที่เลยกำหนดแล้ว) เรียงตามวันครบกำหนด */
    fun upcomingDues(d: DanshaData, today: String, within: Int): List<DueItem> {
        val debt = Engine.debtStates(d, today, false)
        val out = ArrayList<DueItem>()
        debt.forEach { (id, st) ->
            val acc = d.accounts.first { it.id == id }
            val cur = st.currentBill
            val next = st.nextBill
            val b = cur ?: if (next != null && next.date <= today) CurrentBill(next.due, next.totalDue) else null
            if (b != null && b.amount > 0) {
                out += DueItem("debt", acc.name, b.due, b.amount, daysUntil(today, b.due), b.overdue == true || b.due < today)
            }
        }
        d.bills.filter { b -> b.status != "paid" && b.group != "income" && b.due_date.isNotEmpty() && !(b.to_account_id.isNotEmpty() && debt.containsKey(b.to_account_id)) }
            .forEach { b -> out += DueItem("bill", b.name, b.due_date, b.est_amount.num(), daysUntil(today, b.due_date), b.due_date < today) }
        return out.filter { it.days <= within }.sortedBy { it.due }
    }

    private fun days(s: String?): List<Int> =
        s.orEmpty().split(",").map { jsNumber(it) }.filter { it >= 0 }.map { it.toInt() }

    /** รายการที่ต้องแจ้งเตือนวันนี้ ตามค่า debt_reminder_* / bill_reminder_* */
    fun reminderItems(d: DanshaData, cfg: Map<String, String>, today: String): List<DueItem> {
        val debtDays = days(cfg["debt_reminder_days"])
        val billDays = days(cfg["bill_reminder_days"])
        val debtOn = cfg["debt_reminder_enabled"] == "true"
        val billOn = cfg["bill_reminder_enabled"] == "true"
        val within = (debtDays + billDays + 0).max()
        return upcomingDues(d, today, within).filter { x ->
            if (x.overdue) x.kind == "debt" && debtOn
            else if (x.kind == "debt") debtOn && x.days in debtDays
            else billOn && x.days in billDays
        }
    }

    /** ฿1,234.50 / −฿50.00 (รูปแบบเงินของข้อความสรุปเดิม) */
    fun money(n: Double): String {
        val abs = BigDecimal(kotlin.math.abs(n)).setScale(2, RoundingMode.HALF_EVEN)
        val s = java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols(java.util.Locale.US)).format(abs)
        return (if (n < 0) "−" else "") + "฿" + s
    }
}
