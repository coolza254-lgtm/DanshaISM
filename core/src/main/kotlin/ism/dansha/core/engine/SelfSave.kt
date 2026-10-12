package ism.dansha.core.engine

import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction

/*
 * ออมแบบบังคับตัวเอง (แอพ Android) — เปิด/ปิดแยกกันได้
 *
 * Myself Tax: ทุกรายจ่ายเสียภาษีให้ตัวเองตาม % ของหมวด (ตั้งเองได้ หมวดย่อยใช้ของหมวดหลัก)
 *             + ภาษีเงินได้ของตัวเอง: ทุกรายรับหัก x% (ค่าตั้งต้น 1%) — ไม่นับเงินเบิก Staff Claim (เป็นเงินคืน ไม่ใช่รายได้)
 * No Discount: ส่วนลดที่ได้ (ราคาปกติ − ที่จ่ายจริง) ไม่นับว่าประหยัด แต่ต้องเก็บเข้ากระปุก
 *
 * ยอดค้าง = ยอดสะสมตั้งแต่วันที่เปิดใช้ − ที่โอนเข้าบัญชีลงทุนแล้ว (รายการโอน source = mytax / nodiscount)
 */
object SelfSave {
    const val TAX = "mytax"
    const val DISCOUNT = "nodiscount"

    data class Settings(
        val taxOn: Boolean,
        val taxStart: String,
        val incomePct: Double,
        /** % ต่อหมวดหลัก (id → %) */
        val rates: Map<String, Double>,
        val defaultPct: Double,
        val discountOn: Boolean,
        val discountStart: String,
        val targetAccountId: String,
    )

    fun settings(config: Map<String, String>): Settings = Settings(
        taxOn = config["mytax_enabled"] == "true",
        taxStart = config["mytax_start"].orEmpty(),
        incomePct = config["mytax_income_pct"]?.toDoubleOrNull() ?: 1.0,
        rates = config.filterKeys { it.startsWith("mytax_rate:") }
            .mapNotNull { (k, v) -> v.toDoubleOrNull()?.let { k.removePrefix("mytax_rate:") to it } }.toMap(),
        defaultPct = config["mytax_default_pct"]?.toDoubleOrNull() ?: 0.0,
        discountOn = config["nodiscount_enabled"] == "true",
        discountStart = config["nodiscount_start"].orEmpty(),
        targetAccountId = config["savings_account_id"].orEmpty(),
    )

    data class Line(val txn: Transaction, val kind: String, val base: Double, val pct: Double?, val amount: Double)

    data class Status(
        val settings: Settings,
        val taxAccrued: Double,
        val taxPaid: Double,
        val discountAccrued: Double,
        val discountPaid: Double,
        /** รายการที่ทำให้เกิดยอด (ใหม่ก่อน) */
        val lines: List<Line>,
        /** ยอดที่เกิดวันนี้ */
        val today: Double,
    ) {
        val taxDue: Double get() = round2(maxOf(0.0, taxAccrued - taxPaid))
        val discountDue: Double get() = round2(maxOf(0.0, discountAccrued - discountPaid))
        val totalDue: Double get() = round2(taxDue + discountDue)
        val enabled: Boolean get() = settings.taxOn || settings.discountOn
    }

    /** % ภาษีของหมวด (หมวดย่อยที่ไม่ได้ตั้ง ใช้ของหมวดหลัก) */
    fun rateOf(s: Settings, categoryId: String, subcategoryId: String): Double =
        s.rates[subcategoryId] ?: s.rates[categoryId] ?: s.defaultPct

    fun status(d: DanshaData, today: String = Dates.todayStr()): Status {
        val s = settings(Engine.config(d))
        val claimBills = d.bills.filter { it.section == PlanBook.CLAIM }.map { it.id }.toSet()
        val lines = ArrayList<Line>()
        if (s.taxOn) {
            val start = s.taxStart.ifEmpty { today }
            d.transactions.filter { it.date >= start }.forEach { t ->
                when {
                    t.type == "expense" -> {
                        val pct = rateOf(s, t.category_id, t.subcategory_id)
                        val base = t.amount.num()
                        if (pct > 0 && base > 0) lines += Line(t, TAX, base, pct, round2(base * pct / 100))
                    }
                    t.type == "income" && !(t.source == "bill_plan" && t.ref_id in claimBills) -> {
                        val base = t.amount.num()
                        if (s.incomePct > 0 && base > 0) lines += Line(t, TAX, base, s.incomePct, round2(base * s.incomePct / 100))
                    }
                }
            }
        }
        if (s.discountOn) {
            val start = s.discountStart.ifEmpty { today }
            d.transactions.filter { it.date >= start && it.type == "expense" && it.discount.num() > 0 }.forEach { t ->
                lines += Line(t, DISCOUNT, t.discount.num(), null, round2(t.discount.num()))
            }
        }
        fun paid(kind: String, start: String) = round2(
            d.transactions.filter { it.type == "transfer" && it.source == kind && it.date >= start.ifEmpty { "0000" } }.sumOf { it.amount.num() },
        )
        val sorted = lines.sortedWith(compareByDescending<Line> { it.txn.date }.thenByDescending { it.txn.created_at })
        return Status(
            settings = s,
            taxAccrued = round2(lines.filter { it.kind == TAX }.sumOf { it.amount }),
            taxPaid = if (s.taxOn) paid(TAX, s.taxStart) else 0.0,
            discountAccrued = round2(lines.filter { it.kind == DISCOUNT }.sumOf { it.amount }),
            discountPaid = if (s.discountOn) paid(DISCOUNT, s.discountStart) else 0.0,
            lines = sorted,
            today = round2(lines.filter { it.txn.date == today }.sumOf { it.amount }),
        )
    }
}
