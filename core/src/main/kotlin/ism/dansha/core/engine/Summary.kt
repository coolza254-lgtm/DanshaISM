package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction
import java.time.LocalDate

/* หน้า "สรุป": รายรับ/รายจ่ายตามหมวดในช่วงวันที่ + รายวัน (เงินบาท แปลงสกุลด้วยเรทใน fx) */

data class CategoryTotal(
    val categoryId: String,
    val name: String,
    val icon: String,
    val color: String,
    val amount: Double,
    val count: Int,
    /** สัดส่วนของยอดรวมประเภทเดียวกัน (0–100) */
    val share: Double,
    /** หมวดย่อยในหมวดนี้ */
    val children: List<CategoryTotal> = emptyList(),
)

data class DayTotal(val date: String, val income: Double, val expense: Double)

data class PeriodSummary(
    val start: String,
    val end: String,
    val income: Double,
    val expense: Double,
    val net: Double,
    val expenseByCategory: List<CategoryTotal>,
    val incomeByCategory: List<CategoryTotal>,
    /** ทุกวันในช่วง (รวมวันที่ไม่มีรายการ) */
    val daily: List<DayTotal>,
    val transactions: List<Transaction>,
    /** รัฐช่วยจ่าย (60/40) ในช่วงนี้ */
    val govSubsidy: Double,
    val avgExpensePerDay: Double,
)

object Summary {
    /** ยอดเป็นบาท: สกุลของรายการ (ว่าง = สกุลของบัญชี) × เรท */
    fun thb(t: Transaction, d: DanshaData, fx: Map<String, Double>): Double {
        val cur = t.currency.ifEmpty { d.accounts.firstOrNull { it.id == t.account_id }?.currency.orEmpty() }.ifEmpty { "THB" }
        return t.amount.num() * (fx[cur] ?: 1.0)
    }

    fun period(d: DanshaData, start: String, end: String): PeriodSummary {
        val fx = Balances.fxMap(d.fx)
        val rows = d.transactions.filter { it.date in start..end && it.type != "transfer" }
        val cats = d.categories.associateBy { it.id }

        fun group(type: String): List<CategoryTotal> {
            val list = rows.filter { it.type == type }
            val total = list.sumOf { thb(it, d, fx) }
            return list.groupBy { t -> cats[t.category_id]?.let { if (it.parent_id.isNotEmpty()) it.parent_id else it.id } ?: t.category_id }
                .map { (catId, ts) ->
                    val c = cats[catId]
                    val amount = ts.sumOf { thb(it, d, fx) }
                    val children = ts.filter { it.subcategory_id.isNotEmpty() }.groupBy { it.subcategory_id }.map { (subId, ss) ->
                        val s = cats[subId]
                        val a = ss.sumOf { thb(it, d, fx) }
                        CategoryTotal(subId, s?.name ?: "(ไม่มีหมวด)", s?.icon.orEmpty(), s?.color.orEmpty(), round2(a), ss.size, if (amount > 0) round2(a / amount * 100) else 0.0)
                    }.sortedByDescending { it.amount }
                    CategoryTotal(
                        catId, c?.name ?: "ไม่ระบุหมวด", c?.icon ?: "•", c?.color.orEmpty(), round2(amount), ts.size,
                        if (total > 0) round2(amount / total * 100) else 0.0, children,
                    )
                }
                .sortedByDescending { it.amount }
        }

        val s = LocalDate.parse(start)
        val e = LocalDate.parse(end)
        val byDay = rows.groupBy { it.date }
        val daily = generateSequence(s) { it.plusDays(1) }.takeWhile { !it.isAfter(e) }.map { day ->
            val ts = byDay[day.toString()].orEmpty()
            DayTotal(
                day.toString(),
                round2(ts.filter { it.type == "income" }.sumOf { thb(it, d, fx) }),
                round2(ts.filter { it.type == "expense" }.sumOf { thb(it, d, fx) }),
            )
        }.toList()
        val income = round2(rows.filter { it.type == "income" }.sumOf { thb(it, d, fx) })
        val expense = round2(rows.filter { it.type == "expense" }.sumOf { thb(it, d, fx) })
        val days = maxOf(1, daily.size)
        return PeriodSummary(
            start, end, income, expense, round2(income - expense), group("expense"), group("income"), daily,
            d.transactions.filter { it.date in start..end }.sortedWith(compareByDescending<Transaction> { it.date }.thenByDescending { it.created_at }),
            round2(rows.sumOf { it.gov_subsidy.num() }),
            round2(expense / days),
        )
    }

    /** ช่วงวันที่สำเร็จรูป */
    enum class Preset(val label: String) { ThisCycle("รอบนี้"), LastCycle("รอบก่อน"), ThisMonth("เดือนนี้"), LastMonth("เดือนก่อน"), Last7("7 วัน"), Last30("30 วัน") }

    fun range(preset: Preset, today: LocalDate, startDay: Int): Pair<String, String> = when (preset) {
        Preset.ThisCycle -> Dates.payCycleRange(Dates.payCycleOf(today, startDay), startDay).let { it.first.toString() to it.second.toString() }
        Preset.LastCycle -> Dates.payCycleRange(Outlook.shiftCycle(Dates.payCycleOf(today, startDay), -1), startDay).let { it.first.toString() to it.second.toString() }
        Preset.ThisMonth -> today.withDayOfMonth(1).toString() to today.withDayOfMonth(today.lengthOfMonth()).toString()
        Preset.LastMonth -> today.minusMonths(1).let { it.withDayOfMonth(1).toString() to it.withDayOfMonth(it.lengthOfMonth()).toString() }
        Preset.Last7 -> today.minusDays(6).toString() to today.toString()
        Preset.Last30 -> today.minusDays(29).toString() to today.toString()
    }
}
