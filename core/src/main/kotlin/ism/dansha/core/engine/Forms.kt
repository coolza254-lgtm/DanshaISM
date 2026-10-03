package ism.dansha.core.engine

import ism.dansha.core.Account
import ism.dansha.core.Category
import ism.dansha.core.DanshaData
import ism.dansha.core.Dates
import ism.dansha.core.Transaction

/** ตัวช่วยของฟอร์มในแอพ (ไม่ผูกกับหน้าจอ เพื่อให้เปลี่ยน UI ได้โดยไม่กระทบตรรกะ) */
object Forms {
    /** "1,234.50" → 1234.5, ว่าง/อ่านไม่ได้ = null */
    fun parseAmount(text: String): Double? {
        val t = text.replace(",", "").replace(" ", "").replace("฿", "").trim()
        if (t.isEmpty()) return null
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }

    /** ตัวอย่างส่วนที่รัฐจ่าย 60/40 ก่อนบันทึก */
    data class CopayPreview(
        val name: String,
        val gov: Double,
        val self: Double,
        /** รัฐช่วยได้อีกในวันนั้น (ก่อนรายการนี้) */
        val leftToday: Double,
        val ratePct: Double,
    )

    /**
     * ใช้สิทธิ 60/40 ได้ไหม: รายจ่ายจากบัญชี G-Wallet ที่ตั้งไว้ ในช่วงโครงการ
     * excludeId = รายการที่กำลังแก้ (ไม่นับตัวเอง)
     */
    fun copayEligible(d: DanshaData, type: String, accountId: String, date: String): Boolean {
        val cc = Copay.config(Engine.config(d))
        return cc.enabled && type == "expense" && accountId.isNotEmpty() &&
            (cc.accountId.isEmpty() || cc.accountId == accountId) && date >= cc.start && date <= cc.end
    }

    fun copayPreview(d: DanshaData, type: String, accountId: String, date: String, fullPrice: Double?, excludeId: String? = null): CopayPreview? {
        if (!copayEligible(d, type, accountId, date)) return null
        val cc = Copay.config(Engine.config(d))
        val usage = Copay.usage(d.transactions, cc, date, excludeId)
        val split = Copay.split(fullPrice ?: 0.0, cc, usage)
        return CopayPreview(cc.name, split.gov, split.self, usage.leftToday, cc.rate * 100)
    }

    /** รอบที่มีรายการ + รอบปัจจุบัน (ใหม่ก่อน) */
    fun cycles(d: DanshaData, current: String): List<String> =
        (d.transactions.map { it.pay_cycle }.filter { it.length >= 8 } + current).distinct().sortedDescending()

    /** บัญชีที่ใช้ล่าสุดของประเภทรายการนี้ ไม่มี = บัญชีเงินสด/ธนาคารแรกที่เปิดใช้ */
    fun defaultAccount(d: DanshaData, type: String): String {
        val active = d.accounts.filter { it.active }.map { it.id }.toSet()
        d.transactions.lastOrNull { it.type == type && it.account_id in active }?.let { return it.account_id }
        return d.accounts.filter { it.active && it.type != "revolving_credit" }.sortedBy { it.sort ?: Int.MAX_VALUE }.firstOrNull()?.id
            ?: d.accounts.firstOrNull { it.active }?.id.orEmpty()
    }

    /** บัญชีที่เลือกได้ในฟอร์ม (เปิดใช้ + บัญชีเดิมของรายการ แม้ปิดใช้แล้ว) เรียงตาม sort */
    fun accountChoices(d: DanshaData, keep: String = ""): List<Account> =
        d.accounts.filter { it.active || it.id == keep }.sortedWith(compareBy({ it.sort ?: Int.MAX_VALUE }, { it.name }))

    data class CategoryNode(val category: Category, val children: List<Category>)

    /** หมวดหลัก → หมวดย่อย ของประเภท income/expense (เฉพาะที่เปิดใช้ + ที่เลือกอยู่) */
    fun categoryTree(d: DanshaData, type: String, keep: Set<String> = emptySet()): List<CategoryNode> {
        val ok = d.categories.filter { it.type == type && (it.active || it.id in keep) }
        val byParent = ok.groupBy { it.parent_id }
        return ok.filter { it.parent_id.isEmpty() }.sortedBy { it.sort ?: 0 }
            .map { p -> CategoryNode(p, byParent[p.id].orEmpty().sortedBy { it.sort ?: 0 }) }
    }

    /** ค้นหารายการ: โน้ต ชื่อหมวด ชื่อบัญชี หรือยอดเงิน */
    fun search(d: DanshaData, query: String): List<Transaction> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return d.transactions
        val acc = d.accounts.associate { it.id to it.name.lowercase() }
        val cat = d.categories.associate { it.id to it.name.lowercase() }
        return d.transactions.filter { t ->
            t.note.lowercase().contains(q) ||
                (cat[t.category_id]?.contains(q) == true) || (cat[t.subcategory_id]?.contains(q) == true) ||
                (acc[t.account_id]?.contains(q) == true) || (acc[t.to_account_id]?.contains(q) == true) ||
                (t.amount?.toPlainString()?.contains(q) == true)
        }
    }

    /** วันนี้ (เวลาไทย) */
    fun today(): String = Dates.todayStr()
}
